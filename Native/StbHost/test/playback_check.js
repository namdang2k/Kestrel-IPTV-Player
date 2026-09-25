// node playback_check.js ../src/main/assets/stb_facade.js
const assert = require('assert');
const source = require('fs').readFileSync(process.argv[2], 'utf8');
const sent = [], events = [];
const window = {
  __stbSnapshot: { sessionId: 'playback-test' },
  stbNative: { postMessage: text => sent.push(JSON.parse(text)) },
  dispatchEvent: event => events.push(event.detail),
};
const CustomEvent = class { constructor(type, options) { this.detail = options.detail; } };
new Function('window', 'CustomEvent', source)(window, CustomEvent);
const g = window.gSTB;
g.Play('ffmpeg https://stream.invalid/live.m3u8?token=example');
g.Pause(); g.Continue(); g.Stop(); g.Play(null);
assert.deepStrictEqual(sent.map(x => x.type), [
  'bridge.hello', 'player.play', 'player.pause', 'player.continue', 'player.stop', 'player.stop',
]);
assert.strictEqual(sent[1].payload.command, 'ffmpeg https://stream.invalid/live.m3u8?token=example');
assert.strictEqual(g.SetViewport(960, 540, 192, 108), 0);
assert.deepStrictEqual(sent[sent.length - 1].payload, { width: 960, height: 540, x: 192, y: 108 });
g.SetViewportEx(1920, 1080, 0, 0, 0, 0, 1920, 1080);
assert.deepStrictEqual(sent[sent.length - 1].payload, { width: 1920, height: 1080, x: 0, y: 0 });
const before = sent.length;
g.SetViewport(0, 1080, 0, 0);
g.SetViewport(NaN, 1080, 0, 0);
g.SetViewport(1920, 1080, Infinity, 0);
assert.strictEqual(sent.length, before);
assert.strictEqual(sent.every(x => x.sessionId === 'playback-test' && x.version === 1), true);
window.stbNative.onmessage({ data: JSON.stringify({ sessionId: 'old', type: 'player.state', payload: { state: 'playing' } }) });
assert.strictEqual(events.length, 0);
window.stbNative.onmessage({ data: JSON.stringify({ sessionId: 'playback-test', type: 'player.state', payload: { state: 'paused' } }) });
assert.strictEqual(events[0].payload.state, 'paused');
window.stb = { player: { channels: [
  { id: '1', number: 1, name: 'VTV', tv_genre_id: 'sport', tags: ['HD', { title: 'Bóng đá' }] },
  { id: '2', number: 2, name: 'HTV', tv_genre_id: 'music', tags: 'Live, Music' },
], fav_channels_ids: [] } };
window.module = { tv: { genres: [{ id: 'sport', title: 'Thể thao' }, { id: 'music', title: 'Âm nhạc' }] } };
window.__stbPublishChannels();
assert.deepStrictEqual(sent[sent.length - 1].payload.channels[0].tags, ['HD', 'Bóng đá']);
assert.deepStrictEqual(sent[sent.length - 1].payload.channels[1].tags, ['Live', 'Music']);
const unchanged = sent.length;
window.__stbPublishChannels();
assert.strictEqual(sent.length, unchanged);
window.module.tv.genres[0].title = 'Sports';
window.__stbPublishChannels();
assert.strictEqual(sent.length, unchanged + 1);
window.stb.player.channels[0].tags = ['Football'];
window.__stbPublishChannels();
assert.strictEqual(sent.length, unchanged + 2);
console.log('playback facade checks passed');
