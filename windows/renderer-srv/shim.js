// Application Android « Free tune server » : le moteur est en Kotlin ; ce fichier relie la page à ce moteur (même interface que sur Mac et Windows).
(function () {
  "use strict";
  if (window.ft || !window.FTA) return;
  const wait = {}; let seq = 0, onState = null;
  window.__ftres = (id, json) => { const w = wait[id]; if (w) { delete wait[id]; w(json == null ? null : JSON.parse(json)); } };
  window.__ftstate = (json) => { if (onState) onState(JSON.parse(json)); };
  window.ft = {
    invoke: (ch, arg) => new Promise((ok) => { const id = ++seq; wait[id] = ok; window.FTA.invoke(id, ch, JSON.stringify(arg === undefined ? null : arg)); }),
    onState: (fn) => { onState = fn; },
  };
})();
