import { app, BrowserWindow, ipcMain, dialog, shell } from "electron";
import { readFile, writeFile, stat } from "node:fs/promises";
import { mkdirSync } from "node:fs";
import { join, basename } from "node:path";
import { createSseParser, createStreamCompletionGuard } from "../shared/sse.js";

const APP_NAME = "Audio智能助手";
// 产品改名后沿用原有浏览器资料目录，保留登录状态与本机收听进度。
const profilePath = process.env.AUDIORAG_DESKTOP_SMOKE === "1"
  ? app.getPath("userData")
  : join(app.getPath("appData"), "audiorag-desktop");
mkdirSync(profilePath, { recursive: true });
app.setPath("userData", profilePath);
app.setPath("sessionData", profilePath);
app.setName(APP_NAME);

const configured = new URL(
  process.env.AUDIORAG_BACKEND_URL || "http://127.0.0.1:18081",
);
if (
  configured.protocol !== "http:" ||
  !["127.0.0.1", "localhost"].includes(configured.hostname)
)
  throw new Error("桌面客户端只连接本机 Audio智能助手后端");
const BACKEND = configured.origin;
const active = new Map();
let window;

function url(path) {
  if (typeof path !== "string" || !/^\/api\/[a-zA-Z0-9/_-]+$/.test(path))
    throw new Error("无效的 API 路径");
  return BACKEND + path;
}
function headers(token, json = false) {
  return {
    ...(json ? { "Content-Type": "application/json" } : {}),
    ...(token ? { Authorization: "Bearer " + token } : {}),
  };
}
async function checked(response) {
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(
      data.detail || data.message || "后端请求失败：" + response.status,
    );
  }
  return response;
}
async function request({ path, method = "GET", body = null, token }) {
  if (!["GET", "POST", "PUT", "DELETE"].includes(method))
    throw new Error("无效请求方式");
  const response = await fetch(url(path), {
    method,
    headers: headers(token, body !== null),
    body: body === null ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(600000),
  });
  await checked(response);
  return response.status === 204 ? null : response.json();
}
async function stream(sender, { id, body, token }) {
  if (!/^[0-9a-f-]{36}$/i.test(id)) throw new Error("无效任务编号");
  const key = sender.id + ":" + id;
  const controller = new AbortController();
  active.set(key, controller);
  const timer = setTimeout(() => controller.abort(), 900000);
  // 服务端可能已经失败但连接没关：只要 90 秒没有新数据就主动中止，避免界面一直停在"正在思考"。
  let idleTimer = null;
  let idleFired = false;
  const armIdle = () => {
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(() => { idleFired = true; controller.abort(); }, 90000);
  };
  armIdle();
  try {
    const response = await checked(
      await fetch(url("/api/v1/chat/stream"), {
        method: "POST",
        headers: headers(token, true),
        body: JSON.stringify(body),
        signal: controller.signal,
      }),
    );
    const completion = createStreamCompletionGuard();
    const parse = createSseParser((event) => {
      completion.accept(event);
      if (!sender.isDestroyed()) sender.send("stream:event", { id, event });
    });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      armIdle();
      parse(decoder.decode(chunk.value, { stream: true }));
    }
    parse(decoder.decode());
    completion.finish();
    if (!sender.isDestroyed())
      sender.send("stream:event", { id, complete: true });
  } catch (error) {
    const message = idleFired
      ? "90 秒没有收到模型的新进展，已中止；可重试，或把指令拆小一些"
      : error.message;
    if (!sender.isDestroyed())
      sender.send("stream:event", { id, error: message });
  } finally {
    clearTimeout(timer);
    if (idleTimer) clearTimeout(idleTimer);
    controller.abort(); // 协议校验失败时也关闭正文流，不能遗留未消费的连接。
    active.delete(key);
  }
}
ipcMain.handle("api:request", (_event, args) => request(args));
ipcMain.on("stream:start", (event, args) =>
  stream(event.sender, args).catch((error) =>
    event.sender.send("stream:event", { id: args.id, error: error.message }),
  ),
);
ipcMain.on("stream:cancel", (event, id) =>
  active.get(event.sender.id + ":" + id)?.abort(),
);

