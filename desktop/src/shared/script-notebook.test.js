import test from "node:test";
import assert from "node:assert/strict";
import { createRenderer, h, nextTick, reactive } from "vue";
import { useScriptNotebook } from "../renderer/src/composables/useScriptNotebook.js";

const segments = () => [{ segment_number: 1, script: "原稿第一段", status: "READY" },
  { segment_number: 2, script: "原稿第二段", status: "PENDING" }];
function harness(save = async () => true, drafts = {}) {
  const props = reactive({ segments: segments(), queueId: "queue-one", ownerId: 1, busy: false, drafts, save });
  let notebook;
  const renderer = createRenderer({ insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {} });
  const app = renderer.createApp({ setup() { notebook = useScriptNotebook(props); return () => h("div"); } });
  app.mount({});
  return { props, notebook, close: () => app.unmount() };
}

test("polling preserves local notebook input and identifies changed baselines", async () => {
  const run = harness();
  try {
    run.notebook.draft.value = "手工未提交的修改";
    run.props.segments = segments().map(segment => ({ ...segment, status: "SYNTHESIZING" }));
    await nextTick();
    assert.equal(run.notebook.draft.value, "手工未提交的修改");
    assert.equal(run.notebook.dirty.value, true);
    assert.equal(run.notebook.conflicted.value, false);
    run.props.segments = [{ ...segments()[0], script: "对话更新后的服务端正文" }, segments()[1]];
    await nextTick();
    assert.equal(run.notebook.draft.value, "手工未提交的修改");
    assert.equal(run.notebook.saved.value, "原稿第一段");
    assert.equal(run.notebook.conflicted.value, true);
    run.notebook.discard();
    assert.equal(run.notebook.draft.value, "对话更新后的服务端正文");
    assert.equal(run.notebook.dirty.value, false);
  } finally { run.close(); }
});

test("page navigation and unmount never apply edits and retain the local draft", async () => {
  let calls = 0;
  const drafts = {};
  const run = harness(async () => { calls++; return true; }, drafts);
  run.notebook.draft.value = "离开也保留的修改";
  run.notebook.turn(1);
  assert.equal(run.notebook.page.value, 1);
  assert.equal(run.notebook.draft.value, "原稿第二段");
  run.notebook.draft.value = "第二页修改";
  run.notebook.turn(-1);
  assert.equal(run.notebook.draft.value, "离开也保留的修改");
  assert.equal(run.notebook.dirty.value, true);
  run.close();
  await Promise.resolve();
  assert.equal(calls, 0);
  const restored = harness(undefined, drafts);
  try {
    assert.equal(restored.notebook.draft.value, "离开也保留的修改");
    restored.notebook.turn(1);
    assert.equal(restored.notebook.draft.value, "第二页修改");
  } finally { restored.close(); }
});

test("explicit submission shares one request and stages without changing the applied script", async () => {
  const calls = [];
  let resolve;
  const run = harness(payload => { calls.push(payload); return new Promise(done => { resolve = done; }); });
  try {
    run.notebook.draft.value = " 修改后第一段 ";
    const first = run.notebook.flush(), second = run.notebook.flush();
    await Promise.resolve();
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0], { number: 1, text: "修改后第一段", queueId: "queue-one" });
    assert.equal(run.notebook.dirty.value, true, "in-flight edits must remain visible");
    resolve(true);
    assert.equal(await first, true);
    assert.equal(await second, true);
    assert.equal(run.notebook.draft.value, "原稿第一段", "only confirmation may change the effective script");
    assert.equal(run.notebook.dirty.value, false);
    assert.deepEqual(run.props.drafts, {});
    run.props.segments = [{ ...segments()[0], script: "修改后第一段" }, segments()[1]];
    await nextTick();
    assert.equal(run.notebook.draft.value, "修改后第一段", "accepted server text is reflected by polling");
  } finally { run.close(); }
});

test("failed submission retains input and permits navigation without writing again", async () => {
  let calls = 0;
  const run = harness(async () => { calls++; return false; });
  try {
    run.notebook.draft.value = "不能丢失的修改";
    assert.equal(await run.notebook.flush(), false);
    assert.equal(run.notebook.draft.value, "不能丢失的修改");
    assert.equal(run.notebook.dirty.value, true);
    assert.match(run.notebook.error.value, /提交未完成/);
    run.notebook.turn(1);
    run.notebook.turn(-1);
    assert.equal(run.notebook.draft.value, "不能丢失的修改");
    assert.equal(calls, 1);
  } finally { run.close(); }
});

