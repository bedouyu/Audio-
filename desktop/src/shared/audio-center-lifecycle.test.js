import test from "node:test";
import assert from "node:assert/strict";
import { createRenderer, h, nextTick, reactive } from "vue";
import { useAudioCenter } from "../renderer/src/composables/useAudioCenter.js";

const media = "/media/7ab79a00-2127-4c91-890d-674ab100001f.wav";
const queue = () => ({ queue_id: "queue-one", mode: "CONTINUOUS", status: "COMPLETE", total_segments: 2,
  project: { id: 7, title: "一段故事", topic: "故事" },
  lessons: [{ title: "第一段", script: "one", audio_url: media }, { title: "第二段", script: "two", audio_url: media.replace("001f", "002f") }], segments: [] });
const flush = async () => { await nextTick(); await Promise.resolve(); await nextTick(); };
function harness(api) {
  const values = new Map();
  const previousStorage = globalThis.localStorage;
  globalThis.localStorage = { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
  const state = reactive({ user: { id: 1 }, project: null });
  let audio;
  const renderer = createRenderer({ insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {} });
  const app = renderer.createApp({ setup() {
    audio = useAudioCenter({ state, api: async (path, method = "GET", body) => path.endsWith("/voices") ? { voices: [] } : api(path, method, body),
      mediaUrl: path => path ? "http://127.0.0.1:18081" + path : "", refreshProject: async () => {} });
    return () => h("div");
  } });
  app.mount({});
  let pauses = 0;
  const player = { currentTime: 0, duration: 120, playbackRate: 1, play: async () => {}, pause: () => { pauses++; },
    removeAttribute() {}, load() { this.currentTime = 0; } };
  audio.bindPlayer(player);
  return { audio, state, player, values, pauses: () => pauses,
    close() { app.unmount(); globalThis.localStorage = previousStorage; } };
}

test("global playback survives content navigation, restores per user, and pauses on logout", async () => {
  const run = harness(async () => queue());
  try {
    await run.audio.submit("讲一个故事");
    run.state.project = { id: 7 }; await flush();
    run.player.currentTime = 37; run.audio.timeUpdate(); run.audio.setSpeed(1.5);
    run.state.project = null; await flush();
    assert.equal(run.audio.current.value.title, "第一段");
    assert.equal(JSON.parse(run.values.get("audiorag_listening:1")).time, 37);
    await run.audio.next();
    assert.equal(JSON.parse(run.values.get("audiorag_listening:1")).time, 0, "next segment must not inherit the old playback offset");
    run.player.currentTime = 24; run.audio.timeUpdate(); run.audio.save();
    run.audio.state.scriptDrafts["1:queue-one:1"] = { text: "尚未保存的用户文字", saved: "one" };
    const before = run.pauses(); run.state.user = null; await flush();
    assert.ok(run.pauses() > before);
    assert.equal(run.audio.state.queue, null);
    assert.deepEqual(run.audio.state.scriptDrafts, {}, "logout must discard in-memory editing drafts");
    run.state.user = { id: 2 }; await flush();
    assert.equal(run.audio.state.queue, null, "another user must not restore the previous user's queue");
    run.state.user = { id: 1 }; await flush(); run.audio.loaded();
    assert.equal(run.audio.state.index, 1);
    assert.equal(run.player.currentTime, 24);
    assert.equal(run.player.playbackRate, 1.5);
  } finally { run.close(); }
});

test("a late request response cannot repopulate playback after logout", async () => {
  let resolve;
  const run = harness(() => new Promise(done => { resolve = done; }));
  try {
    const pending = run.audio.submit("读一段英语");
    run.state.user = null; await flush();
    resolve(queue());
    assert.equal(await pending, null);
    assert.equal(run.audio.state.queue, null);
    assert.equal(run.values.size, 0);
  } finally { run.close(); }
});

test("quiet refresh is single-flight and late responses cannot replace another queue or logged-out state", async () => {
  let resolve;
  let reads = 0;
  const run = harness(path => {
    if (path.includes("/continuous-audio/")) { reads++; return new Promise(done => { resolve = done; }); }
    return {};
  });
  try {
    run.audio.state.queue = queue();
    const first = run.audio.refresh();
    const duplicate = run.audio.refresh();
    assert.equal(reads, 1);
    await run.audio.openQueue({ ...queue(), queue_id: "queue-two" }, queue().project, false);
    resolve(queue());
    await first; await duplicate;
    assert.equal(run.audio.state.queue.queue_id, "queue-two");
    const afterLogout = run.audio.refresh();
    run.state.user = null;
    resolve({ ...queue(), queue_id: "queue-two" });
    await afterLogout;
    assert.equal(run.audio.state.queue, null);
  } finally { run.close(); }
});

test("polling started before a manual edit cannot overwrite the acknowledged revision", async () => {
  let resolve;
  const original = { ...queue(), segments: [{ segment_number: 1, script: "旧稿", status: "READY" }] };
  const revised = { ...original, segments: [{ segment_number: 1, script: "新稿", status: "PENDING" }] };
  const run = harness((path, method) => {
    if (path.endsWith("/revise")) return revised;
    if (path.includes("/continuous-audio/") && method === "GET") return new Promise(done => { resolve = done; });
    return {};
  });
  try {
    run.audio.state.queue = original;
    const poll = run.audio.poll();
    assert.equal(await run.audio.reviseSegment(1, { text: "新稿" }, "queue-one"), true);
    resolve(original);
    await poll;
    assert.equal(run.audio.state.queue.segments[0].script, "新稿");
  } finally { run.close(); }
});

test("a stale status poll cannot undo a successful refill or stop its generation polling", async () => {
  let resolve;
  const original = { ...queue(), mode: "CONTINUOUS", status: "READY", storage: "mysql-durable", generating: 0,
    segments: [{ segment_number: 1, script: "第一段", status: "PENDING" }] };
  const refilled = { ...original, status: "GENERATING", generating: 1,
    segments: [{ segment_number: 1, script: "第一段", status: "SYNTHESIZING" }] };
  const run = harness((path, method) => {
    if (path.endsWith("/refill")) return refilled;
    if (path.includes("/continuous-audio/") && method === "GET") return new Promise(done => { resolve = done; });
    return {};
  });
  try {
    run.audio.state.queue = original;
    const poll = run.audio.poll();
    assert.equal(await run.audio.refill(true), true);
    resolve(original);
    await poll;
    assert.equal(run.audio.state.queue.status, "GENERATING");
    assert.equal(run.audio.state.queue.segments[0].status, "SYNTHESIZING");
  } finally { run.close(); }
});

test("manual edits bind their original queue and old completion cannot unlock a new queue edit", async () => {
  const pending = [];
  const run = harness((path, method) => {
    if (path.endsWith("/revise")) return new Promise(done => { pending.push({ path, done }); });
    return {};
  });
  try {
    run.audio.state.queue = { ...queue(), segments: [{ segment_number: 1, script: "旧稿" }] };
    const oldEdit = run.audio.reviseSegment(1, { text: "旧队列修改" }, "queue-one");
    const other = { ...queue(), queue_id: "queue-two", segments: [{ segment_number: 2, script: "另一个队列" }] };
    await run.audio.openQueue(other, queue().project, false);
    assert.equal(run.audio.state.revising, null);
    assert.equal(await run.audio.reviseSegment(1, { text: "过期稿纸" }, "queue-one"), false);
    const newEdit = run.audio.reviseSegment(2, { text: "新队列修改" }, "queue-two");
    assert.equal(pending.length, 2);
    pending[0].done(queue());
    assert.equal(await oldEdit, false);
    assert.equal(run.audio.state.queue.queue_id, "queue-two");
    assert.equal(run.audio.state.revising, 2, "old finally must not unlock the new edit");
    pending[1].done({ ...other, segments: [{ segment_number: 2, script: "新队列修改" }] });
    assert.equal(await newEdit, true);
    assert.equal(run.audio.state.revising, null);
    run.state.user = null;
    assert.equal(await run.audio.reviseSegment(2, { text: "退出后修改" }, "queue-two"), false);
    assert.equal(pending.length, 2);
  } finally { run.close(); }
});

test("manual autosave skips unchanged text instead of issuing another synthesis request", async () => {
  const mutations = [];
  const run = harness((path, method) => { if (method === "POST") mutations.push(path); return {}; });
  try {
    run.audio.state.queue = { ...queue(), segments: [{ segment_number: 1, script: "已经保存的正文" }] };
    assert.equal(await run.audio.reviseSegment(1, { text: " 已经保存的正文\n" }, "queue-one"), true);
    assert.deepEqual(mutations, []);
  } finally { run.close(); }
});

test("manual edits send the notebook baseline hash instead of adopting a newer polled script", async () => {
  const writes = [];
  const run = harness((path, method, body) => {
    if (path.endsWith("/revise")) { writes.push(body); return queue(); }
    return {};
  });
  try {
    run.audio.state.queue = { ...queue(), segments: [{ segment_number: 1, script: "后台的新稿", script_hash: "b".repeat(64) }] };
    assert.equal(await run.audio.reviseSegment(1, { text: "基于旧稿的修改" }, "queue-one", "a".repeat(64)), true);
    assert.equal(writes[0].expected_script_hash, "a".repeat(64));
    assert.equal(writes[0].text, "基于旧稿的修改");
  } finally { run.close(); }
});

test("review actions require the displayed owned proposal and stage rejection without changing live text", async () => {
  const calls = [];
  const proposal = { id: "proposal-one", queue_id: "queue-one", status: "PENDING", before_text: "原稿", after_text: "修改稿" };
  const original = { ...queue(), script_revisions: [proposal], segments: [{ segment_number: 1, script: "原稿" }] };
  const run = harness((path, method, body) => {
    if (method === "POST") {
      calls.push({ path, body });
      return { ...original, script_revisions: [{ ...proposal, status: "REJECTED" }] };
    }
    return {};
  });
  try {
    run.audio.state.queue = original;
    assert.equal(await run.audio.acceptScriptRevision("missing"), false);
    assert.equal(await run.audio.acceptScriptRevision(proposal.id, "another-queue"), false);
    assert.deepEqual(calls, []);
    assert.equal(await run.audio.rejectScriptRevision(proposal.id), true);
    assert.equal(run.audio.state.queue.segments[0].script, "原稿");
    assert.equal(run.audio.state.queue.script_revisions[0].status, "REJECTED");
    assert.match(calls[0].path, /script-revisions\/proposal-one\/reject$/);
    assert.deepEqual(calls[0].body, { confirmed: true });
    assert.equal(await run.audio.acceptScriptRevision(proposal.id), false);
    assert.equal(calls.length, 1);
  } finally { run.close(); }
});

test("revision acceptance preserves the current voice by default and forwards only explicit voice choices", async () => {
  const writes = [];
  const proposal = { id: "proposal-one", queue_id: "queue-one", status: "PENDING" };
  const original = { ...queue(), voice: "english-current", script_revisions: [proposal] };
  const run = harness((path, method, body) => {
    if (method === "POST") writes.push({ path, body });
    return { ...original, script_revisions: [{ ...proposal, status: "ACCEPTED" }] };
  });
  try {
    run.audio.state.queue = original;
    await flush();
    assert.deepEqual(writes, [], "opening the review must not update the queue voice");
    assert.equal(await run.audio.acceptScriptRevision(proposal.id, "queue-one"), true);
    assert.deepEqual(writes[0].body, { confirmed: true });
    assert.equal(Object.hasOwn(writes[0].body, "voice"), false);

    run.audio.state.queue = original;
    assert.equal(await run.audio.acceptScriptRevision(proposal.id, "queue-one", ""), true);
    assert.deepEqual(writes[1].body, { confirmed: true, voice: "" });

    run.audio.state.queue = original;
    assert.equal(await run.audio.acceptScriptRevision(proposal.id, "queue-one", "chinese-voice"), true);
    assert.deepEqual(writes[2].body, { confirmed: true, voice: "chinese-voice" });
    assert.ok(writes.every(write => write.path.endsWith("/script-revisions/proposal-one/accept")));
    assert.equal(writes.length, 3);
  } finally { run.close(); }
});

test("acceptance blocks duplicate clicks and stale polls, and old completion cannot overwrite another queue", async () => {
  const proposal = { id: "proposal-one", queue_id: "queue-one", status: "PENDING" };
  const original = { ...queue(), script_revisions: [proposal] };
  let acknowledge, finishPoll, writes = 0;
  const run = harness((path, method) => {
    if (path.endsWith("/accept")) { writes++; return new Promise(done => { acknowledge = done; }); }
    if (path.includes("/continuous-audio/") && method === "GET") return new Promise(done => { finishPoll = done; });
    return {};
  });
  try {
    run.audio.state.queue = original;
    const polling = run.audio.poll();
    const accepting = run.audio.acceptScriptRevision(proposal.id);
    assert.equal(await run.audio.acceptScriptRevision(proposal.id), false);
    const accepted = { ...original, script_revisions: [{ ...proposal, status: "ACCEPTED" }] };
    acknowledge(accepted);
    assert.equal(await accepting, true);
    finishPoll(original); await polling;
    assert.equal(run.audio.state.queue.script_revisions[0].status, "ACCEPTED");
    run.audio.state.queue = original;
    const late = run.audio.acceptScriptRevision(proposal.id);
    await run.audio.openQueue({ ...queue(), queue_id: "another-queue" }, queue().project, false);
    acknowledge(accepted);
    assert.equal(await late, false);
    assert.equal(run.audio.state.queue.queue_id, "another-queue");
    assert.equal(writes, 2);
  } finally { run.close(); }
});

test("natural edit responses show review without pausing or replacing the live audio", async () => {
  const original = queue();
  const proposal = { id: "proposal-one", queue_id: original.queue_id, status: "PENDING" };
  const run = harness((path) => path.endsWith("/requests")
    ? { ...original, action: "REVIEW", script_revisions: [proposal] } : {});
  try {
    run.audio.state.queue = original; run.audio.state.project = original.project;
    run.player.currentTime = 37;
    const pauses = run.pauses();
    const response = await run.audio.submit("把刚才的内容缩短一点");
    assert.equal(response.action, "REVIEW");
    assert.equal(run.pauses(), pauses);
    assert.equal(run.player.currentTime, 37);
    assert.equal(run.audio.current.value.script, "one");
    assert.equal(run.audio.state.queue.script_revisions[0].status, "PENDING");
  } finally { run.close(); }
});

test("legacy archive playback restores its own saved record without referencing a missing variable", async () => {
  const run = harness(async () => ({}));
  try {
    run.state.user = null;
    run.values.set("audiorag_listening:1", JSON.stringify({ record: { title: "旧音频", script: "归档正文", audio_url: media },
      project: { id: 7, title: "归档空间" }, time: 12, speed: 1.25 }));
    run.state.user = { id: 1 };
    await flush(); run.audio.loaded();
    assert.equal(run.audio.state.queue.mode, "ARCHIVE");
    assert.equal(run.audio.current.value.script, "归档正文");
    assert.equal(run.player.currentTime, 12);
  } finally { run.close(); }
});

test("RESUME and history restore server position without starting a new generation", async () => {
  const calls = [];
  const run = harness(async (path, method, body) => {
    calls.push({ path, method, body });
    if (path.endsWith("/history")) return { queues: [queue()] };
    if (path.endsWith("/progress") && method === "GET") return {
      segment_index: 1, position_seconds: 48, playback_rate: 1.25, completed: false, updated_at: "2026-10-20T12:00:00",
    };
    if (path.endsWith("/progress")) return body;
    return { ...queue(), action: "RESUME" };
  });
  try {
    await run.audio.submit("继续上次的故事"); run.audio.loaded();
    assert.equal(run.audio.state.index, 1);
    assert.equal(run.player.currentTime, 48);
    assert.equal(run.audio.state.speed, 1.25);
    const submitted = calls.filter(item => item.path.endsWith("/requests"));
    assert.equal(submitted.length, 1);
    assert.equal(calls.some(item => item.path.includes("/continuous-audio") && item.method === "POST"), false);
    await run.audio.openQueue(queue()); run.audio.loaded();
    assert.equal(calls.filter(item => item.path.endsWith("/requests")).length, 1, "opening history must not generate audio");
    assert.equal(run.player.currentTime, 48);
    run.player.currentTime = 52; run.audio.timeUpdate(); run.audio.playChanged(false); await flush(); await flush();
    const saved = calls.filter(item => item.method === "PUT");
    assert.equal(saved.at(-1).body.position_seconds, 52);
    assert.equal(saved.at(-1).body.segment_index, 1);
  } finally { run.close(); }
});

test("late server progress cannot restore a queue belonging to a logged out user", async () => {
  let resolve;
  const run = harness((path, method) => {
    if (path.endsWith("/progress") && method === "GET") return new Promise(done => { resolve = done; });
    return queue();
  });
  try {
    const opening = run.audio.openQueue(queue());
    run.state.user = null; await flush();
    resolve({ segment_index: 1, position_seconds: 88, playback_rate: 1.5, updated_at: "saved" });
    await opening;
    assert.equal(run.audio.state.queue, null);
    assert.equal(run.audio.state.time, 0);
  } finally { run.close(); }
});

test("contextual requests bind the loaded older history queue rather than the newest queue or visible space", async () => {
  const submitted = [];
  const older = { ...queue(), queue_id: "older-history-queue", project_id: 7 };
  const run = harness(async (path, method, body) => {
    if (path.endsWith("/history")) return { queues: [{ ...queue(), queue_id: "newest-queue" }, older] };
    if (path.endsWith("/progress") && method === "GET") return { segment_index: 0, position_seconds: 8, playback_rate: 1, updated_at: "saved" };
    if (path.endsWith("/requests")) { submitted.push(body); return queue(); }
    return method === "PUT" ? body : queue();
  });
  try {
    await run.audio.openQueue(older, older.project, false);
    await run.audio.submit("把刚才那段改成英语", { id: 99 });
    assert.equal(submitted[0].previous_queue_id, "older-history-queue");
    assert.equal(submitted[0].project_id, 7);
    await run.audio.openQueue(older, older.project, false);
    await run.audio.submit("继续听", null);
    assert.equal(submitted[1].previous_queue_id, "older-history-queue");
    assert.equal(submitted[1].project_id, 7);
  } finally { run.close(); }
});

for (const localNewer of [true, false]) {
  test(localNewer ? "newer offline local progress survives a stale server checkpoint and uploads after restore"
    : "a newer server checkpoint wins over older local progress without re-stamping on queue polling", async () => {
    const writes = [];
    const remoteAt = Date.parse("2026-10-20T10:00:00Z");
    const localAt = remoteAt + (localNewer ? 60_000 : -60_000);
    const run = harness(async (path, method, body) => {
      if (path.endsWith("/history")) return { queues: [queue()] };
      if (path.endsWith("/progress") && method === "GET") return {
        segment_index: 0, position_seconds: 12, playback_rate: 1, completed: false, updated_at: new Date(remoteAt).toISOString(),
      };
      if (method === "PUT") { writes.push(body); return body; }
      return queue();
    });
    try {
      run.state.user = null; await flush();
      run.values.set("audiorag_listening:1", JSON.stringify({ queueId: "queue-one", index: 1, time: 70,
        speed: 1.5, completed: true, savedAt: localAt, project: queue().project }));
      run.state.user = { id: 1 }; await flush(); await flush(); run.audio.loaded(); await flush();
      assert.equal(run.audio.state.index, localNewer ? 1 : 0);
      assert.equal(run.player.currentTime, localNewer ? 70 : 12);
      assert.equal(run.audio.state.completed, localNewer);
      const checkpoint = JSON.parse(run.values.get("audiorag_listening:1"));
      assert.equal(checkpoint.savedAt, localNewer ? localAt : remoteAt);
      const beforeWrites = writes.length;
      await run.audio.poll(); await flush();
      assert.equal(JSON.parse(run.values.get("audiorag_listening:1")).savedAt, checkpoint.savedAt);
      assert.equal(writes.length, beforeWrites, "status polling must not upload an unchanged playback position");
      if (localNewer) assert.equal(writes.at(-1).position_seconds, 70);
      else assert.equal(writes.length, 0, "reading a newer remote checkpoint must not rewrite it");
    } finally { run.close(); }
  });
}
