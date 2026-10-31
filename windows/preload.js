const { contextBridge, ipcRenderer } = require("electron");
const OK = new Set(["state", "acc:add", "acc:bulk", "acc:remove", "acc:share", "acc:code", "settings:set", "settings:dir", "search:yt", "dl:start", "dl:show", "open:url"]);
contextBridge.exposeInMainWorld("ft", {
  invoke: (ch, arg) => (OK.has(ch) ? ipcRenderer.invoke(ch, arg) : Promise.reject(new Error("canal inconnu"))),
  onState: (fn) => ipcRenderer.on("state", (_e, s) => fn(s)),
});
