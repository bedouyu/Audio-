import { audioSubmission } from "./audio-queue-policy.js";

export function listeningSubmission(instruction, projectId, voice, previous, uuid, previousQueueId = null) {
  const text = typeof instruction === "string" ? instruction.trim() : "";
  if (!text) throw new Error("先告诉我你想听什么。");
  if (text.length > 4000) throw new Error("收听要求请控制在 4000 字以内。");
  const body = { instruction: text };
  if (projectId != null) body.project_id = projectId;
  if (typeof previousQueueId === "string" && previousQueueId) body.previous_queue_id = previousQueueId;
  if (typeof voice === "string" && voice.trim()) body.voice = voice.trim();
  return audioSubmission(body, previous, uuid);
}

export function listeningContext(instruction, project, playingProject, queue) {
  const contextual = /(刚才|这段|这个音频|上一段|继续|接着|换成|改成|缩短|短一点|续写|下一章|下一段|上次|新进展|新消息|\b(?:previous|continue|shorter|rewrite)\b|\bnext\s+(?:chapter|part)\b)/i.test(instruction || "");
  if (contextual && playingProject?.id != null && queue?.queue_id)
    return { projectId: playingProject.id, previousQueueId: queue.queue_id };
  return { projectId: project?.id, previousQueueId: null };
}

export function playbackSize(queue) {
  const declared = Number(queue?.total_segments);
  return Math.max(Number.isInteger(declared) && declared > 0 ? declared : 0,
    Array.isArray(queue?.lessons) ? queue.lessons.length : 0);
}

export function queueLesson(queue, index) {
  const lessons = Array.isArray(queue?.lessons) ? queue.lessons : [];
  return lessons.some(item => Number.isInteger(item?.segment_number))
    ? lessons.find(item => item.segment_number === index + 1) || null
    : lessons[index] || null;
}

export function readPlayback(value) {
  if (!value || typeof value !== "object") return null;
  const queueId = typeof value.queueId === "string" && value.queueId.length <= 128 ? value.queueId : "";
  const record = value.record && /^\/media\/[0-9a-f-]{36}\.wav$/i.test(value.record.audio_url || "")
    ? { title: String(value.record.title || "已有音频"), script: String(value.record.script || ""), audio_url: value.record.audio_url } : null;
  if (!queueId && !record) return null;
  return {
    queueId, record,
    index: Number.isInteger(value.index) && value.index >= 0 ? value.index : 0,
    time: Number.isFinite(value.time) && value.time >= 0 ? value.time : 0,
    speed: Number.isFinite(value.speed) && value.speed >= 0.5 && value.speed <= 2 ? value.speed : 1,
    savedAt: Number.isFinite(value.savedAt) && value.savedAt >= 0 ? value.savedAt : 0,
    completed: value.completed === true,
    project: value.project && Number.isInteger(value.project.id)
      ? { id: value.project.id, title: String(value.project.title || ""), topic: String(value.project.topic || "") } : null,
    instruction: typeof value.instruction === "string" ? value.instruction.slice(0, 4000) : "",
  };
}
