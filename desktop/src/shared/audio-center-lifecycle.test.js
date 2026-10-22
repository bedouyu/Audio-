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
    const before = run.pauses(); run.state.user = null; await flush();
    assert.ok(run.pauses() > before);
    assert.equal(run.audio.state.queue, null);
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
