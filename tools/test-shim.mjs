import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { test } from 'node:test';

const script = readFileSync(new URL('../app/src/main/assets/tyranopocket-compat.js', import.meta.url), 'utf8');
function environment() {
  const requests = [];
  const files = new Map([['/data/hello.txt', 'hello'], ['/data/%ED%95%9C%20%2B%25.txt', 'unicode-name']]);
  class XHR {
    headers = {};
    open(method, url, async) { this.method = method; this.url = url; assert.equal(async, false); }
    setRequestHeader(k, v) { this.headers[k] = v; }
    overrideMimeType() {}
    send() {
      const u = new URL(this.url);
      assert.equal(u.origin, 'https://game-test.invalid');
      requests.push({path: u.pathname, headers: this.headers, method: this.method});
      this.status = files.has(u.pathname) ? 200 : 404;
      this.responseText = files.get(u.pathname) || '';
    }
  }
  const ctx = vm.createContext({location: {origin: 'https://game-test.invalid', pathname: '/index.html'}, XMLHttpRequest: XHR, TextDecoder, Uint8Array});
  vm.runInContext(script, ctx);
  return {ctx, requests, run: js => vm.runInContext(js, ctx)};
}
test('path operations preserve Node-like basename dirname join resolve', () => {
  const e = environment();
  assert.equal(e.run("require('path').join('data','scenario','../image/a.png')"), 'data/image/a.png');
  assert.equal(e.run("require('path').resolve('data','../tyrano/kag.js')"), '/tyrano/kag.js');
  assert.equal(e.run("require('path').basename('/data/a.ks','.ks')"), 'a');
  assert.equal(e.run("require('path').dirname('/data/a.ks')"), '/data');
  assert.equal(e.run("require('path').extname('a.ks')"), '.ks');
});
test('read-only fs uses local intercepted requests with native read size gate', () => {
  const e = environment();
  assert.equal(e.run("require('fs').existsSync('/data/hello.txt')"), true);
  assert.equal(e.run("require('fs').readFileSync('/data/hello.txt','utf8')"), 'hello');
  assert.equal(e.run("require('fs').readFileSync('/data/hello.txt').toString()"), 'hello');
  assert.equal(e.requests[1].headers['X-TyranoPocket-Read'], '1');
  assert.equal(e.run("require('fs').existsSync('/not-found')"), false);
});
test('Unicode, plus, percent, spaces encode once', () => {
  const e = environment();
  assert.equal(e.run("require('fs').readFileSync('/data/한 +%.txt','utf8')"), 'unicode-name');
  assert.equal(e.requests[0].path, '/data/%ED%95%9C%20%2B%25.txt');
});
test('unsupported OS modules and writes are not available', () => {
  const e = environment();
  for (const module of ['child_process','net','winreg','ffi','./addon.node']) assert.throws(() => e.run(`require('${module}')`));
  assert.throws(() => e.run("require('electron').ipcRenderer"));
  assert.equal(e.run("typeof require('fs').writeFileSync"), 'undefined');
  assert.equal(e.run("typeof Android"), 'undefined');
});
test('no Windows drive or external URL becomes a filesystem request', () => {
  const e = environment();
  for (const path of ['https://external.invalid/a', 'C:/Windows/win.ini', 'data\\secret', 'a\0b']) assert.throws(() => e.run(`require('fs').readFileSync(${JSON.stringify(path)})`));
  assert.equal(e.requests.length, 0);
});
test('existing require is left intact', () => {
  const ctx = vm.createContext({require: 'original'});vm.runInContext(script, ctx);assert.equal(ctx.require, 'original');
});

const lifecycle = readFileSync(new URL('../app/src/main/assets/runtime-lifecycle.js', import.meta.url), 'utf8');
test('lifecycle pauses and resumes only previously active media and audio contexts', async () => {
  const media = {paused: false, pauses: 0, plays: 0, pause() {this.paused = true;this.pauses++;}, play() {this.paused = false;this.plays++;return Promise.resolve();}};
  class AudioContext {state = 'running';suspend(){this.state='suspended';return Promise.resolve();}resume(){this.state='running';return Promise.resolve();}}
  const ctx = vm.createContext({document: {querySelectorAll: () => [media]}, AudioContext});ctx.window = ctx;
  vm.runInContext(lifecycle, ctx);vm.runInContext('context = new AudioContext()', ctx);
  vm.runInContext('__tyranoPocketMedia.pause();__tyranoPocketMedia.pause()', ctx);
  assert.equal(media.pauses, 1);assert.equal(ctx.context.state, 'suspended');
  vm.runInContext('__tyranoPocketMedia.resume()', ctx);
  assert.equal(media.plays, 1);assert.equal(ctx.context.state, 'running');
});
