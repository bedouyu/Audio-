"""Live agent evaluation against the real model.

Runs the multi-agent pipeline through the backend HTTP API and reports, per
case: duration deviation, whether review happened, tool-call count and wall
time. Repeats each case so variance is visible instead of a single lucky run.

    python tools/live-agent-check.py --repeats 2

Requires the backend on 127.0.0.1:18081 and the ai-services compose project.
"""
import argparse
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18081"
CJK = re.compile(r"[\u4e00-\u9fff]")
WORD = re.compile(r"[A-Za-z0-9']+")
# 真实语速：用 12 段已合成 WAV 与其讲稿反算得到 193 字/分钟（tools/measure-speech-rate.py）。
# 评测按实测值打分，而不是按代码里的常数——否则改常数就能让指标变好。
CJK_PER_MIN = 193.0
WORDS_PER_MIN = 150.0

# 本轮产出的讲稿正文与原始要求，供内容验收使用。
SCRIPTS = {}
REQUESTS = {}
LECTURE_MARKERS = ("第一点", "第二点", "首先", "其次", "最后一点", "课程大纲", "本课", "知识点一",
                   "firstly", "secondly", "in this lesson", "learning objectives")
META_MARKERS = ("字数统计", "草稿说明", "供你审阅", "word count", "draft notes", "estimate only")

CHAT_CASES = [
    (3, "写一段 3 分钟的睡前故事，主题是月亮为什么会变形，语气温柔", "chat-zh-3min"),
    (2, "write a 2 minute bedtime story in English about a sleepy lighthouse keeper", "chat-en-2min"),
]
AUDIO_CASES = [
    (3, "讲一段 3 分钟的睡前故事，主题是小熊第一次独自去森林", "audio-zh-3min"),
    (2, "tell me a 2 minute bedtime story in English about a lighthouse keeper who cannot sleep", "audio-en-2min"),
]


def post_json(path, body, token=None, timeout=600):
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BASE + path, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read().decode("utf-8", "replace")


def get_json(path, token, timeout=120):
    request = urllib.request.Request(BASE + path)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8") or "{}")


def estimate(text):
    cjk = len(CJK.findall(text))
    words = len(WORD.findall(text))
    seconds = cjk / CJK_PER_MIN * 60 + words / WORDS_PER_MIN * 60
    old_seconds = len(re.sub(r"\s+", "", text)) / CJK_PER_MIN * 60
    return cjk, words, seconds, old_seconds


def parse_sse(payload):
    turns, events = [], []
    for line in payload.splitlines():
        if not line.startswith("data:"):
            continue
        raw = line[5:].strip()
        if not raw:
            continue
        try:
            event = json.loads(raw)
        except ValueError:
            continue
        data = event.get("data")
        if isinstance(data, str):
            turns.append(data)
        elif isinstance(data, dict):
            events.append(data)
    return turns, events


def newest_by_kind(artifacts):
    """列表按创建时间倒序；每种类型只保留最新一份。"""
    newest = {}
    for item in artifacts:
        newest.setdefault(item["kind"], item)
    return newest


def register(token_name):
    status, registered = post_json("/api/v1/auth/register", {"username": token_name, "password": "password1"})
    return json.loads(registered)["token"]


def new_project(token, prefix):
    status, project = post_json("/api/v1/projects", {"topic": f"{prefix}-{uuid.uuid4().hex[:6]}"}, token)
    return json.loads(project)["id"]


def run_chat_case(token, minutes, message, label):
    started = time.time()
    project_id = new_project(token, f"eval-{label}")
    body = {"message": message, "project_id": project_id, "request_id": str(uuid.uuid4())}
    code, payload = post_json("/api/v1/chat/stream", body, token)
    elapsed = time.time() - started
    turns, events = parse_sse(payload)
    artifacts = get_json(f"/api/v1/projects/{project_id}/agent/artifacts", token)["artifacts"]
    newest = newest_by_kind(artifacts)
    draft, review = newest.get("SCRIPT_DRAFT"), newest.get("REVIEW_REPORT")
    if not draft:
        print(f"[{label}] no SCRIPT_DRAFT; artifacts={sorted(newest)}")
        return None
    script = draft["content"]
    cjk, words, seconds, old_seconds = estimate(script)
    target = minutes * 60.0
    approved = None
    issues = []
    if review:
        try:
            report = json.loads(review["content"])
            approved, issues = report.get("approved"), report.get("issues") or []
        except ValueError:
            approved = "unparsed"
    row = {"label": label, "minutes": minutes, "deviation": (seconds - target) / target,
           "review_present": review is not None, "approved": approved,
           "drafts": sum(1 for a in artifacts if a["kind"] == "SCRIPT_DRAFT"),
           "tool_events": len(events), "wall": elapsed,
           "estimated_minutes": round(seconds / 60, 2), "old_rule_minutes": round(old_seconds / 60, 2),
           "issue_count": len(issues)}
    SCRIPTS[label] = script
    REQUESTS[label] = message
    print(json.dumps(row, ensure_ascii=False))
    return row


