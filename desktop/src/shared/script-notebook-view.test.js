import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as Vue from "vue";
import { parse, compileScript } from "vue/compiler-sfc";
import { useScriptNotebook } from "../renderer/src/composables/useScriptNotebook.js";
import { scriptDiff } from "./script-diff.js";

function component(name, dependencies) {
  const source = readFileSync(new URL("../renderer/src/components/" + name + ".vue", import.meta.url), "utf8");
  const { descriptor } = parse(source);
  const compiled = compileScript(descriptor, { id: name, inlineTemplate: true }).content
    .replace(/import\s*\{([^}]+)\}\s*from\s*["']([^"']+)["'];?/g, (_, symbols, module) =>
      `const {${symbols.replace(/\s+as\s+/g, ":")}} = dependencies[${JSON.stringify(module)}];`)
    .replace(/import\s+(\w+)\s+from\s*["']([^"']+)["'];?/g, (_, symbol, module) =>
      `const ${symbol} = dependencies[${JSON.stringify(module)}].default;`)
    .replace("export default", "return");
  return new Function("dependencies", compiled)({ vue: Vue, ...dependencies });
}
const ScriptDiff = component("ScriptDiff", { "../../../shared/script-diff.js": { scriptDiff } });
const ScriptNotebook = component("ScriptNotebook", {
  "../composables/useScriptNotebook.js": { useScriptNotebook }, "./ScriptDiff.vue": { default: ScriptDiff }
});
const flush = async () => { await Vue.nextTick(); await Promise.resolve(); await Vue.nextTick(); };

function harness(extra = {}) {
  const previousDocument = globalThis.document, previousDocumentClass = globalThis.Document, previousShadowRoot = globalThis.ShadowRoot;
  globalThis.Document = class {};
  globalThis.ShadowRoot = class {};
  globalThis.document = Object.assign(new globalThis.Document(), { activeElement: null });
  const element = (type, text = "") => ({ type, text, children: [], props: {}, parent: null,
    get options() { return this.children.filter(node => node.type === "option"); },
    addEventListener() {}, removeEventListener() {}, getAttribute() { return null; }, getRootNode() { return globalThis.document; } });
  const remove = node => { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1); };
  const renderer = Vue.createRenderer({
    insert(node, parent, anchor) { remove(node); node.parent = parent; const index = parent.children.indexOf(anchor); parent.children.splice(index < 0 ? parent.children.length : index, 0, node); },
    remove, createElement: element, createText: text => element("#text", text), createComment: () => element("#comment"),
    setText(node, text) { node.text = text; }, setElementText(node, text) { node.text = text; node.children = []; },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1],
    patchProp(node, name, previous, next) {
      node.props[name] = next;
      if (name === "value" || name === "multiple") node[name] = next;
    }
  });
  const props = Vue.reactive({ queueId: "queue-one", ownerId: 1, drafts: {}, segments: [{ segment_number: 1, script: "当前稿" }],
    revisions: [{ id: "change-one", status: "PENDING", before_text: "当前稿", after_text: "新稿", segment_number: 1 }], ...extra });
  const root = element("root");
  const app = renderer.createApp({ render: () => Vue.h(ScriptNotebook, props) }); app.mount(root);
  const text = node => node.text + node.children.map(text).join("");
  const find = (predicate, node = root) => predicate(node) ? node : node.children.map(child => find(predicate, child)).find(Boolean);
  return { props, root, text: () => text(root), find, button: label => find(node => node.type === "button" && text(node) === label),
    close() { app.unmount(); globalThis.document = previousDocument; globalThis.Document = previousDocumentClass; globalThis.ShadowRoot = previousShadowRoot; } };
}

test("pending revisions render safe additions and deletions and require an explicit confirmation click", async () => {
  const calls = []; let acknowledge;
  const run = harness({ acceptRevision: id => { calls.push(id); return new Promise(resolve => { acknowledge = resolve; }); } });
  try {
    assert.deepEqual(calls, []);
    assert.ok(run.find(node => node.type === "ins"));
    assert.ok(run.find(node => node.type === "del"));
    const request = run.button("确认修改").props.onClick();
    await flush();
    assert.deepEqual(calls, ["change-one"]);
    assert.equal(run.button("保留原稿").props.disabled, true);
    acknowledge(true); await request; await flush();
    assert.match(run.text(), /已确认修改，正在更新音频/);
  } finally { run.close(); }
});