ipcMain.handle(
  "knowledge:upload",
  async (_event, { projectId, title, transcript, token }) => {
    if (!Number.isSafeInteger(projectId) || projectId < 1)
      throw new Error("项目编号无效");
    const selected = await dialog.showOpenDialog(window, {
      properties: ["openFile"],
      filters: [
        {
          name: "文本、字幕或音频",
          extensions: [
            "txt",
            "md",
            "srt",
            "vtt",
            "json",
            "jsonl",
            "csv",
            "wav",
            "mp3",
            "m4a",
            "flac",
            "ogg",
          ],
        },
      ],
    });
    if (selected.canceled) return null;
    const path = selected.filePaths[0];
    if ((await stat(path)).size > 100 * 1024 * 1024)
      throw new Error("文件最大 100MB");
    const form = new FormData();
    form.append("file", new Blob([await readFile(path)]), basename(path));
    form.append("title", title?.trim() || basename(path));
    form.append("project_id", String(projectId));
    if (transcript?.trim()) form.append("transcript", transcript);
    const response = await checked(
      await fetch(url("/api/v1/knowledge/audio"), {
        method: "POST",
        headers: headers(token),
        body: form,
        signal: AbortSignal.timeout(600000),
      }),
    );
    return response.json();
  },
);
ipcMain.handle(
  "knowledge:download",
  async (_event, { projectId, recordId, filename, token }) => {
    if (
      ![projectId, recordId].every(
        (value) => Number.isSafeInteger(value) && value > 0,
      )
    )
      throw new Error("资料编号无效");
    const selected = await dialog.showSaveDialog(window, {
      defaultPath: basename(filename || "document.txt"),
    });
    if (selected.canceled) return false;
    const response = await checked(
      await fetch(
        url(
          "/api/v1/projects/" +
            projectId +
            "/knowledge/" +
            recordId +
            "/download",
        ),
        { headers: headers(token), signal: AbortSignal.timeout(120000) },
      ),
    );
    await writeFile(
      selected.filePath,
      Buffer.from(await response.arrayBuffer()),
    );
    return true;
  },
);
ipcMain.handle("script:save", async (_event, { filename, content } = {}) => {
  if (typeof content !== "string" || !content.trim())
    throw new Error("讲稿为空，无法导出");
  const selected = await dialog.showSaveDialog(window, {
    defaultPath: basename(filename || "讲稿.md"),
    filters: [
      { name: "Markdown", extensions: ["md"] },
      { name: "纯文本", extensions: ["txt"] },
    ],
  });
  if (selected.canceled || !selected.filePath) return false;
  await writeFile(selected.filePath, content, "utf8");
  return true;
});
ipcMain.handle("source:open", async (_event, source) => {
  const parsed = new URL(source);
  if (!["https:", "http:"].includes(parsed.protocol))
    throw new Error("来源地址无效");
  await shell.openExternal(parsed.href);
});
function createWindow() {
  window = new BrowserWindow({
    width: 1480,
    height: 920,
    minWidth: 1060,
    minHeight: 700,
    show: process.env.AUDIORAG_DESKTOP_SMOKE !== "1",
    title: APP_NAME,
    backgroundColor: "#f6f7f9",
    webPreferences: {
      preload: join(import.meta.dirname, "../preload/index.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
    },
  });
  window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  // 录入音色需要麦克风；只放行本窗口的 media 权限，其余一律拒绝。
  window.webContents.session.setPermissionRequestHandler((contents, permission, callback) =>
    callback(permission === "media" && contents === window.webContents),
  );
  window.webContents.session.setPermissionCheckHandler((contents, permission) =>
    permission === "media" && contents === window.webContents,
  );
  window.webContents.on("will-navigate", (event) => event.preventDefault());
  if (process.env.ELECTRON_RENDERER_URL)
    window.loadURL(process.env.ELECTRON_RENDERER_URL);
  else window.loadFile(join(import.meta.dirname, "../renderer/index.html"));
  window.on("closed", () => {
    for (const task of active.values()) task.abort();
    window = null;
  });
}
app.whenReady().then(createWindow);
app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
app.on("activate", () => {
  if (!BrowserWindow.getAllWindows().length) createWindow();
});
ipcMain.handle("app:info", () => ({
  name: APP_NAME,
  backend: BACKEND,
  version: app.getVersion(),
}));
