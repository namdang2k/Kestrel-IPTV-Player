// Contract tests with a deterministic XHR/event loop and shared storage across portal loads.
const fs = require('fs'), assert = require('assert');
const source = fs.readFileSync(process.argv[2], 'utf8');
const store = new Map();
const storage = {
  getItem: k => store.get(k) || null, setItem: (k, v) => store.set(k, String(v)),
  removeItem: k => store.delete(k), key: i => [...store.keys()][i], get length() { return store.size; }
};
function load(operation, force = true, fingerprint = 'account-a') {
  const sent = [], timers = [];
  class XHR {
    constructor() { this.listeners = {}; this.responseType = ''; this.readyState = 0; this.status = 0; }
    open(method, url) { this.url = url; }
    send() { this.network = true; }
    abort() {}
    setRequestHeader() {}
    getResponseHeader() { return this.retryAfter || null; }
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
    removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter(x => x !== fn); }
    dispatchEvent(e) {
      for (const fn of this.listeners[e.type] || []) fn.call(this);
      if (this['on' + e.type]) this['on' + e.type](e);
    }
    respond(status, body) { this.status = status; this.responseText = body; this.readyState = 4; this.dispatchEvent({type:'readystatechange'}); }
  }
  const w = {
    __stbSnapshot: { sessionId: operation + '-attempt', operationId: operation, forceLoginPortal: force },
    __stbCacheKey: fingerprint, localStorage: storage, XMLHttpRequest: XHR,
    location: { href: 'http://p/c/', origin: 'http://p' }, Event: class { constructor(type) { this.type = type; } },
    setTimeout: fn => timers.push(fn), dispatchEvent() {},
    stbNative: { postMessage: s => sent.push(JSON.parse(s)) }
  };
  new Function('window', 'console', source)(w, {log(){},warn(){},error(){}});
  function request(action, type = 'stb') {
    const xhr = new w.XMLHttpRequest();
    xhr.open('GET', '/server/load.php?type=' + type + '&action=' + action);
    xhr.send(); return xhr;
  }
  function authenticate() {
    const handshake = request('handshake');
    assert.ok(handshake.network, 'handshake is never cached');
    handshake.respond(200, '{"js":{"token":"T"}}');
    const profile = request('get_profile');
    assert.ok(profile.network, 'profile is never cached');
    profile.respond(200, '{"js":{"status":0}}');
  }
  return {w, request, authenticate, sent, flush: () => { while (timers.length) timers.shift()(); }};
}
const catalog = '{"js":{"data":[{"id":1,"name":"One"}]}}';
let t = load('one'); t.authenticate();
t.request('get_all_channels', 'itv').respond(200, catalog);
const generation = storage.getItem('stb-content-v1:account-a:generation');
t = load('one'); t.authenticate(); // same operation/retry does not invalidate again
assert.strictEqual(storage.getItem('stb-content-v1:account-a:generation'), generation);
let x = t.request('get_all_channels', 'itv'); t.flush();
assert.ok(!x.network); assert.strictEqual(x.responseText, catalog);
t = load('two', false);
x = t.request('get_all_channels', 'itv'); assert.ok(x.network, 'no cache before acceptance');
t.authenticate(); x = t.request('get_all_channels', 'itv'); t.flush(); assert.ok(!x.network);
// Cancelling a synthetic response must not run the portal callback.
x = t.request('get_all_channels', 'itv'); let called = false;
x.onreadystatechange = () => { called = true; }; x.abort(); t.flush(); assert.ok(!called);
// Fingerprint change cannot read the old account's cache, even with Force Login off.
t = load('three', false, 'account-b'); t.authenticate();
assert.ok(t.request('get_all_channels', 'itv').network);
// Invalidate even when physical deletion fails.
const remove = storage.removeItem; storage.removeItem = () => { throw Error('disk'); };
t = load('four'); t.authenticate();
assert.ok(t.request('get_all_channels', 'itv').network);
storage.removeItem = remove;
// A superseded operation cannot repopulate cache.
const old = t.request('get_all_channels', 'itv');
const newer = load('five'); newer.authenticate();
old.respond(200, catalog);
assert.ok(newer.request('get_all_channels', 'itv').network);
// Expired cache never extends its TTL on a cache hit.
newer.request('get_all_channels', 'itv').respond(200, catalog);
const key = [...store.keys()].find(k => k.includes('data:five:'));
const entry = JSON.parse(store.get(key)); entry.fetchedAt = Date.now() - 3600001; store.set(key, JSON.stringify(entry));
assert.ok(newer.request('get_all_channels', 'itv').network);
for (const blocked of [true, 1, '1']) {
  t = load('reject-' + blocked);
  t.request('get_profile').respond(503, JSON.stringify({js:{blocked}}));
  assert.strictEqual(t.sent.find(m => m.type === 'portal.failure').payload.kind, 'PROFILE_REJECTED');
}
t = load('message');
t.request('get_profile').respond(200, '{"js":{"status":0,"block_msg":"Notice only","blocked":"0"}}');
assert.ok(!t.sent.some(m => m.type === 'portal.failure'));
for (const body of ['{"js":null}', '{"js":false}', '<html>failure</html>', '{"js":{"status":99}}']) {
  t = load('invalid'); t.request('get_profile').respond(200, body);
  assert.strictEqual(t.sent.find(m => m.type === 'portal.failure').payload.kind, 'INVALID_RESPONSE');
}
t = load('rate'); x = t.request('get_profile'); x.retryAfter = '12'; x.respond(429, '');
assert.strictEqual(t.sent.find(m => m.type === 'portal.failure').payload.retryAfter, 12);
process.stdout.write('connect/cache contract checks passed\n');
