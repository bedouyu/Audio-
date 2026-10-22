import { computed, nextTick, onBeforeUnmount, reactive, watch } from "vue";
import { canRefill, queueMessage, segmentConfirmation } from "../../../shared/audio-queue-policy.js";
import { listeningContext, listeningSubmission, playbackSize, queueLesson, readPlayback } from "../../../shared/audio-center-policy.js";

export function useAudioCenter(workspace) {
  const { state: workspaceState, api, mediaUrl, refreshProject } = workspace;
  const state = reactive({ queue: null, index: 0, project: null, instruction: "", time: 0, duration: 0,
    speed: 1, playing: false, submitting: false, refilling: false, restoring: false, error: "", voices: [], voice: "", voiceStatus: "",
    history: [], historyStatus: "", progressStatus: "", completed: false });
  const current = computed(() => queueLesson(state.queue, state.index));
  const source = computed(() => mediaUrl(current.value?.audio_url));
  const total = computed(() => playbackSize(state.queue));
  const message = computed(() => queueMessage(state.queue));
  let player = null;
  let timer = null;
  let owner = null;
  let identity = 0;
  let revision = 0;
  let pollingId = null;
  let restoredTime = 0;
  let wantsPlay = false;
  let pending = null;
  let lastSave = 0;
  let lastRefillAttempt = 0;
  let refillInterval = 15000;
  let lastProgressAt = 0;
  let lastProgressQueue = null;
  let progressChain = Promise.resolve();
  let checkpointSignature = "";
  let checkpointAt = 0;
  const storageKey = () => owner == null ? null : "audiorag_listening:" + owner;

  function progressSignature() {
    return JSON.stringify([state.queue?.queue_id || current.value?.audio_url || "", state.index,
      restoredTime || state.time || 0, state.speed, state.completed]);
  }
  function rememberCheckpoint(timestamp) {
    checkpointAt = Number.isFinite(timestamp) && timestamp >= 0 ? timestamp : 0;
    checkpointSignature = progressSignature();
  }

  function save(force = false, completed = false) {
    const key = storageKey();
    if (!key || !state.queue || state.restoring) return;
    if (completed) state.completed = true;
    const signature = progressSignature();
    const changed = signature !== checkpointSignature;
    if (changed) { checkpointAt = Date.now(); checkpointSignature = signature; }
    try {
      localStorage.setItem(key, JSON.stringify({ queueId: state.queue.queue_id || "",
        record: state.queue.mode === "ARCHIVE" ? current.value : null, index: state.index,
        time: restoredTime || state.time || 0, speed: state.speed,
        savedAt: checkpointAt, completed: state.completed,
        project: state.project, instruction: state.instruction }));
    } catch { /* Playback remains available when local storage is full. */ }
    if (changed || force) persistProgress(force, state.completed);
  }
  function persistProgress(force, completed) {
    const id = state.queue?.queue_id;
    if (!id || !current.value?.audio_url || state.restoring || workspaceState.user?.id !== owner) return;
    const now = Date.now();
    if (!force && id === lastProgressQueue && now - lastProgressAt < 5000) return;
    lastProgressAt = now; lastProgressQueue = id;
    const version = identity;
    const body = { segment_index: state.index, position_seconds: state.time || 0,
      playback_rate: state.speed, completed: !!completed };
    progressChain = progressChain.catch(() => {}).then(async () => {
      if (version !== identity) return;
      await api(`/api/v1/audio/queues/${encodeURIComponent(id)}/progress`, "PUT", body);
      if (version === identity && state.queue?.queue_id === id) state.progressStatus = completed ? "已听完" : "收听进度已保存";
    }).catch(() => {
      if (version === identity && state.queue?.queue_id === id) state.progressStatus = "进度已暂存在本机，稍后会继续同步。";
    });
  }
  async function restoreProgress(fallback = null) {
    const id = state.queue?.queue_id, version = revision;
    if (!id) return;
    state.restoring = true;
    let saved = fallback;
    let syncLocal = false;
    try {
      const result = await api(`/api/v1/audio/queues/${encodeURIComponent(id)}/progress`);
      if (version !== revision) return;
      const remoteAt = Date.parse(result.updated_at || "") || 0;
      if (!saved || remoteAt >= (saved.savedAt || 0) && remoteAt > 0) {
        saved = { index: result.segment_index, time: result.position_seconds, speed: result.playback_rate,
          completed: result.completed, savedAt: remoteAt };
        state.progressStatus = "";
      } else {
        syncLocal = true;
        state.progressStatus = "已恢复较新的本机收听位置。";
      }
    } catch {
      if (version !== revision) return;
      syncLocal = !!saved;
      state.progressStatus = saved ? "已恢复本机保存的收听位置。" : "服务端进度暂不可用，从头开始收听。";
    } finally { if (version === revision) state.restoring = false; }
    if (version !== revision || !saved) return;
    state.index = Number.isInteger(saved.index) && saved.index >= 0 && (!total.value || saved.index < total.value) ? saved.index : 0;
    state.time = Number.isFinite(saved.time) && saved.time >= 0 ? saved.time : 0;
    state.speed = Number.isFinite(saved.speed) && saved.speed >= 0.5 && saved.speed <= 2 ? saved.speed : 1;
    state.completed = saved.completed === true;
    restoredTime = state.time;
    await nextTick();
    if (player?.readyState >= 1) loaded();
    rememberCheckpoint(saved.savedAt);
    save(syncLocal);
  }
  function updateHistory(queue) {
    if (!queue?.queue_id) return;
    const others = state.history.filter(item => item.queue_id !== queue.queue_id);
    state.history = [queue, ...others].sort((a, b) => String(b.updated_at || "").localeCompare(String(a.updated_at || "")));
  }
  async function refreshHistory() {
    const version = identity;
    try {
      const result = await api("/api/v1/audio/history");
      if (version !== identity) return;
      state.history = Array.isArray(result.queues) ? result.queues : [];
      state.historyStatus = "";
    } catch {
      if (version === identity) state.historyStatus = "暂时无法读取音频历史，可以继续打开已有内容空间。";
    }
  }
  function clear() {
    revision++;
    clearInterval(timer);
    timer = null;
    wantsPlay = false;
    player?.pause();
    if (player) { player.removeAttribute("src"); player.load(); }
    state.queue = null; state.project = null; state.instruction = ""; state.index = 0;
    state.time = 0; state.duration = 0; state.playing = false; state.error = "";
    state.submitting = false; state.refilling = false; state.restoring = false; restoredTime = 0; pending = null;
    state.progressStatus = ""; state.completed = false; lastProgressAt = 0; lastProgressQueue = null; progressChain = Promise.resolve();
    checkpointSignature = ""; checkpointAt = 0;
    if ("mediaSession" in navigator) {
      navigator.mediaSession.metadata = null;
      for (const action of ["play", "pause", "nexttrack", "previoustrack"]) navigator.mediaSession.setActionHandler(action, null);
    }
  }
  function bindPlayer(element) { player = element; }
  function startPolling() {
    clearInterval(timer);
    timer = setInterval(() => poll(), 3000);
  }
  async function poll() {
    const id = state.queue?.queue_id;
    if (!id || pollingId === id) return;
    const version = revision;
    pollingId = id;
    try {
      const result = await api("/api/v1/continuous-audio/" + encodeURIComponent(id));
      if (version !== revision || state.queue?.queue_id !== id) return;
      const before = state.queue.lessons?.length || 0;
      state.queue = result;
      updateHistory(result);
      if (total.value && state.index >= total.value) state.index = total.value - 1;
      state.error = "";
      if (["COMPLETE", "FAILED", "CANCELLED", "LEGACY_READ_ONLY"].includes(result.status)) clearInterval(timer);
      if ((result.lessons?.length || 0) > before && workspaceState.project?.id === state.project?.id)
        refreshProject().catch(() => {});
      if (!state.restoring) save();
    } catch (error) {
      if (version === revision) state.error = "读取音频任务失败：" + error.message;
    } finally {
      if (pollingId === id) pollingId = null;
    }
  }
  async function loadVoices(version) {
    try {
      const result = await api("/api/v1/audio/voices");
      if (version !== identity) return;
      state.voices = Array.isArray(result.voices) ? result.voices.filter(item => item?.name && item.enabled !== false) : [];
      state.voiceStatus = state.voices.some(item => item.provider === "cosyvoice") ? "已连接本地 CosyVoice，自然音色支持中文、英语和双语。"
        : state.voices.length ? "生成时会按内容语言检查可用音色，也可以指定已安装的音色。"
        : "未发现可选音色，生成时会检查本机语音能力。";
    } catch {
      if (version === identity) state.voiceStatus = "无法读取音色列表，生成时会再次检查本机语音能力。";
    }
  }
  watch(() => workspaceState.user?.id ?? null, async userId => {
    save(); clear(); owner = userId; identity++;
    const version = revision;
    state.voices = []; state.voice = ""; state.voiceStatus = "正在读取可用音色…"; state.history = []; state.historyStatus = "";
    if (userId == null) return;
    loadVoices(identity);
    refreshHistory();
    let saved;
    try { saved = readPlayback(JSON.parse(localStorage.getItem(storageKey()) || "null")); } catch { saved = null; }
    if (!saved) return;
    state.index = saved.index; state.project = saved.project; state.instruction = saved.instruction;
    state.speed = saved.speed; state.time = saved.time; restoredTime = saved.time;
    state.completed = saved.completed;
    if (saved.queueId) {
      state.queue = { queue_id: saved.queueId, status: "PREPARING", lessons: [] };
      state.restoring = true; startPolling(); await poll();
      if (version === revision) await restoreProgress(saved);
    } else {
      state.queue = { mode: "ARCHIVE", status: "COMPLETE", total_segments: 1, lessons: [saved.record] };
      rememberCheckpoint(saved.savedAt);
    }
  }, { immediate: true });

  async function submit(instruction, project = null) {
    if (state.submitting) return null;
    const version = identity;
    const context = listeningContext(instruction, project, state.project, state.queue);
    pending = listeningSubmission(instruction, context.projectId, state.voice, pending, undefined, context.previousQueueId);
    state.submitting = true; state.error = "";
    try {
      const result = await api("/api/v1/audio/requests", "POST", pending.body);
      if (version !== identity) return null;
      if (!result.queue_id || !result.project?.id) throw new Error("任务响应缺少音频队列或内容空间，请更新后端后重试。");
      pending = null;
      if (result.action === "RESUME") { await openQueue(result, result.project, true); return version === identity ? result : null; }
      save(true); player?.pause(); revision++;
      state.queue = result; state.project = result.project; state.instruction = instruction.trim();
      state.index = 0; state.time = 0; state.duration = 0; state.refilling = false; state.completed = false; restoredTime = 0; wantsPlay = true;
      updateHistory(result); startPolling(); save(); await nextTick(); await attemptPlay();
      return result;
    } catch (error) {
      if (version === identity) state.error = error.message;
      throw error;
    } finally { if (version === identity) state.submitting = false; }
  }
  async function attemptPlay() {
    if (!wantsPlay || !player || !source.value) return;
    try { await player.play(); wantsPlay = false; }
    catch { wantsPlay = false; state.error = "音频已就绪，请点击播放开始收听。"; }
  }
  watch(source, async () => { state.duration = 0; await nextTick(); await attemptPlay(); });
  watch(current, item => {
    if (!item || !("mediaSession" in navigator)) return;
    navigator.mediaSession.metadata = new MediaMetadata({ title: item.title || state.project?.title || "Audio智能助手",
      artist: "Audio智能助手", album: state.project?.title || "我的音频" });
    navigator.mediaSession.setActionHandler("play", () => { wantsPlay = true; attemptPlay(); });
    navigator.mediaSession.setActionHandler("pause", () => player?.pause());
    navigator.mediaSession.setActionHandler("nexttrack", next);
    navigator.mediaSession.setActionHandler("previoustrack", previous);
  });
  function loaded() {
    if (!player) return;
    state.duration = Number.isFinite(player.duration) ? player.duration : 0;
    player.playbackRate = state.speed;
    if (restoredTime > 0) { player.currentTime = Math.min(restoredTime, Math.max(0, state.duration - 0.1)); restoredTime = 0; }
    state.time = player.currentTime || 0;
    attemptPlay();
  }
  function timeUpdate() {
    state.time = player?.currentTime || 0;
    if (Date.now() - lastSave > 1500) { save(); lastSave = Date.now(); }
    maybeRefill();
  }
  // 片尾 15 秒内只按节流节奏补段；失败后指数退避，避免每个 timeupdate 都发一次请求。
  function maybeRefill() {
    if (state.refilling || !state.queue?.queue_id) return;
    const remaining = (player?.duration || 0) - (player?.currentTime || 0);
    if (!Number.isFinite(remaining) || remaining >= 15) return;
    if (Date.now() - lastRefillAttempt < refillInterval) return;
    lastRefillAttempt = Date.now();
    refill();
  }
  function playChanged(playing) { state.playing = playing; if (!playing) save(true); }
  function setSpeed(value) {
    state.speed = Number(value);
    if (player) player.playbackRate = state.speed;
    save(true);
  }
  async function refill(manual = false) {
    if (state.refilling || !state.queue?.queue_id || !canRefill(state.queue, manual)) return false;
    const version = revision, id = state.queue.queue_id;
    state.refilling = true;
    try {
      const result = await api("/api/v1/continuous-audio/" + encodeURIComponent(id) + "/refill", "POST", {});
      if (version !== revision) return false;
      state.queue = result; state.error = ""; startPolling(); save();
      updateHistory(result);
      refillInterval = 15000;
      return true;
    } catch (error) {
      if (version === revision) state.error = error.message;
      refillInterval = Math.min(refillInterval * 2, 120000);
      return false;
    }
    finally { if (version === revision) state.refilling = false; }
  }
  async function select(index, play = true) {
    if (index < 0 || index >= total.value) return;
    save(true);
    player?.pause(); state.index = index; state.time = 0; state.completed = false; restoredTime = 0; wantsPlay = play;
    refillInterval = 15000; lastRefillAttempt = 0;
    save(true); await nextTick(); await attemptPlay();
  }
  async function next() {
    if (state.index + 1 >= total.value) { wantsPlay = false; save(true, state.queue?.status === "COMPLETE"); return; }
    await select(state.index + 1);
    await refill(true); await poll();
  }
  function previous() { return select(state.index - 1); }
  function playRecord(record, project) {
    save(true); player?.pause(); clearInterval(timer); revision++;
    state.queue = { mode: "ARCHIVE", status: "COMPLETE", total_segments: 1,
      lessons: [{ title: record.title, script: record.content, audio_url: record.metadata?.audio_url, citations: record.metadata?.citations }] };
    state.project = project; state.instruction = ""; state.index = 0; state.time = 0;
    state.refilling = false; state.completed = false; restoredTime = 0; wantsPlay = true; state.error = ""; save(); nextTick(attemptPlay);
  }
  async function reviewSegment(segment, reason = null) {
    const reset = reason != null;
    if (!state.queue?.queue_id) return;
    const id = state.queue.queue_id, version = revision;
    const result = await api(`/api/v1/continuous-audio/${encodeURIComponent(id)}/segments/${segment.segment_number}/${reset ? "reset" : "recover"}`,
      "POST", segmentConfirmation(segment, reason || "", reset));
    if (version !== revision) return;
    state.queue = result; updateHistory(result); startPolling(); save();
  }
  async function openQueue(queue, project = null, play = true) {
    if (!queue?.queue_id) return;
    save(true); player?.pause(); revision++;
    const version = revision;
    state.queue = queue;
    state.project = project || queue.project || workspaceState.projects?.find(item => item.id === queue.project_id)
      || { id: queue.project_id, title: queue.title || "音频内容", topic: queue.instruction || "" };
    state.instruction = queue.instruction || ""; state.index = 0; state.time = 0;
    state.error = ""; state.refilling = false; state.completed = false; restoredTime = 0; wantsPlay = false;
    refillInterval = 15000; lastRefillAttempt = 0;
    state.restoring = true; updateHistory(queue);
    let fallback = null;
    try {
      const saved = readPlayback(JSON.parse(localStorage.getItem(storageKey()) || "null"));
      if (saved?.queueId === queue.queue_id) fallback = saved;
    } catch { /* Server progress remains available. */ }
    await restoreProgress(fallback);
    if (version !== revision) return;
    startPolling(); wantsPlay = play; await nextTick(); await attemptPlay(); save();
  }
  function forgetProject(projectId) {
    state.history = state.history.filter(item => item.project_id !== projectId);
    if (state.project?.id !== projectId) return;
    clear();
    const key = storageKey(); if (key) localStorage.removeItem(key);
  }
  function resumeLegacyProject(project) {
    if (state.queue || owner == null) return;
    try {
      const saved = readPlayback(JSON.parse(localStorage.getItem("audiorag_audio:" + owner + ":" + project.id) || "null"));
      if (!saved?.queueId) return;
      state.queue = { queue_id: saved.queueId, lessons: [], status: "PREPARING" };
      state.project = project; state.index = saved.index; restoredTime = saved.time; state.time = saved.time;
      const version = revision;
      state.restoring = true; startPolling(); poll().then(() => { if (version === revision) restoreProgress(saved); });
    } catch { /* Old project snapshots are optional. */ }
  }
  /** 录入零样本音色：服务端只对样例做一次特征提取并缓存，不训练模型。 */
  async function importVoice(payload) {
    const voice = await api("/api/v1/audio/voices", "POST", payload);
    await loadVoices(identity);
    return voice;
  }
  async function removeVoice(name) {
    await api("/api/v1/audio/voices/" + encodeURIComponent(name), "DELETE");
    await loadVoices(identity);
  }
  /** 完整讲稿：后端把各段正文按顺序合并，供查看与导出成一个文件。 */
  async function loadScript(queueId) {
    const id = queueId || state.queue?.queue_id;
    if (!id) return null;
    return api("/api/v1/continuous-audio/" + encodeURIComponent(id) + "/script");
  }
  /** 导出为单个 Markdown 文件；文件名取标题，附上来源与分段信息。 */
  async function exportScript(queueId) {
    const info = await loadScript(queueId);
    if (!info?.script) return null;
    const header = ["# " + (info.title || "音频讲稿"), "",
      "> 分段：" + info.segments + " · 字数：" + info.characters + " · 任务：" + info.queue_id, ""].join("\n");
    const filename = (info.title || "音频讲稿").replace(/[\\/:*?"<>|]/g, "_").slice(0, 60) + ".md";
    const saved = await window.audioAgent.saveScript({ filename, content: header + info.script + "\n" });
    return saved ? info : null;
  }
  onBeforeUnmount(() => {
    save(true); clear();
    if ("mediaSession" in navigator) {
      navigator.mediaSession.metadata = null;
      for (const action of ["play", "pause", "nexttrack", "previoustrack"]) navigator.mediaSession.setActionHandler(action, null);
    }
  });
  return { state, current, source, total, message, bindPlayer, submit, poll, refill, next, previous,
    select, loaded, timeUpdate, playChanged, setSpeed, playRecord, reviewSegment, forgetProject, resumeLegacyProject,
    openQueue, refreshHistory, save, clear, loadScript, exportScript, importVoice, removeVoice };
}
