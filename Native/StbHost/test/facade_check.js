const fs = require('fs'), assert = require('assert');
const sent = [];
const window = {
  __stbSnapshot: { sessionId: 's1', mac: '00:1A:79:12:34:56', serialNumber: 'SN', model: 'MAG250', deviceId: '' },
  stbNative: { postMessage: m => sent.push(JSON.parse(m)) },
  events: [], dispatchEvent(e) { this.events.push(e.detail); },
};
global.CustomEvent = class { constructor(t, o) { this.detail = o.detail; } };
const console = { warn() {}, error() {} };
new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(window, console);
const g = window.gSTB;
assert.strictEqual(g.GetDeviceMacAddress(), '00:1A:79:12:34:56');
assert.strictEqual(window.stb, g);
assert.strictEqual(window.__stbSnapshot, undefined);
assert.strictEqual(sent[0].type, 'bridge.hello');
g.Play('ffmpeg http://x/live.m3u8');
assert.deepStrictEqual([sent[1].type, sent[1].payload.command, sent[1].sessionId], ['player.play', 'ffmpeg http://x/live.m3u8', 's1']);
g.SetEnv('a', 1); g.SetEnv('b', 2);
assert.strictEqual(sent.filter(m => m.type === 'bridge.unsupported').length, 1);
g.SetVolume(150); assert.strictEqual(g.GetVolume(), 100);
window.stbNative.onmessage({ data: JSON.stringify({ sessionId: 'old', type: 'player.state' }) });
window.stbNative.onmessage({ data: JSON.stringify({ sessionId: 's1', type: 'player.state', payload: {} }) });
assert.strictEqual(window.events.length, 1);

// GetUID vs node:crypto (same math as javax.crypto HmacSHA256 + android Base64.DEFAULT)
const crypto = require('crypto');
const b64 = (k, d) => crypto.createHmac('sha256', Buffer.from(k, 'utf8')).update(d, 'utf8').digest('base64') + '\n';
const sha = s => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
function load(snap) {
  const w = { __stbSnapshot: Object.assign({ sessionId: 's' }, snap), stbNative: { postMessage() {} }, dispatchEvent() {} };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  return w.gSTB;
}
const id = sha('00:1A:79:12:34:56').toUpperCase();
const u = load({ sendDeviceId: true, deviceId: id });
assert.strictEqual(u.GetUID(), id);
for (const t of ['', 'tok', 'x'.repeat(55), 'y'.repeat(64), 'z'.repeat(100), 'ưđ€😀'])
  assert.strictEqual(u.GetUID(t), b64(t || id, id), 'signature ' + t.length);
assert.strictEqual(u.GetUID('device_id', 'device_id'), sha(b64('device_id', 'device_id')).toUpperCase());
assert.strictEqual(u.GetUID('k'.repeat(70), 'k'.repeat(70)), sha(b64('k'.repeat(70), 'k'.repeat(70))).toUpperCase());
assert.strictEqual(u.GetUID('device_id', 'other'), id);
assert.strictEqual(u.GetUID('a', 'b'), '');
assert.strictEqual(u.GetUID('tok', ''), b64('tok', id));
assert.strictEqual(u.GetUID('a', 1, 2), '');
const custom = load({ sendDeviceId: true, deviceId: id, deviceId2: 'ID2', signature: 'SIG' });
assert.deepStrictEqual([custom.GetUID('t'), custom.GetUID('d', 'd')], ['SIG', 'ID2']);
const off = load({ sendDeviceId: false, deviceId: id, deviceId2: 'ID2', signature: 'SIG' });
assert.deepStrictEqual([off.GetUID(), off.GetUID('t'), off.GetUID('d', 'd')], ['', '', '']);

// GetHashVersion1 = sha1(sha1(sha1(a) + KEY) + b) as StbEmu 2.0.23 computes it (ISO-8859-1, lowercase hex); portal sends it as handshake prehash.
const sha1 = s => crypto.createHash('sha1').update(Buffer.from(s, 'latin1')).digest('hex');
const HV1_KEY = 'dA0j6HpVFcMgNjUBDr0QhwTBIzLHDIrynuQy4XNJ';
const hv1 = (a, b) => sha1(sha1(sha1(a) + HV1_KEY) + (b || ''));
for (const [a, b] of [['abc', undefined], ['abc', 'xyz'], ['', ''], ['x'.repeat(200), 'é']])
  assert.strictEqual(u.GetHashVersion1(a, b), hv1(a, b), 'GetHashVersion1 ' + a.length);
