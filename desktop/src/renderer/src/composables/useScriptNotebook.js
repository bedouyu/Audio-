import { computed, onBeforeUnmount, ref, watch } from "vue";

/** 本地改稿只在明确提交时创建待审版本；轮询、换页和离开不会应用修改。 */
export function useScriptNotebook(props) {
  const page = ref(0);
  const draft = ref("");
  const saved = ref("");
  const savedHash = ref("");
  const saving = ref(false);
  const error = ref("");
  const requestFailed = ref(false);
  const displayError = computed(() => requestFailed.value && props.requestError ? props.requestError : error.value);
  const list = computed(() => props.segments || []);
  const current = computed(() => list.value[page.value] || null);
  const dirty = computed(() => draft.value.trim() !== saved.value.trim());
  const conflicted = computed(() => dirty.value && (
    (savedHash.value && current.value?.script_hash && savedHash.value !== current.value.script_hash)
    || (current.value?.script || "").trim() !== saved.value.trim()
  ));
  let context = 0;
  let inFlight = null;
  let loadedKey = null;
  let loading = false;
  const draftKey = () => props.queueId && props.ownerId != null && current.value
    ? props.ownerId + ":" + props.queueId + ":" + current.value.segment_number : null;

  function rememberDraft() {
    if (!loadedKey || !props.drafts || loading) return;
    if (dirty.value) props.drafts[loadedKey] = { text: draft.value, saved: saved.value, savedHash: savedHash.value };
    else delete props.drafts[loadedKey];
  }

  function selectPage() {
    rememberDraft();
    context++;
    inFlight = null;
    saving.value = false;
    loadedKey = draftKey();
    const text = current.value?.script || "";
    const cached = loadedKey && props.drafts?.[loadedKey];
    const restore = cached && cached.text.trim() !== text.trim();
    loading = true;
    saved.value = restore ? cached.saved : text;
    savedHash.value = restore ? cached.savedHash || "" : current.value?.script_hash || "";
    draft.value = restore ? cached.text : text;
    error.value = "";
    requestFailed.value = false;
    loading = false;
    rememberDraft();
  }

  watch(() => [props.queueId, props.ownerId], () => { page.value = 0; }, { flush: "sync" });
  watch(() => [props.queueId, props.ownerId, current.value?.segment_number], selectPage,
    { immediate: true, flush: "sync" });
  watch(list, values => { if (page.value >= values.length) page.value = 0; }, { flush: "sync" });
  watch(() => [current.value?.script, current.value?.script_hash], ([text, hash]) => {
    if (!dirty.value && !saving.value) {
      loading = true;
      saved.value = text || "";
      savedHash.value = hash || "";
      draft.value = saved.value;
      loading = false;
      rememberDraft();
    }
  }, { flush: "sync" });
  watch(draft, rememberDraft, { flush: "sync" });

  // 保留方法名供调用方使用；它现在只提交待审修改，不保存正文或合成音频。
  function flush() {
    if (inFlight) return inFlight;
    if (!current.value || !dirty.value) return Promise.resolve(true);
    const text = draft.value.trim();
    if (!text) { requestFailed.value = false; error.value = "讲稿不能为空，输入仍保留在稿纸中。"; return Promise.resolve(false); }
    if (props.busy) return Promise.resolve(false);
    const version = context;
    const key = loadedKey, drafts = props.drafts;
    const payload = { number: current.value.segment_number, text, queueId: props.queueId };
    if (savedHash.value) payload.expectedScriptHash = savedHash.value;
    saving.value = true;
    error.value = "";
    requestFailed.value = false;
    const request = Promise.resolve().then(() => props.save(payload)).then(success => {
      if (success !== true) {
        if (version === context) {
          requestFailed.value = true;
          error.value = "提交未完成，修改仍保留，请稍后重试。";
        }
        return false;
      }
      if (key && drafts?.[key]?.text.trim() === text) delete drafts[key];
      if (version !== context) return false;
      // 待审版本已由服务端保存；稿纸仍显示当前生效正文，确认后才会更新。
      if (draft.value.trim() === text) {
        loading = true;
        saved.value = current.value?.script || "";
        savedHash.value = current.value?.script_hash || "";
        draft.value = saved.value;
        loading = false;
      }
      rememberDraft();
      return true;
    }).catch(() => {
      if (version === context) {
        requestFailed.value = true;
        error.value = "提交失败，修改仍保留，请稍后重试。";
      }
      return false;
    }).finally(() => {
      if (inFlight === request) { inFlight = null; saving.value = false; }
    });
    inFlight = request;
    return request;
  }

  function discard() {
    if (saving.value) return;
    loading = true;
    saved.value = current.value?.script || "";
    savedHash.value = current.value?.script_hash || "";
    draft.value = saved.value;
    error.value = "";
    requestFailed.value = false;
    loading = false;
    rememberDraft();
  }

  function turn(delta) {
    const next = page.value + delta;
    if (next < 0 || next >= list.value.length) return;
    rememberDraft();
    page.value = next;
  }

  onBeforeUnmount(rememberDraft);
  return { page, draft, saved, current, list, dirty, conflicted, saving, error: displayError, flush, discard, turn };
}
