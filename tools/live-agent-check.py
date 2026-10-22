"""Live agent test: real DeepSeek calls through the multi-agent pipeline.

Exercises POST /api/v1/chat/stream (supervisor -> script_writer -> review_agent)
and measures what the recent changes were meant to fix:

  * does the script match the requested duration (language-aware density)?
  * does review_agent report a real deviation instead of a vague pass?
  * how far off would the old "260 characters per minute" rule have been?
"""
import json
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
CJK_PER_MIN = 260.0
WORDS_PER_MIN = 150.0


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
    turns = []
    tools = []
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
        payload_data = event.get("data")
        if isinstance(payload_data, str):
            turns.append(payload_data)
        elif isinstance(payload_data, dict):
            tools.append(payload_data)
    return turns, tools


def run_case(token, minutes, message, label):
    status, project = post_json("/api/v1/projects", {"topic": f"live-{label}-{uuid.uuid4().hex[:6]}"}, token)
    project_id = json.loads(project)["id"]
    body = {"message": message, "project_id": project_id, "request_id": str(uuid.uuid4())}
    started = time.time()
    code, payload = post_json("/api/v1/chat/stream", body, token)
    elapsed = time.time() - started
    turns, tools = parse_sse(payload)
    artifacts = get_json(f"/api/v1/projects/{project_id}/agent/artifacts", token)["artifacts"]
    # 列表按创建时间倒序；每种类型只保留最新一份（修订稿），否则测到的是被取代的旧稿。
    by_kind = {}
    for item in artifacts:
        by_kind.setdefault(item["kind"], item)
    draft = by_kind.get("SCRIPT_DRAFT")
    review = by_kind.get("REVIEW_REPORT")
    drafts = [item for item in artifacts if item["kind"] == "SCRIPT_DRAFT"]
    superseded = [item for item in drafts if item["status"] == "SUPERSEDED"]
    if not draft:
        print(f"[{label}] no SCRIPT_DRAFT; artifacts={list(by_kind)}; stream={payload[:400]}")
        return None
    script = draft["content"]
    cjk, words, seconds, old_seconds = estimate(script)
    target = minutes * 60.0
    deviation = (seconds - target) / target
    approved, issues = None, []
    if review:
        try:
            report = json.loads(review["content"])
            approved = report.get("approved")
            issues = report.get("issues") or []
        except ValueError:
            approved = "unparsed"
    print(json.dumps({
        "label": label,
        "requested_minutes": minutes,
        "wall_seconds": round(elapsed, 1),
        "stream_http": code,
        "answer_preview": (turns[-1][:120] if turns else ""),
        "tool_events": len(tools),
        "draft_status": draft["status"],
        "draft_count": len(drafts),
        "superseded_drafts": len(superseded),
        "characters": len(script),
        "cjk_characters": cjk,
        "latin_words": words,
        "estimated_minutes": round(seconds / 60, 2),
        "deviation_percent": round(deviation * 100, 1),
        "old_260_rule_minutes": round(old_seconds / 60, 2),
        "review_approved": approved,
        "review_issue_count": len(issues),
        "review_issues": issues[:5],
        "all_artifacts": list(by_kind),
    }, ensure_ascii=False, indent=2))
    return {"label": label, "minutes": minutes, "deviation": deviation}


def main():
    name = "live" + uuid.uuid4().hex[:10]
    status, registered = post_json("/api/v1/auth/register", {"username": name, "password": "password1"})
    token = json.loads(registered)["token"]
    print(f"registered {name} -> {status}")

    results = []
    cases = [
        (3, "写一段 3 分钟的睡前故事，主题是月亮为什么会变形，语气温柔", "zh-3min"),
        (2, "write a 2 minute bedtime story in English about a sleepy lighthouse keeper", "en-2min"),
    ]
    for minutes, message, label in cases:
        try:
            outcome = run_case(token, minutes, message, label)
            if outcome:
                results.append(outcome)
        except Exception as error:  # noqa: BLE001 - report and continue
            print(f"[{label}] FAILED: {type(error).__name__}: {error}")

    if results:
        print("\nSUMMARY")
        for item in results:
            print(f"  {item['label']}: target {item['minutes']}min |deviation|={abs(item['deviation'])*100:.1f}%")
        print(f"  mean |deviation| = {statistics.mean(abs(r['deviation']) for r in results)*100:.1f}%")
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main())
