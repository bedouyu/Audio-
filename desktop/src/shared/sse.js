// SSE 帧可能跨任意网络 chunk；只有帧完整后才解析 JSON。
export function createSseParser(onEvent) {
  let buffer = "";
  return (text) => {
    buffer += text;
    if (buffer.length > 1024 * 1024) throw new Error("事件过大");
    let match;
    while ((match = /\r?\n\r?\n/.exec(buffer))) {
      const frame = buffer.slice(0, match.index);
      buffer = buffer.slice(match.index + match[0].length);
      const data = frame
        .split(/\r?\n/)
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n");
      if (data) onEvent(JSON.parse(data));
    }
  };
}

// DONE只是协议结束标记，不能替代真实RESULT；不要把断连/半截回答显示为已完成。
export function createStreamCompletionGuard() {
  let result = false;
  let done = false;
  return {
    accept(event) {
      if (!event || typeof event.type !== "string") throw new Error("无效流式事件");
      if (done || (result && event.type !== "DONE")) throw new Error("结果结束后收到额外事件");
      if (event.type === "ERROR") throw new Error(String(event.data || "任务失败"));
      if (event.type === "RESULT") {
        const value = event.data;
        if (!value || typeof value.answer !== "string" || !value.session_id || !value.run_id
            || !Array.isArray(value.artifacts) || !["SUCCEEDED", "AWAITING_CONFIRMATION"].includes(value.status))
          throw new Error("任务结果不完整，请查看后端任务状态");
        result = true;
      }
      if (event.type === "DONE") {
        if (!result) throw new Error("任务结束但未收到完整结果");
        done = true;
      }
    },
    finish() {
      if (!result || !done) throw new Error("任务未完整结束，请查看运行提示");
    },
  };
}