test("a late proposal acknowledgement cannot change another queue or user notebook", async () => {
  let resolve;
  const run = harness(() => new Promise(done => { resolve = done; }));
  try {
    run.notebook.draft.value = "旧队列的修改";
    const pending = run.notebook.flush();
    await Promise.resolve();
    run.props.queueId = "queue-two";
    run.props.ownerId = 2;
    run.props.segments = [{ segment_number: 1, script: "新用户的正文" }];
    await nextTick();
    resolve(true);
    assert.equal(await pending, false);
    assert.equal(run.notebook.draft.value, "新用户的正文");
    assert.equal(run.notebook.dirty.value, false);
    assert.equal(run.notebook.saving.value, false);
  } finally { run.close(); }
});

test("unchanged and whitespace-only edits never submit regeneration proposals", async () => {
  let calls = 0;
  const run = harness(async () => { calls++; return true; });
  try {
    await run.notebook.flush();
    run.notebook.draft.value = " 原稿第一段 \n";
    await run.notebook.flush();
    assert.equal(calls, 0);
    run.notebook.turn(1);
    assert.equal(run.notebook.page.value, 1);
  } finally { run.close(); }
});

test("queue navigation preserves input under the original owner, queue and segment", async () => {
  const run = harness();
  try {
    run.notebook.draft.value = "离开后也不能丢失的文字";
    run.props.queueId = "queue-two";
    run.props.segments = [{ segment_number: 1, script: "另一个队列" }];
    await nextTick();
    assert.equal(run.notebook.draft.value, "另一个队列");
    run.props.queueId = "queue-one";
    run.props.segments = segments();
    await nextTick();
    assert.equal(run.notebook.draft.value, "离开后也不能丢失的文字");
    assert.equal(run.notebook.dirty.value, true);
    run.props.ownerId = 2;
    await nextTick();
    assert.equal(run.notebook.draft.value, "原稿第一段");
  } finally { run.close(); }
});

test("dirty edits keep the original script hash through remote changes and draft restoration", async () => {
  const calls = [];
  const run = harness(async payload => { calls.push(payload); return false; });
  try {
    run.props.segments = [{ ...segments()[0], script_hash: "a".repeat(64) }];
    run.notebook.draft.value = "基于原稿的手工修改";
    run.props.segments = [{ ...segments()[0], script: "后台已更新的正文", script_hash: "b".repeat(64) }];
    await run.notebook.flush();
    assert.equal(calls[0].expectedScriptHash, "a".repeat(64));
    run.props.queueId = "queue-two";
    run.props.segments = [{ segment_number: 1, script: "其他队列", script_hash: "c".repeat(64) }];
    run.props.queueId = "queue-one";
    run.props.segments = [{ ...segments()[0], script: "后台已更新的正文", script_hash: "b".repeat(64) }];
    await run.notebook.flush();
    assert.equal(calls[1].expectedScriptHash, "a".repeat(64));
  } finally { run.close(); }
});

test("a staged proposal does not adopt its proposed text or hash as an applied baseline", async () => {
  const calls = [];
  const run = harness(async payload => { calls.push(payload); return true; });
  try {
    run.props.segments = [{ ...segments()[0], script_hash: "a".repeat(64) }];
    run.notebook.draft.value = "第一次待审修改";
    assert.equal(await run.notebook.flush(), true);
    assert.equal(run.notebook.saved.value, "原稿第一段");
    run.notebook.draft.value = "另一份待审修改";
    assert.equal(await run.notebook.flush(), true);
    assert.equal(calls[0].expectedScriptHash, "a".repeat(64));
    assert.equal(calls[1].expectedScriptHash, "a".repeat(64));
  } finally { run.close(); }
});

test("discarding local changes restores the latest applied text without a request", async () => {
  let calls = 0;
  const run = harness(async () => { calls++; return true; });
  try {
    run.notebook.draft.value = "要放弃的修改";
    run.props.segments = [{ ...segments()[0], script: "远端新稿", script_hash: "b".repeat(64) }];
    run.notebook.discard();
    assert.equal(run.notebook.draft.value, "远端新稿");
    assert.equal(run.notebook.dirty.value, false);
    assert.deepEqual(run.props.drafts, {});
    assert.equal(calls, 0);
  } finally { run.close(); }
});

test("failed local submissions surface the server reason while retaining the full input", async () => {
  const run = harness(async () => false);
  try {
    run.notebook.draft.value = "不能丢失的完整修改";
    await run.notebook.flush();
    run.props.requestError = "讲稿正在更新，请完成当前任务后重试";
    assert.equal(run.notebook.error.value, run.props.requestError);
    assert.equal(run.notebook.draft.value, "不能丢失的完整修改");
    run.notebook.draft.value = "";
    await run.notebook.flush();
    assert.match(run.notebook.error.value, /讲稿不能为空/);
    assert.doesNotMatch(run.notebook.error.value, /正在更新/);
  } finally { run.close(); }
});
