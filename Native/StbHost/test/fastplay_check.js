// node test/fastplay_check.js src/main/assets/stb_facade.js
// Reconnect fast path: channels.fastplay waits for get_profile, calls create_link with the portal's own URL + headers,
// announces the link, then Play()s it; any failure reports channels.fastplay.failed.
const fs = require('fs'), assert = require('assert');

function load() {
  const sent = [], xhrs = [];
  class XHR {
    constructor() { this.l = []; this.headers = {}; this.readyState = 0; this.responseType = ''; xhrs.push(this); }
    addEventListener(t, f) { this.l.push(f); }
    open(method, url) { this.url = url; }
    setRequestHeader(n, v) { this.headers[n] = v; }
    send() { this.sent = true; }
    respond(status, text) { this.readyState = 4; this.status = status; this.responseText = text; this.l.forEach(f => f.call(this)); }
  }
  const window = {
    __stbSnapshot: { sessionId: 's1', mac: 'm' }, XMLHttpRequest: XHR, dispatchEvent() {},
    location: { href: 'http://p/c/', origin: 'http://p' },
    stbNative: { postMessage: m => sent.push(JSON.parse(m)) },
  };
  global.CustomEvent = class { constructor(t, o) { this.detail = o.detail; } };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(window, { log() {}, warn() {}, error() {} });
  const portalCall = (action, headers, status, text) => {
    const x = new window.XMLHttpRequest();
    x.open('GET', 'http://p/stalker_portal/server/load.php?type=stb&action=' + action + '&JsHttpRequest=1-xml');
    Object.keys(headers).forEach(k => x.setRequestHeader(k, headers[k]));
    x.respond(status, text);
  };
  const fastplay = (id, cmd) => window.stbNative.onmessage({ data: JSON.stringify({ version: 1, sessionId: 's1', type: 'channels.fastplay', payload: { id, cmd } }) });
  return { sent, xhrs, portalCall, fastplay, window };
}

{ // requested before the token is authorized: waits, then plays with the portal's URL and headers
  const t = load();
  t.portalCall('handshake', { 'X-User-Agent': 'Model: MAG250' }, 200, '{"js":{"token":"T"}}');
  t.fastplay('12', 'ffrt http://localhost/ch/12_');
  assert.strictEqual(t.xhrs.length, 1, 'no create_link before get_profile');
  t.portalCall('get_profile', { Authorization: 'Bearer T', 'X-User-Agent': 'Model: MAG250' }, 200, '{"js":{"status":0}}');
  const link = t.xhrs[2];
  assert.ok(link && link.sent, 'create_link sent once authorized');
  assert.strictEqual(link.url, 'http://p/stalker_portal/server/load.php?type=itv&action=create_link&cmd='
      + encodeURIComponent('ffrt http://localhost/ch/12_') + '&series=&forced_storage=undefined&disable_ad=0&download=0&JsHttpRequest=1-xml');
  assert.deepStrictEqual(link.headers, { authorization: 'Bearer T', 'x-user-agent': 'Model: MAG250' });
  link.respond(200, '{"js":{"id":"1","cmd":"ffrt http://stream/12.m3u8?token=x"}}');
  const types = t.sent.map(m => m.type);
  const playing = types.indexOf('channels.fastplay.playing');
  assert.ok(playing >= 0 && types.indexOf('player.play') > playing, 'link announced before Play()');
  assert.strictEqual(t.sent.find(m => m.type === 'player.play').payload.command, 'ffrt http://stream/12.m3u8?token=x');
}

{ // already authorized: runs at once; a server error falls back
  const t = load();
  t.portalCall('get_profile', { Authorization: 'Bearer T' }, 200, '{"js":{"status":0}}');
  t.fastplay('7', 'ffrt http://localhost/ch/7_');
  t.xhrs[1].respond(200, '{"js":{"error":"nothing_to_play"}}');
  assert.deepStrictEqual(t.sent.filter(m => m.type.startsWith('channels.fastplay')).map(m => [m.type, m.payload.id]),
      [['channels.fastplay.failed', '7']]);
  assert.ok(!t.sent.some(m => m.type === 'player.play'));
}

{ // rejected profile: never calls create_link
  const t = load();
  t.fastplay('7', 'c');
  t.portalCall('get_profile', {}, 200, '{"js":{"status":2}}');
  assert.strictEqual(t.xhrs.length, 1);
}

{ // timeout / auth failure on create_link: failed (auth_failed is also reported)
  const t = load();
  t.portalCall('get_profile', { Authorization: 'Bearer T' }, 200, '{"js":{"status":0}}');
  t.fastplay('7', 'c');
  t.xhrs[1].respond(200, 'Authorization failed.');
  const types = t.sent.map(m => m.type);
  assert.ok(types.includes('portal.auth_failed') && types.includes('channels.fastplay.failed'));
}