// "Authorization failed." (server dropped the session) reaches native before the portal's own handler runs.
{
  const posted = [], order = [];
  class Xhr {
    constructor() { this.listeners = []; this.readyState = 0; this.status = 0; this.responseType = ''; this.responseText = ''; }
    open() { order.push('open'); }
    setRequestHeader() {}
    addEventListener(t, f) { this.listeners.push(f); }
    finish(text) { this.readyState = 4; this.status = 200; this.responseText = text; this.listeners.forEach(f => f.call(this)); if (this.onreadystatechange) this.onreadystatechange(); }
  }
  const w = { __stbSnapshot: { sessionId: 's' }, stbNative: { postMessage: m => posted.push(JSON.parse(m).type) }, dispatchEvent() {}, XMLHttpRequest: Xhr, location: { href: 'http://p/c/', origin: 'http://p' } };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  const ok = new w.XMLHttpRequest(); ok.open('GET', '/x?action=get_profile'); ok.finish('{"js":{"status":0}}');
  const denied = new w.XMLHttpRequest(); denied.open('GET', '/x?action=get_genres'); denied.setRequestHeader('Authorization', 'Bearer T');
  denied.onreadystatechange = () => posted.push('portal handler');
  denied.finish('Authorization failed.');
  assert.deepStrictEqual(posted, ['bridge.hello', 'portal.phase', 'portal.auth_failed', 'portal handler']);
  assert.deepStrictEqual(order, ['open', 'open']);
}
// Value-returning MAG APIs the portal calls at start-up must not return undefined (StbEmu returns ""/false).
assert.deepStrictEqual([u.IsFileExist('/x'), u.RDir('getenv x'), u.LoadUserData('f')], [false, '', '']);
// Device/firmware identity the portal sends in get_profile; undefined here made the portal answer "Your STB is not supported".
const mag = load({ model: 'MAG250' });
assert.strictEqual(mag.GetDeviceModelExt(), 'MAG250');
assert.match(mag.Version(), /^JS API version: \d+; STB API version: \d+; Player Engine version: 0x[0-9a-f]+$/);
assert.strictEqual(typeof mag.GetDeviceVersionHardware(), 'string');
// Link status: undefined reads as "no network" and the portal stops after get_localization.
const nav = { onLine: true };
Object.defineProperty(globalThis, 'navigator', { value: nav, configurable: true }); // Node 22 has its own read-only navigator
assert.deepStrictEqual([mag.GetLanLinkStatus(), mag.GetWifiLinkStatus()], [true, true]);
nav.onLine = false;
assert.deepStrictEqual([mag.GetLanLinkStatus(), mag.GetWifiLinkStatus()], [false, false]);
// TV screen: Stalker picks its layout from screen.height (480/576/720/1080 only) and otherwise "reboots" into
// graphicres 720 forever; `typeof gSTB.prototype === 'function'` would switch it to innerHeight instead.
{
  const metas = [];
  const w = {
    __stbSnapshot: { sessionId: 's' }, stbNative: { postMessage() {} }, dispatchEvent() {},
    screen: { width: 853, height: 384 }, innerWidth: 853, innerHeight: 384,
    addEventListener() {},
    document: {
      createElement: () => ({ setAttribute(k, v) { this[k] = v; } }),
      documentElement: { appendChild: m => metas.push(m) },
      addEventListener() {},
    },
  };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  assert.deepStrictEqual([w.screen.width, w.screen.height], [1280, 720]);
  assert.strictEqual(w.gSTB.prototype, undefined);
  assert.strictEqual(metas[0].content, 'width=1600'); // 20:9 landscape phone: 720 CSS px tall fits exactly
}
{
  // At document start the page may have no size yet (innerHeight 0): fall back to 1280, never "width=NaN".
  const metas = [];
  const w = {
    __stbSnapshot: { sessionId: 's' }, stbNative: { postMessage() {} }, dispatchEvent() {}, addEventListener() {},
    screen: {}, innerWidth: 0, innerHeight: 0,
    document: { createElement: () => ({ setAttribute(k, v) { this[k] = v; } }), documentElement: { appendChild: m => metas.push(m) }, addEventListener() {} },
  };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  assert.strictEqual(metas[0].content, 'width=1280');
}

