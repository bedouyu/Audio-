import test from "node:test";
import assert from "node:assert/strict";
import { listeningContext, listeningSubmission, playbackSize, queueLesson, readPlayback } from "./audio-center-policy.js";

test("a listening instruction needs no project, category, duration, audience or fixed voice", () => {
  assert.deepEqual(listeningSubmission("  给我讲一段英文故事  ", null, "", null, () => "one").body,
    { instruction: "给我讲一段英文故事", request_id: "one" });
  assert.throws(() => listeningSubmission("   "));
  assert.throws(() => listeningSubmission("a".repeat(4001)));
});

test("retry keeps the submitted intent key, but a new space or voice changes the request", () => {
  const first = listeningSubmission("接着读", 3, "", null, () => "first");
  assert.equal(listeningSubmission("接着读", 3, "", first, () => "unused"), first);
  assert.equal(listeningSubmission("接着读", 4, "", first, () => "new-space").body.request_id, "new-space");
  assert.equal(listeningSubmission("接着读", 3, "English voice", first, () => "new-voice").body.voice, "English voice");
});

test("context points to the actual player queue and its space, including from the homepage", () => {
  const player = { id: 7 };
  assert.deepEqual(listeningContext("把刚才那段改成英语", null, player, { queue_id: "older" }), { projectId: 7, previousQueueId: "older" });
  assert.deepEqual(listeningContext("继续听", { id: 99 }, player, { queue_id: "older" }), { projectId: 7, previousQueueId: "older" });
  assert.deepEqual(listeningContext("有新进展吗", null, player, { queue_id: "older" }), { projectId: 7, previousQueueId: "older" });
  assert.deepEqual(listeningContext("还有什么新消息", null, player, { queue_id: "older" }), { projectId: 7, previousQueueId: "older" });
  assert.deepEqual(listeningContext("讲一段关于天文的故事", { id: 99 }, player, { queue_id: "older" }), { projectId: 99, previousQueueId: null });
  const first = listeningSubmission("短一点", 7, "", null, () => "one", "older");
  const second = listeningSubmission("短一点", 7, "", first, () => "two", "newer");
  assert.equal(second.body.request_id, "two");
  assert.equal(second.body.previous_queue_id, "newer");
});

test("playback restoration rejects unrelated media and sanitizes corrupted progress", () => {
  assert.equal(readPlayback({ record: { audio_url: "https://example.com/file.wav" } }), null);
  const restored = readPlayback({ queueId: "queue", index: -1, time: Infinity, speed: 10, project: { id: 5, title: "故事" } });
  assert.equal(restored.index, 0);
  assert.equal(restored.time, 0);
  assert.equal(restored.speed, 1);
  assert.equal(restored.savedAt, 0);
  assert.equal(restored.completed, false);
  assert.equal(restored.project.id, 5);
  assert.equal(playbackSize({ total_segments: 6, lessons: [1] }), 6);
  assert.equal(playbackSize({ lessons: [1, 2] }), 2);
});

test("ready audio stays bound to its real segment when earlier segments are unavailable", () => {
  const queue = { lessons: [{ segment_number: 2, title: "second" }], total_segments: 3 };
  assert.equal(queueLesson(queue, 0), null);
  assert.equal(queueLesson(queue, 1).title, "second");
  assert.equal(queueLesson({ lessons: [{ title: "legacy" }] }, 0).title, "legacy");
});