def run_audio_case(token, minutes, instruction, label):
    started = time.time()
    project_id = new_project(token, f"eval-{label}")
    body = {"instruction": instruction, "request_id": str(uuid.uuid4()), "project_id": project_id}
    code, payload = post_json("/api/v1/audio/requests", body, token)
    queue_id = json.loads(payload).get("queue_id")
    snapshot = {}
    deadline = time.time() + 420
    while time.time() < deadline:
        try:
            snapshot = get_json(f"/api/v1/continuous-audio/{queue_id}", token)
        except urllib.error.URLError:
            snapshot = {}
        if snapshot.get("status") in ("COMPLETE", "FAILED", "NEEDS_REVIEW", "CANCELLED"):
            break
        time.sleep(4)
    elapsed = time.time() - started
    artifacts = get_json(f"/api/v1/projects/{project_id}/agent/artifacts", token)["artifacts"]
    newest = newest_by_kind(artifacts)
    draft, review = newest.get("SCRIPT_DRAFT"), newest.get("REVIEW_REPORT")
    lessons = snapshot.get("lessons") or []
    script = lessons[0].get("script", "") if lessons else (draft["content"] if draft else "")
    if not script:
        print(f"[{label}] status={snapshot.get('status')} no script; errors={snapshot.get('errors')}")
        return {"label": label, "minutes": minutes, "ok": False, "wall": elapsed,
                "status": snapshot.get("status")}
    cjk, words, seconds, old_seconds = estimate(script)
    target = minutes * 60.0
    approved = None
    if review:
        try:
            approved = json.loads(review["content"]).get("approved")
        except ValueError:
            approved = "unparsed"
    row = {"label": label, "minutes": minutes, "deviation": (seconds - target) / target,
           "review_present": review is not None, "approved": approved,
           "drafts": sum(1 for a in artifacts if a["kind"] == "SCRIPT_DRAFT"),
           "tool_events": 0, "wall": elapsed,
           "estimated_minutes": round(seconds / 60, 2), "old_rule_minutes": round(old_seconds / 60, 2),
           "status": snapshot.get("status"), "segments": len(lessons),
           "audio_files": sum(1 for item in lessons if item.get("audio_url")),
           "consumed": draft is not None and draft["status"] == "CONSUMED"}
    SCRIPTS[label] = script
    REQUESTS[label] = instruction
    print(json.dumps(row, ensure_ascii=False))
    return row


def deterministic_checks(request, script):
    """能确定性判断的先自己判，不交给模型。"""
    lower = script.lower()
    english = not CJK.search(script)
    story = ("故事" in request) or ("story" in request.lower())
    ending = script.strip()[-1:] if script.strip() else ""
    return {
        "no_meta_leak": not any(marker in lower for marker in META_MARKERS),
        "no_unrequested_lecture": not (story and any(marker in script for marker in LECTURE_MARKERS)),
        "language_matches": english == ("english" in request.lower() or "英文" in request or "英语" in request),
        "has_ending": ending in "。.!！?？…」\"'",
    }


def judge_conformance(request, script, label):
    """让模型当验收员，逐条判断是否满足用户要求。"""
    key = os.environ.get("DEEPSEEK_API_KEY") or os.environ.get("AUDIORAG_LLM_API_KEY")
    if not key:
        print(f"[{label}] 缺少 API 密钥，跳过模型验收")
        return None
    prompt = (
        "你是严格的音频内容验收员。用户要求：\n" + request
        + "\n\n待验收的讲稿正文：\n" + script
        + "\n\n逐条判断正文是否满足用户要求，必须覆盖：主题是否命中；体裁（故事/简报/讲解）是否正确；"
        "语气是否符合；语言是否正确；是否有完整结尾且适合朗读；用户没要求时是否出现课程大纲或"
        "“首先/其次/第一点”式讲课结构（算缺陷）；是否混入字数统计、草稿说明等元信息（算缺陷）；"
        "是否明显注水、重复或跑题。\n"
        '只输出 JSON，不要代码围栏：{"requirements":[{"item":"要求","met":true或false,'
        '"evidence":"正文中的简短依据"}],"overall":1到5的整数,"blocking":["最严重的问题，没有则空数组"]}'
    )
    body = {"model": os.environ.get("AUDIORAG_LLM_MODEL", "deepseek-flash"),
            "messages": [{"role": "user", "content": prompt}], "temperature": 0}
    request_obj = urllib.request.Request("https://api.deepseek.com/chat/completions",
                                         data=json.dumps(body).encode("utf-8"), method="POST")
    request_obj.add_header("Content-Type", "application/json")
    request_obj.add_header("Authorization", "Bearer " + key)
    try:
        with urllib.request.urlopen(request_obj, timeout=120) as response:
            payload = json.loads(response.read().decode("utf-8"))
        content = payload["choices"][0]["message"]["content"].strip()
        if content.startswith("```"):
            content = re.sub(r"^```[a-zA-Z]*\n?|\n?```$", "", content).strip()
        return json.loads(content)
    except Exception as error:  # noqa: BLE001 - judge is advisory
        print(f"[{label}] 模型验收失败: {type(error).__name__}: {error}")
        return None


