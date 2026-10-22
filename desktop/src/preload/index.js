import { contextBridge, ipcRenderer } from "electron";
contextBridge.exposeInMainWorld("audioAgent", {
  info: () => ipcRenderer.invoke("app:info"),
  request: (args) => ipcRenderer.invoke("api:request", args),
  upload: (args) => ipcRenderer.invoke("knowledge:upload", args),
  download: (args) => ipcRenderer.invoke("knowledge:download", args),
  openSource: (source) => ipcRenderer.invoke("source:open", source),
  stream: (id, body, token, onEvent) =>
    new Promise((resolve, reject) => {
      const listener = (_event, update) => {
        if (update.id !== id) return;
        if (update.complete || update.error) {
          ipcRenderer.removeListener("stream:event", listener);
          update.error ? reject(new Error(update.error)) : resolve();
        } else {
          try {
            onEvent(update.event);
          } catch (error) {
            ipcRenderer.removeListener("stream:event", listener);
            ipcRenderer.send("stream:cancel", id);
            reject(error);
          }
        }
      };
      ipcRenderer.on("stream:event", listener);
      ipcRenderer.send("stream:start", { id, body, token });
    }),
  cancel: (id) => ipcRenderer.send("stream:cancel", id),
});
