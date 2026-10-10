/* TyranoPocket minimal read-only compatibility. Not Node or Electron emulation. */
(() => {
  'use strict';
  if (globalThis.require) return;
  const fail = name => { throw new Error('TyranoPocket unsupported API: ' + name); };
  const cwd = decodeURIComponent(location.pathname).replace(/\/[^/]*$/, '') || '/';
  function normalize(value) {
    if (typeof value !== 'string' || /[\0-\x1f\\:]/.test(value)) throw new Error('Invalid sandbox path');
    const absolute = value.startsWith('/');
    const parts = [];
    for (const part of value.split('/')) {
      if (!part || part === '.') continue;
      if (part === '..') { if (parts.length && parts.at(-1) !== '..') parts.pop(); else if (!absolute) parts.push('..'); }
      else parts.push(part);
    }
    return (absolute ? '/' : '') + parts.join('/') || (absolute ? '/' : '.');
  }
  const path = Object.freeze({
    sep: '/', delimiter: ':', normalize,
    join: (...parts) => normalize(parts.filter(Boolean).join('/')),
    resolve: (...parts) => { let p = cwd; for (const x of parts) if (x) p = x.startsWith('/') ? x : p + '/' + x; return normalize(p); },
    basename: (p, suffix = '') => { const b = normalize(p).split('/').pop(); return suffix && b.endsWith(suffix) ? b.slice(0, -suffix.length) : b; },
    dirname: p => { p = normalize(p); const i = p.lastIndexOf('/'); return i < 0 ? '.' : i === 0 ? '/' : p.slice(0, i); },
    extname: p => { const b = path.basename(p), i = b.lastIndexOf('.'); return i > 0 ? b.slice(i) : ''; },
    isAbsolute: p => typeof p === 'string' && p.startsWith('/')
  });
  function url(p) {
    p = path.resolve(p);
    return location.origin + p.split('/').map(encodeURIComponent).join('/');
  }
  function read(p, head = false) {
    const request = new XMLHttpRequest();
    request.open(head ? 'HEAD' : 'GET', url(p), false);
    request.setRequestHeader('X-TyranoPocket-Read', '1');
    if (!head) request.overrideMimeType('text/plain; charset=x-user-defined');
    request.send();
    if (request.status >= 400 || request.status === 0) throw new Error('Sandbox file unavailable');
    return request.responseText;
  }
  const fs = Object.freeze({
    existsSync: p => { try { read(p, true); return true; } catch (_) { return false; } },
    readFileSync: (p, options) => {
      const encoding = typeof options === 'string' ? options : options?.encoding;
      const binary = read(p);
      if (binary.length > 8 * 1024 * 1024) throw new Error('Synchronous file read limit');
      const bytes = Uint8Array.from(binary, c => c.charCodeAt(0) & 255);
      bytes.toString = (enc = 'utf8') => {
        if (!['utf8', 'utf-8', 'shift_jis', 'shift-jis', 'sjis', 'ascii'].includes(enc.toLowerCase())) return fail('encoding ' + enc);
        return new TextDecoder(enc === 'utf8' ? 'utf-8' : enc).decode(bytes);
      };
      return encoding ? bytes.toString(encoding) : bytes;
    }
  });
  const electron = new Proxy(Object.create(null), { get: (_, key) => fail('electron.' + String(key)) });
  Object.defineProperty(globalThis, 'require', { value: name => {
    name = String(name).replace(/^node:/, '');
    if (name === 'path') return path;
    if (name === 'fs') return fs;
    if (name === 'electron') return electron;
    return fail('require(' + name + ')');
  }});
  Object.defineProperty(globalThis, 'process', { value: Object.freeze({platform: 'linux', env: Object.freeze({}), versions: Object.freeze({}), cwd: () => cwd}) });
  Object.defineProperty(globalThis, '__dirname', { value: cwd });
  Object.defineProperty(globalThis, '__filename', { value: decodeURIComponent(location.pathname) });
})();