def report_conformance(use_judge):
    if not SCRIPTS:
        return
    print("\nCONFORMANCE")
    for label, script in SCRIPTS.items():
        checks = deterministic_checks(REQUESTS[label], script)
        passed = sum(1 for value in checks.values() if value)
        line = f"  {label:20} deterministic {passed}/{len(checks)} " + \
               " ".join(f"{name}={'ok' if value else 'FAIL'}" for name, value in checks.items())
        print(line)
        if not use_judge:
            continue
        verdict = judge_conformance(REQUESTS[label], script, label)
        if not verdict:
            continue
        requirements = verdict.get("requirements") or []
        met = sum(1 for item in requirements if item.get("met"))
        print(f"  {'':20} judge {met}/{len(requirements)} met, overall {verdict.get('overall')}/5")
        for item in requirements:
            if not item.get("met"):
                print(f"  {'':20}   unmet: {item.get('item')} -> {str(item.get('evidence'))[:120]}")
        blocking = verdict.get("blocking") or []
        if blocking:
            print(f"  {'':20}   blocking: {str(blocking[0])[:160]}")


def summarise(rows):
    """按去掉 #序号 的基础用例名聚合，才能看出同一用例多次运行的离散度。"""
    print("\nSUMMARY")
    print(f"  {'case':18} {'n':>2} {'mean|dev|':>9} {'stdev':>7} {'min':>6} {'max':>6} {'review%':>8} {'mean s':>7}")
    for label in sorted({row["label"].split("#")[0] for row in rows}):
        group = [row for row in rows if row["label"].split("#")[0] == label and "deviation" in row]
        if not group:
            print(f"  {label:18} 0 (no usable run)")
            continue
        deviations = [abs(row["deviation"]) for row in group]
        stdev = statistics.stdev(deviations) if len(deviations) > 1 else 0.0
        reviewed = sum(1 for row in group if row.get("review_present")) / len(group)
        print(f"  {label:18} {len(group):>2} {statistics.mean(deviations)*100:>8.1f}% {stdev*100:>6.1f}% "
              f"{min(deviations)*100:>5.1f}% {max(deviations)*100:>5.1f}% {reviewed*100:>7.0f}% "
              f"{statistics.mean([r['wall'] for r in group]):>7.1f}")
    usable = [row for row in rows if "deviation" in row]
    if usable:
        print(f"  overall mean |deviation| = {statistics.mean(abs(r['deviation']) for r in usable)*100:.1f}%")
        print(f"  review coverage = {sum(1 for r in usable if r.get('review_present'))/len(usable)*100:.0f}%")
        print(f"  runs = {len(usable)}, failed = {len(rows) - len(usable)}")
    else:
        print("  no usable runs")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--only", choices=["chat", "audio"], default=None)
    parser.add_argument("--no-judge", action="store_true", help="跳过模型验收，只跑确定性检查")
    args = parser.parse_args()

    token = register("live" + uuid.uuid4().hex[:10])
    rows = []
    for repeat in range(args.repeats):
        if args.only != "audio":
            for minutes, message, label in CHAT_CASES:
                try:
                    row = run_chat_case(token, minutes, message, f"{label}#{repeat+1}")
                    if row:
                        rows.append(row)
                except Exception as error:  # noqa: BLE001 - report and continue
                    print(f"[{label}#{repeat+1}] FAILED: {type(error).__name__}: {error}")
        if args.only != "chat":
            for minutes, instruction, label in AUDIO_CASES:
                try:
                    row = run_audio_case(token, minutes, instruction, f"{label}#{repeat+1}")
                    if row:
                        rows.append(row)
                except Exception as error:  # noqa: BLE001 - report and continue
                    print(f"[{label}#{repeat+1}] FAILED: {type(error).__name__}: {error}")
    summarise(rows)
    report_conformance(not args.no_judge)
    return 0 if rows else 1


if __name__ == "__main__":
    sys.exit(main())
