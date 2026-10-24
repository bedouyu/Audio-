// 按中文字符、其他语言单词和空白分词；文本始终以纯文本渲染。
function tokens(text) {
  return text.match(/[\p{Script=Han}]|(?:(?!\p{Script=Han})[\p{L}\p{N}_])+|[^\S\n]+|\n|[^\p{L}\p{N}_\s]/gu) || [];
}

function compact(parts) {
  const result = [];
  for (const part of parts) {
    if (!part.text) continue;
    const previous = result[result.length - 1];
    if (previous?.type === part.type) previous.text += part.text;
    else result.push({ ...part });
  }
  // 替换区域统一先显示删除再显示新增，便于逐项阅读。
  const ordered = [];
  for (let i = 0; i < result.length;) {
    if (result[i].type === "equal") { ordered.push(result[i++]); continue; }
    let removed = "", added = "";
    while (i < result.length && result[i].type !== "equal") {
      if (result[i].type === "remove") removed += result[i].text;
      else added += result[i].text;
      i++;
    }
    if (removed) ordered.push({ type: "remove", text: removed });
    if (added) ordered.push({ type: "add", text: added });
  }
  return ordered;
}

function backtrack(trace, before, after) {
  const parts = [];
  let x = before.length, y = after.length;
  for (let d = trace.length - 1; d >= 0; d--) {
    const previous = trace[d], k = x - y;
    const previousK = k === -d || (k !== d && (previous.get(k - 1) ?? -Infinity) < (previous.get(k + 1) ?? -Infinity))
      ? k + 1 : k - 1;
    const previousX = previous.get(previousK) ?? 0, previousY = previousX - previousK;
    while (x > previousX && y > previousY) {
      parts.push({ type: "equal", text: before[--x] });
      y--;
    }
    if (d === 0) break;
    if (x === previousX) parts.push({ type: "add", text: after[--y] });
    else parts.push({ type: "remove", text: before[--x] });
  }
  return parts.reverse();
}

function middleDiff(before, after, maxSteps, maxDistance) {
  if (!before.length) return [{ type: "add", text: after.join("") }];
  if (!after.length) return [{ type: "remove", text: before.join("") }];
  const frontier = new Map([[1, 0]]), trace = [];
  let steps = 0;
  for (let d = 0; d <= Math.min(before.length + after.length, maxDistance); d++) {
    trace.push(new Map(frontier));
    for (let k = -d; k <= d; k += 2) {
      if (++steps > maxSteps) return null;
      let x = k === -d || (k !== d && (frontier.get(k - 1) ?? -Infinity) < (frontier.get(k + 1) ?? -Infinity))
        ? frontier.get(k + 1) ?? 0 : (frontier.get(k - 1) ?? 0) + 1;
      let y = x - k;
      while (x < before.length && y < after.length && before[x] === after[y]) {
        x++; y++;
        if (++steps > maxSteps) return null;
      }
      frontier.set(k, x);
      if (x >= before.length && y >= after.length) return backtrack(trace, before, after);
    }
  }
  return null;
}

/** 可还原修改前后全文；复杂大稿超过工作预算时按整个变更区展示，避免阻塞界面。 */
export function scriptDiff(beforeText = "", afterText = "", { maxSteps = 250000, maxDistance = 512 } = {}) {
  const before = String(beforeText ?? ""), after = String(afterText ?? "");
  if (before === after) return before ? [{ type: "equal", text: before }] : [];
  const a = tokens(before), b = tokens(after);
  let prefix = 0, suffix = 0;
  while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++;
  while (suffix < a.length - prefix && suffix < b.length - prefix
    && a[a.length - suffix - 1] === b[b.length - suffix - 1]) suffix++;
  const removed = a.slice(prefix, a.length - suffix), added = b.slice(prefix, b.length - suffix);
  const middle = middleDiff(removed, added, maxSteps, maxDistance) || [
    { type: "remove", text: removed.join("") }, { type: "add", text: added.join("") }
  ];
  return compact([
    { type: "equal", text: a.slice(0, prefix).join("") }, ...middle,
    { type: "equal", text: suffix ? a.slice(a.length - suffix).join("") : "" }
  ]);
}