test("review voice choices remain local until confirmation and preserving the current voice omits the argument", async () => {
  const calls = [];
  const run = harness({ currentVoice: "english-current", voices: [
    { name: "chinese-voice", display_name: "中文音色" }, { name: "bilingual-voice", display_name: "双语音色" }
  ], acceptRevision: async (...args) => { calls.push(args); return true; } });
  try {
    const select = run.find(node => node.type === "select");
    assert.ok(select);
    assert.match(run.text(), /沿用当前音色 · english-current/);
    assert.deepEqual(calls, []);
    await run.button("确认修改").props.onClick(); await flush();
    assert.deepEqual(calls, [["change-one"]]);

    select.props["onUpdate:modelValue"](""); await flush();
    assert.equal(calls.length, 1, "choosing automatic voice must not accept or regenerate");
    assert.equal(run.find(node => node.type === "textarea").value, "当前稿");
    await run.button("确认修改").props.onClick(); await flush();
    assert.deepEqual(calls[1], ["change-one", ""]);

    select.props["onUpdate:modelValue"]("chinese-voice"); await flush();
    assert.equal(calls.length, 2, "choosing an installed voice must not accept or regenerate");
    await run.button("确认修改").props.onClick(); await flush();
    assert.deepEqual(calls[2], ["change-one", "chinese-voice"]);
  } finally { run.close(); }
});

test("voice changes apply only to acceptance and reset when the displayed queue changes", async () => {
  const accepted = [], rejected = [], reverted = [];
  const run = harness({ voices: [{ name: "chinese-voice" }],
    acceptRevision: async (...args) => { accepted.push(args); return true; },
    rejectRevision: async (...args) => { rejected.push(args); return true; },
    revertRevision: async (...args) => { reverted.push(args); return true; } });
  try {
    run.find(node => node.type === "select").props["onUpdate:modelValue"]("chinese-voice"); await flush();
    assert.deepEqual(accepted, []);
    await run.button("保留原稿").props.onClick(); await flush();
    assert.deepEqual(rejected, [["change-one"]]);
    run.props.revisions = [{ ...run.props.revisions[0], status: "ACCEPTED" }]; await flush();
    await run.button("撤回此修改").props.onClick(); await flush();
    assert.deepEqual(reverted, [["change-one"]]);
    assert.deepEqual(accepted, []);

    run.props.queueId = "queue-two";
    run.props.revisions = [{ id: "change-two", status: "PENDING", before_text: "另一稿", after_text: "另一新稿" }];
    await flush();
    await run.button("确认修改").props.onClick(); await flush();
    assert.deepEqual(accepted, [["change-two"]], "another queue must default to keeping its own current voice");
  } finally { run.close(); }
});

test("rejection keeps the original and accepted revisions can stage a reverse change", async () => {
  const rejected = [], reverted = [];
  const run = harness({ rejectRevision: async id => { rejected.push(id); return true; },
    revertRevision: async id => { reverted.push(id); return true; } });
  try {
    await run.button("保留原稿").props.onClick(); await flush();
    assert.deepEqual(rejected, ["change-one"]);
    assert.match(run.text(), /已保留原稿/);
    assert.equal(run.find(node => node.type === "textarea").value, "当前稿");
    run.props.revisions = [{ ...run.props.revisions[0], status: "ACCEPTED" }]; await flush();
    await run.button("撤回此修改").props.onClick(); await flush();
    assert.deepEqual(reverted, ["change-one"]);
    assert.match(run.text(), /已创建撤回版本，请在待确认修改中审阅/);
  } finally { run.close(); }
});

test("a failed review action is visible and a late result cannot annotate another queue", async () => {
  let acknowledge;
  const run = harness({ acceptRevision: () => new Promise(resolve => { acknowledge = resolve; }) });
  try {
    const failed = run.button("确认修改").props.onClick(); acknowledge(false); await failed; await flush();
    assert.match(run.text(), /操作未完成/);
    const late = run.button("确认修改").props.onClick();
    run.props.queueId = "another-queue";
    await flush(); acknowledge(true); await late; await flush();
    assert.doesNotMatch(run.text(), /已确认修改|操作未完成/);
  } finally { run.close(); }
});

test("failed review actions show the server reason and unchanged records use a readable label", async () => {
  const run = harness({ acceptRevision: async () => false, requestError: "原稿已更新，不能确认旧版本" });
  try {
    await run.button("确认修改").props.onClick(); await flush();
    assert.match(run.text(), /原稿已更新，不能确认旧版本/);
    assert.doesNotMatch(run.text(), /操作未完成/);
    run.props.revisions = [{ ...run.props.revisions[0], status: "UNCHANGED" }]; await flush();
    assert.match(run.text(), /正文未变/);
    assert.doesNotMatch(run.text(), /UNCHANGED/);
  } finally { run.close(); }
});

test("conflicting local input remains visible and cannot submit over the newer script", async () => {
  const run = harness({ revisions: [] });
  try {
    run.find(node => node.type === "textarea").props["onUpdate:modelValue"]("本地需要保留的文字"); await flush();
    run.props.segments = [{ segment_number: 1, script: "远端更新后的稿子" }]; await flush();
    assert.equal(run.find(node => node.type === "textarea").value, "本地需要保留的文字");
    assert.equal(run.button("提交审阅").props.disabled, true);
    assert.match(run.text(), /先复制要保留的文字/);
  } finally { run.close(); }
});
