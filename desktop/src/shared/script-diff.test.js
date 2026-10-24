import test from "node:test";
import assert from "node:assert/strict";
import { scriptDiff } from "./script-diff.js";

function verify(before, after, options) {
  const parts = scriptDiff(before, after, options);
  assert.equal(parts.filter(part => part.type !== "add").map(part => part.text).join(""), before);
  assert.equal(parts.filter(part => part.type !== "remove").map(part => part.text).join(""), after);
  assert.ok(parts.every(part => ["equal", "add", "remove"].includes(part.type) && part.text.length));
  return parts;
}

test("Chinese replacements identify only the changed characters", () => {
  assert.deepEqual(verify("今天介绍音频学习。", "今天介绍音频生态。"), [
    { type: "equal", text: "今天介绍音频" }, { type: "remove", text: "学习" },
    { type: "add", text: "生态" }, { type: "equal", text: "。" }
  ]);
  assert.deepEqual(verify("Audio学习助手", "Audio智能助手").filter(part => part.type !== "equal"), [
    { type: "remove", text: "学习" }, { type: "add", text: "智能" }
  ]);
});

test("English words, spaces, newlines and emoji survive the round trip", () => {
  const parts = verify("Hello world!\nNext line 🐈.\r\n", "Hello audio world!\nAnother line 🐕.\r\n");
  assert.ok(parts.some(part => part.type === "add" && part.text.includes("audio")));
  assert.ok(parts.some(part => part.type === "remove" && part.text.includes("Next")));
  assert.ok(parts.every(part => !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(part.text)));
});

test("empty drafts, full replacements and literal markup are preserved", () => {
  verify("", "新增"); verify("删除", ""); verify("", "");
  verify("原文", "<img src=x onerror=alert(1)> & {{danger}}");
  assert.deepEqual(scriptDiff("同文", "同文"), [{ type: "equal", text: "同文" }]);
});

test("large complex scripts fall back within a fixed search budget without losing text", () => {
  const before = "共同开头\n" + "甲乙丙丁".repeat(15000) + "\n共同结尾";
  const after = "共同开头\n" + "戊己庚辛".repeat(15000) + "\n共同结尾";
  const parts = verify(before, after, { maxSteps: 10, maxDistance: 4 });
  assert.equal(parts.length, 4);
  assert.equal(parts[0].type, "equal");
  assert.equal(parts[3].type, "equal");
});

test("scattered edits in a long script preserve unchanged passages", () => {
  const before = "第一章，甲乙丙丁。\n".repeat(3000);
  const after = before.slice(0, 999) + "新增" + before.slice(999, 18000) + "修订" + before.slice(18002);
  const parts = verify(before, after);
  assert.ok(parts.filter(part => part.type === "equal").reduce((n, part) => n + part.text.length, 0) > before.length - 30);
});

test("varied edit sequences always reconstruct both original and proposed scripts", () => {
  let seed = 17;
  const random = n => { seed = (seed * 48271) % 2147483647; return seed % n; };
  const alphabet = ["你", "好", "猫", "a", "word", " ", "\n", "😀", "!", "<", "\t"];
  for (let run = 0; run < 160; run++) {
    const before = Array.from({ length: 35 }, () => alphabet[random(alphabet.length)]).join("");
    const after = Array.from(before);
    for (let edit = 0; edit < 8; edit++) after.splice(random(after.length + 1), random(3), alphabet[random(alphabet.length)]);
    verify(before, after.join(""));
  }
});
