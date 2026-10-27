(() => {
  "use strict";
  // Icônes dans le style des symboles iOS (trait arrondi, 24 px). Dessinées pour l'appli, ce ne sont pas les symboles d'Apple.
  const P = {
    xmark: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
    flip: '<path d="M8.5 5.5l1-1.5h5l1 1.5H18A2.5 2.5 0 0 1 20.5 8v8.5A2.5 2.5 0 0 1 18 19H6a2.5 2.5 0 0 1-2.5-2.5V8A2.5 2.5 0 0 1 6 5.5z"/><path d="M9.2 12.3a3 3 0 0 1 5.4-1.1M14.8 12.9a3 3 0 0 1-5.4 1.1"/><path d="M14.8 9.4v1.9h-1.9M9.2 15.6v-1.9h1.9"/>',
    photos: '<rect x="3.5" y="8" width="13" height="12" rx="2.8"/><path d="M7 5h10.2a3.3 3.3 0 0 1 3.3 3.3V17"/><path d="M3.5 17l3.8-3.3 3 2.5 2-1.6 3.7 3"/><circle cx="8.3" cy="11.5" r="1" fill="currentColor" stroke="none"/>',
    send: '<path fill="currentColor" stroke="none" d="M20.8 3.2 3.4 10.2c-.7.3-.6 1.3.1 1.5l6.3 2 2 6.3c.2.7 1.2.8 1.5.1l7.5-17.4c.3-.7-.4-1.3-1-1.1z"/>',
    flame: '<path fill="currentColor" stroke="none" d="M12.3 2.3c.3 2.7-.9 4.2-2.3 5.8-1.5 1.7-3.4 3.5-3.4 6.7a5.4 5.4 0 0 0 5.4 5.7 5.4 5.4 0 0 0 5.5-5.6c0-2-.9-3.5-2-4.7-.3 1.1-.9 1.9-1.8 2.3.5-3.9-.3-7.7-1.4-10.2z"/><path fill="rgba(255,255,255,.55)" stroke="none" d="M12 20.3a2.6 2.6 0 0 1-2.6-2.7c0-1.6 1.2-2.3 2.6-4 1.4 1.7 2.6 2.4 2.6 4A2.6 2.6 0 0 1 12 20.3z"/>',
    trash: '<path d="M4.5 7h15M9.5 7V5.4A1.4 1.4 0 0 1 10.9 4h2.2a1.4 1.4 0 0 1 1.4 1.4V7M6.5 7l.8 11.2A2 2 0 0 0 9.3 20h5.4a2 2 0 0 0 2-1.8L17.5 7M10 11v5M14 11v5"/>',
    text: '<text x="12" y="17.2" text-anchor="middle" font-size="15.5" font-weight="600" font-family="-apple-system,system-ui,sans-serif" fill="currentColor" stroke="none">Aa</text>',
    filters: '<circle cx="9.3" cy="9.6" r="4.8"/><circle cx="14.7" cy="9.6" r="4.8"/><circle cx="12" cy="14.7" r="4.8"/>',
    pencil: '<path d="M16.2 4.3a2 2 0 0 1 2.8 0l.7.7a2 2 0 0 1 0 2.8L9 18.5l-4.3 1 1-4.3z"/><path d="M14.5 6l3.5 3.5"/>',
    smile: '<circle cx="12" cy="12" r="8.5"/><circle cx="9" cy="10.3" r=".95" fill="currentColor" stroke="none"/><circle cx="15" cy="10.3" r=".95" fill="currentColor" stroke="none"/><path d="M8.3 14.1a4.6 4.6 0 0 0 7.4 0"/>',
    tape: '<path d="M5 7h14l-1 2.5 1 2.5-1 2.5 1 2.5H5l1-2.5L5 12l1-2.5z"/><path d="M9.5 10.6h5M9.5 13.4h5"/>',
    timer: '<circle cx="12" cy="13.5" r="7.3"/><path d="M12 13.5V9.8M9.8 3.5h4.4M18.6 6.3l1.2-1.2"/>',
    undo: '<path d="M8.5 5.5l-4 4 4 4"/><path d="M4.5 9.5h9.2a5.3 5.3 0 0 1 0 10.6H9"/>',
    check: '<path d="M5 12.8l4.5 4.5L19 7.5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    nowpic: '<rect x="2.5" y="4" width="15" height="14" rx="2.8"/><circle cx="8" cy="9.4" r="1.3" fill="currentColor" stroke="none"/><path d="M2.5 15.4l4.4-3.8 3.2 2.7 2.4-1.9 5 4"/><circle cx="18.2" cy="16.6" r="4.6" fill="#2a1a24"/><path d="M18.2 14.2v2.6l1.7 1"/>',
    bell: '<path d="M6 16.5v-5a6 6 0 0 1 12 0v5l1.5 1.8h-15zM10 20.5a2.2 2.2 0 0 0 4 0"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  };
  function icon(name, size, cls) {
    const s = size || 22;
    return '<svg class="ios-i' + (cls ? " " + cls : "") + '" viewBox="0 0 24 24" width="' + s + '" height="' + s + '" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (P[name] || "") + "</svg>";
  }
  window.IOS = { icon, paths: P };
})();
