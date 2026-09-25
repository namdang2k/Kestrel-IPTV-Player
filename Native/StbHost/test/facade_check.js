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
process.stdout.write('facade ok\n');