// ChannelSource: reads the portal's in-memory channels once they exist, resends only on change, plays by id.
{
  const { makePortal, SOURCE } = require('./portal_fixture');
  const sent = [];
  const w = {
    __stbSnapshot: { sessionId: 's' }, dispatchEvent() {},
    stbNative: { postMessage: m => sent.push(JSON.parse(m)) },
    __stbChannelSource: SOURCE, // test hook: production uses the built-in descriptor
  };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  Object.assign(w, makePortal()); // the portal's scripts run after the facade (and replace window.stb)
  w.__stbPublishChannels();
  w.__stbPublishChannels(); // unchanged → no second message
  const data = sent.filter(m => m.type === 'channels.data');
  assert.strictEqual(data.length, 1);
  const p = data[0].payload;
  assert.strictEqual(p.channels.length, 3);
  assert.deepStrictEqual(Object.keys(p.channels[0]).sort(), ['cmd', 'genreId', 'id', 'name', 'number']);
  assert.strictEqual(p.channels[0].cmd, 'ffrt http://x/1'); // kept by native for the reconnect fast path
  assert.ok(p.channels.every(c => typeof c.id === 'string'));
  assert.deepStrictEqual(p.favIds, ['13']);
  assert.deepStrictEqual(p.genres.map(g => g.id), ['g1', 'g2']); // portal's pseudo-genre '*' (All) dropped: app has its own tab

  w.stbNative.onmessage({ data: JSON.stringify({ sessionId: 's', type: 'channels.play', payload: { id: '12' } }) });
  assert.strictEqual(w.__played.length, 1);
  assert.strictEqual(String(w.__played[0][SOURCE.fields.id]), '12');
  assert.strictEqual(sent.filter(m => m.type === 'channels.play.accepted').pop().payload.id, '12');

  w.stbNative.onmessage({ data: JSON.stringify({ sessionId: 's', type: 'channels.play', payload: { id: '999' } }) });
  assert.strictEqual(sent.filter(m => m.type === 'channels.error').pop().payload.code, 'CHANNEL_NOT_FOUND');
}
{
  // Built-in descriptor (no test hook) reads the real Stalker shape.
  const { makePortal } = require('./portal_fixture');
  const sent = [];
  const w = { __stbSnapshot: { sessionId: 's' }, dispatchEvent() {}, stbNative: { postMessage: m => sent.push(JSON.parse(m)) },
    location: { origin: 'http://p.tv' } };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  Object.assign(w, makePortal());
  w.__stbPublishChannels();
  const p = sent.filter(m => m.type === 'channels.data')[0].payload;
  assert.strictEqual(p.channels.length, 3);
  // Logo + archive for the native list; channels without them carry neither key (16k channels: keep the payload lean).
  assert.deepStrictEqual([p.channels[0].logo, p.channels[0].archive], ['1.png', 1]);
  assert.deepStrictEqual([p.channels[1].logo, p.channels[1].archive], [undefined, undefined]);
  assert.strictEqual(p.logoBase, 'http://p.tv/stalker_portal/misc/logos/320/');
}
{
  // No readable source (portal still loading / other portal) → nothing sent, no throw.
  const sent = [];
  const w = { __stbSnapshot: { sessionId: 's' }, dispatchEvent() {}, stbNative: { postMessage: m => sent.push(JSON.parse(m)) } };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  w.__stbPublishChannels();
  assert.strictEqual(sent.filter(m => m.type === 'channels.data').length, 0);
}

// Native seeds the env it kept for this profile (no reboot on reconnect) and is sent every change to keep.
{
  const posted = [];
  const w = {
    __stbSnapshot: { sessionId: 's' }, __stbEnvSeed: { ntpurl: 'pool.ntp.org' },
    stbNative: { postMessage: m => posted.push(JSON.parse(m)) }, dispatchEvent() {},
  };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  assert.strictEqual(w.__stbEnvSeed, undefined);
  assert.strictEqual(w.gSTB.RDir('getenv ntpurl'), 'pool.ntp.org');
  w.gSTB.RDir('setenv timezone_conf Europe/Kiev');
  const saved = posted.filter(m => m.type === 'env.save');
  assert.deepStrictEqual(saved[saved.length - 1].payload, { ntpurl: 'pool.ntp.org', timezone_conf: 'Europe/Kiev' });
}

// Firmware env (RDir getenv/setenv) survives ExecAction('reboot') = page reload: the portal sets ntpurl, "reboots" and
// returns from user_init; on the next load it must read the value back or it loops without ever drawing its UI.
{
  const store = {};
  let reloads = 0;
  const w = {
    __stbSnapshot: { sessionId: 's' }, stbNative: { postMessage() {} }, dispatchEvent() {},
    localStorage: { getItem: k => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); } },
    location: { reload: () => reloads++ },
  };
  const run = () => { delete w.gSTB; new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console); return w.gSTB; };
  let g = run();
  assert.strictEqual(g.RDir('getenv ntpurl'), '');
  g.RDir('setenv ntpurl pool.ntp.org');
  g.RDir('setenv timezone_conf "" ');
  assert.strictEqual(g.ExecAction('reboot'), true);
  return new Promise(r => setTimeout(r, 0)).then(() => {
    assert.strictEqual(reloads, 1);
    g = run(); // after the reload
    assert.deepStrictEqual([g.RDir('getenv ntpurl'), g.RDir('getenv timezone_conf'), g.RDir('Model')], ['pool.ntp.org', '', '']);
    assert.strictEqual(g.ExecAction('graphicres 720'), false);
    process.stdout.write('facade ok\n');
  });
}
