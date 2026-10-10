/* Runtime media lifecycle only; no native interface or device access. */
(() => {
  if (window.__tyranoPocketMedia) return;
  let paused = false;
  const contexts = new Set(), activeMedia = new Set(), activeContexts = new Set();
  for (const key of ['AudioContext', 'webkitAudioContext']) {
    const Original = window[key];
    if (Original) window[key] = new Proxy(Original, { construct(target, args) {
      const context = Reflect.construct(target, args); contexts.add(context);
      if (paused) context.suspend().catch(() => {});
      return context;
    }});
  }
  window.__tyranoPocketMedia = {
    pause() {
      if (paused) return; paused = true;
      document.querySelectorAll('audio,video').forEach(media => { if (!media.paused) { activeMedia.add(media); media.pause(); } });
      contexts.forEach(context => { if (context.state === 'closed') contexts.delete(context); else if (context.state === 'running') { activeContexts.add(context); context.suspend().catch(() => {}); } });
    },
    resume() {
      if (!paused) return; paused = false;
      activeContexts.forEach(context => context.resume().catch(() => {}));activeContexts.clear();
      activeMedia.forEach(media => media.play().catch(() => {}));activeMedia.clear();
    }
  };
})();
