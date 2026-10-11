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


function request(t, kind, id, requestId, sessionId = 's1') {
  t.window.stbNative.onmessage({data: JSON.stringify({version: 1, sessionId,
    type: 'channels.details', payload: {kind, id, requestId}})});
}
function details(t) { return t.sent.filter(m => m.type === 'channels.details').map(m => m.payload); }
{
  const t = load();
  request(t, 'epg', '', 1);
  assert.strictEqual(t.xhrs.length, 0);
  assert.deepStrictEqual(details(t)[0].programs, []);
  request(t, 'epg', '7', 2, 'old-session');
  assert.strictEqual(details(t).length, 1);
}
{
  const t = load();
  t.portalCall('get_profile', {Authorization: 'Bearer T'}, 200, '{"js":{"status":0,"expire_billing_date":"2027-10-06"}}');
  request(t, 'epg', '7', 10);
  request(t, 'epg', '8', 11);
  assert.ok(t.xhrs[1].url.includes('action=get_short_epg&ch_id=7'));
  assert.strictEqual(t.xhrs[1].headers.authorization, 'Bearer T');
  t.xhrs[2].respond(200, '{"js":{"data":[{"name":"News","time":"10:00","time_to":"11:00","descr":"Daily news"}]}}');
  t.xhrs[1].respond(200, '{"js":[]}');
  assert.deepStrictEqual(details(t).map(p => [p.id, p.requestId]), [['8',11], ['7',10]]);
  assert.strictEqual(details(t)[0].programs[0].description, 'Daily news');
  request(t, 'info', '', 12);
  t.xhrs[3].respond(200, '{"js":{"phone":"2028-01-01"}}');
  assert.strictEqual(details(t)[2].expiry, '2028-01-01');
  assert.strictEqual(details(t)[2].mac, 'm');
}
for (const [status, body] of [[0, ''], [500, ''], [401, ''], [200, 'invalid'], [200, '{"js":{}}']]) {
  const t = load();
  t.portalCall('get_profile', {Authorization: 'Bearer T'}, 200, '{"js":{"status":0}}');
  request(t, 'epg', '7', 1);
  t.xhrs[1].respond(status, body);
  assert.strictEqual(details(t)[0].error, true);
  assert.ok(!t.sent.some(m => m.type === 'portal.auth_failed' || m.type === 'portal.failure'));
}
process.stdout.write('portal details checks passed\n');