process.stdout.write('fastplay ok\n');

// Typed errors never guess missing session from arbitrary provider text.
for (const [status, body, kind] of [
  [401, '', 'AUTHENTICATION_REJECTED'], [403, '', 'FORBIDDEN'],
  [429, '', 'RATE_LIMITED'], [500, '', 'SERVER_UNAVAILABLE'], [0, '', 'NETWORK'],
  [200, '<html>error</html>', 'INVALID_RESPONSE'], [200, '{"js":{"id":3}}', 'INVALID_RESPONSE'],
  [200, '{"js":{"id":3,"status":2}}', 'PROFILE_REJECTED'],
  [200, '{"js":{"id":3,"status":0,"blocked":true}}', 'PROFILE_REJECTED']
]) {
  const t = load();
  t.portalCall('get_profile', {}, status, body);
  assert.strictEqual(t.sent.find(m => m.type === 'portal.failure').payload.kind, kind);
  assert.ok(!t.sent.some(m => m.type === 'portal.auth_failed'));
}
{
  const t = load();
  t.portalCall('handshake', {}, 200, '{"js":{"token":" "}}');
  assert.strictEqual(t.sent.find(m => m.type === 'portal.failure').payload.kind, 'INVALID_HANDSHAKE');
}
{
  const t = load();
  t.portalCall('get_profile', { Authorization: 'Bearer T' }, 200, '{"js":{"status":0}}');
  for (let i = 0; i < 10; i++) t.portalCall('get_genres', { Authorization: 'Bearer T' }, 401, '');
  assert.strictEqual(t.sent.filter(m => m.type === 'portal.auth_failed').length, 1, 'coalesce rejection burst');
}
{
  const t = load(), old = new t.window.XMLHttpRequest();
  t.portalCall('get_profile', { Authorization: 'Bearer T' }, 200, '{"js":{"status":0}}');
  old.open('GET', 'http://p/stalker_portal/server/load.php?action=get_genres');
  old.setRequestHeader('Authorization', 'Bearer T');
  t.portalCall('handshake', {}, 200, '{"js":{"token":"NEW"}}');
  t.portalCall('get_profile', { Authorization: 'Bearer NEW' }, 200, '{"js":{"status":0}}');
  old.respond(401, '');
  assert.ok(!t.sent.some(m => m.type === 'portal.auth_failed'), 'old response cannot reject new session');
  const foreign = new t.window.XMLHttpRequest();
  foreign.open('GET', 'https://cdn.invalid/load.php?action=get_profile');
  foreign.setRequestHeader('Authorization', 'Bearer FOREIGN');
  foreign.respond(200, '{"js":{"status":0}}');
  t.fastplay('7', 'cmd');
  assert.strictEqual(t.xhrs[t.xhrs.length - 1].headers.authorization, 'Bearer NEW');
}
{
  const t = load();
  t.portalCall('get_profile', { Authorization: 'Bearer T' }, 200, '{"js":{"status":0}}');
  t.window.stb = { player: { channels: [] } };
  t.window.__stbPublishChannels();
  assert.ok(!t.sent.some(m => m.type === 'channels.data'), 'startup empty is not yet a snapshot');
  t.portalCall('get_all_channels', { Authorization: 'Bearer T' }, 200, '{"js":{"data":[]}}');
  t.window.__stbPublishChannels();
  assert.deepStrictEqual(t.sent.find(m => m.type === 'channels.data').payload.channels, []);
}
process.stdout.write('session policy checks passed\n');

{
  const t = load();
  t.portalCall('get_profile', { Authorization: 'Bearer T' }, 200, '{"js":{"status":0}}');
  t.window.stb = { player: { channels: [{ id: 1, name: 'Old' }] } };
  t.portalCall('get_all_channels', { Authorization: 'Bearer T' }, 200, '{"js":{"data":[{"id":1,"name":"Old"}]}}');
  const reload = () => t.window.stbNative.onmessage({ data: JSON.stringify({sessionId:'s1', type:'portal.reload_catalog'}) });
  const before = t.xhrs.length;
  for (let i=0; i<10; i++) reload();
  assert.strictEqual(t.xhrs.length, before+1, 'catalog reload is single flight');
  t.xhrs.at(-1).respond(500, 'error');
  assert.strictEqual(t.window.stb.player.channels[0].name, 'Old', 'failed snapshot leaves cache intact');
  reload();
  t.xhrs.at(-1).respond(200, '{"js":{"data":[{"id":2,"name":"New"}]}}');
  assert.strictEqual(t.window.stb.player.channels[0].name, 'New');
  assert.strictEqual(t.sent.filter(m => m.type === 'channels.data').at(-1).payload.channels[0].name, 'New');
  assert.ok(t.xhrs.every(x => !x.url.includes('action=handshake')), 'catalog reload does not reauthenticate');
}
process.stdout.write('catalog reload checks passed\n');
