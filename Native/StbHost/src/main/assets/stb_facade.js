// STB facade, installed by StbHost at document start (before portal scripts).
// window.__stbSnapshot is prepended by native: {sessionId, mac, serialNumber, model, sendDeviceId, deviceId, deviceId2, signature}.
// Getters answer synchronously from the snapshot; commands go to native as JSON messages.
(function () {
  'use strict';
  if (window.gSTB) return;

  var snap = window.__stbSnapshot || {};
  delete window.__stbSnapshot;
  var native = window.stbNative;
  var nextRequestId = 1;
  var volume = 100;
  var reported = {};

  function send(type, payload) {
    if (!native) return;
    native.postMessage(JSON.stringify({
      version: 1,
      sessionId: snap.sessionId,
      requestId: String(nextRequestId++),
      type: type,
      payload: payload || {}
    }));
  }

  function unsupported(name) {
    if (reported[name]) return;
    reported[name] = true;
    console.warn('[stb] unsupported API: ' + name);
    send('bridge.unsupported', { name: name });
  }

  // GetUID as StbEmu 2.0.23 computes it (Docs/Reference/StbEmu-2.0.23.01/README.md). Profile values for
  // deviceId2/signature win when set; otherwise they are derived from deviceId. Must be synchronous, hence no WebCrypto.
  function getUid(argc, a, b) {
    var id = snap.deviceId || '';
    a = a == null ? '' : String(a);
    b = b == null ? '' : String(b);
    if (!snap.sendDeviceId) return '';
    if (argc === 0) return id;
    if (argc >= 3) return '';
    if (argc === 1 || b === '') return snap.signature || hmacB64(a || id, id);
    if (snap.deviceId2) return snap.deviceId2;
    if (a === b) return hex(sha256(utf8(hmacB64(a, b)))).toUpperCase();
    return a === 'device_id' ? id : '';
  }

  function utf8(text) { return Array.prototype.slice.call(new TextEncoder().encode(text)); }
  function hex(bytes) { return bytes.map(function (x) { return (x + 256).toString(16).slice(1); }).join(''); }

  // Android Base64.DEFAULT: standard alphabet, trailing newline (a 32-byte MAC never reaches the 76-char wrap).
  function hmacB64(key, data) {
    var k = utf8(key);
    if (k.length > 64) k = sha256(k);
    var ipad = [], opad = [];
    for (var i = 0; i < 64; i++) { ipad[i] = (k[i] || 0) ^ 0x36; opad[i] = (k[i] || 0) ^ 0x5c; }
    var mac = sha256(opad.concat(sha256(ipad.concat(utf8(data)))));
    return btoa(String.fromCharCode.apply(null, mac)) + '\n';
  }

  var K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  // bytes in, 32 bytes out
  function sha256(bytes) {
    var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var m = bytes.concat([0x80]);
    while (m.length % 64 !== 56) m.push(0);
    var hi = Math.floor(bytes.length / 0x20000000), lo = bytes.length * 8 >>> 0; // 64-bit big-endian bit length
    m.push(hi >>> 24, hi >>> 16 & 255, hi >>> 8 & 255, hi & 255, lo >>> 24, lo >>> 16 & 255, lo >>> 8 & 255, lo & 255);
    var w = [];
    for (var off = 0; off < m.length; off += 64) {
      for (var t = 0; t < 64; t++) {
        if (t < 16) {
          w[t] = m[off + t * 4] << 24 | m[off + t * 4 + 1] << 16 | m[off + t * 4 + 2] << 8 | m[off + t * 4 + 3];
        } else {
          var x = w[t - 15], y = w[t - 2];
          var s0 = (x >>> 7 | x << 25) ^ (x >>> 18 | x << 14) ^ x >>> 3;
          var s1 = (y >>> 17 | y << 15) ^ (y >>> 19 | y << 13) ^ y >>> 10;
          w[t] = w[t - 16] + s0 + w[t - 7] + s1 | 0;
        }
      }
      var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
      for (t = 0; t < 64; t++) {
        var S1 = (e >>> 6 | e << 26) ^ (e >>> 11 | e << 21) ^ (e >>> 25 | e << 7);
        var t1 = k + S1 + (e & f ^ ~e & g) + K[t] + w[t] | 0;
        var S0 = (a >>> 2 | a << 30) ^ (a >>> 13 | a << 19) ^ (a >>> 22 | a << 10);
        var t2 = S0 + (a & b ^ a & c ^ b & c) | 0;
        k = g; g = f; f = e; e = d + t1 | 0; d = c; c = b; b = a; a = t1 + t2 | 0;
      }
      h[0] = h[0] + a | 0; h[1] = h[1] + b | 0; h[2] = h[2] + c | 0; h[3] = h[3] + d | 0;
      h[4] = h[4] + e | 0; h[5] = h[5] + f | 0; h[6] = h[6] + g | 0; h[7] = h[7] + k | 0;
    }
    var out = [];
    for (var j = 0; j < 8; j++) out.push(h[j] >>> 24 & 255, h[j] >>> 16 & 255, h[j] >>> 8 & 255, h[j] & 255);
    return out;
  }

  // Java String.getBytes(ISO_8859_1): one byte per char, '?' for anything above U+00FF.
  function latin1(text) {
    var out = [];
    for (var i = 0; i < text.length; i++) out.push(text.charCodeAt(i) < 256 ? text.charCodeAt(i) : 63);
    return out;
  }

  // bytes in, 20 bytes out
  function sha1(bytes) {
    var h = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0];
    var m = bytes.concat([0x80]);
    while (m.length % 64 !== 56) m.push(0);
    var hi = Math.floor(bytes.length / 0x20000000), lo = bytes.length * 8 >>> 0;
    m.push(hi >>> 24, hi >>> 16 & 255, hi >>> 8 & 255, hi & 255, lo >>> 24, lo >>> 16 & 255, lo >>> 8 & 255, lo & 255);
    var w = [];
    for (var off = 0; off < m.length; off += 64) {
      for (var t = 0; t < 80; t++) {
        if (t < 16) w[t] = m[off + t * 4] << 24 | m[off + t * 4 + 1] << 16 | m[off + t * 4 + 2] << 8 | m[off + t * 4 + 3];
        else { var x = w[t - 3] ^ w[t - 8] ^ w[t - 14] ^ w[t - 16]; w[t] = x << 1 | x >>> 31; }
      }
      var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
      for (t = 0; t < 80; t++) {
        var f = t < 20 ? (b & c | ~b & d) + 0x5a827999 : t < 40 ? (b ^ c ^ d) + 0x6ed9eba1
          : t < 60 ? (b & c | b & d | c & d) + 0x8f1bbcdc : (b ^ c ^ d) + 0xca62c1d6;
        var tmp = (a << 5 | a >>> 27) + f + e + w[t] | 0;
        e = d; d = c; c = b << 30 | b >>> 2; b = a; a = tmp;
      }
      h[0] = h[0] + a | 0; h[1] = h[1] + b | 0; h[2] = h[2] + c | 0; h[3] = h[3] + d | 0; h[4] = h[4] + e | 0;
    }
    var out = [];
    for (var j = 0; j < 5; j++) out.push(h[j] >>> 24 & 255, h[j] >>> 16 & 255, h[j] >>> 8 & 255, h[j] & 255);
    return out;
  }

  // Key and nesting from StbEmu 2.0.23; the portal sends the result as handshake `prehash` (undefined → HTTP 500).
  var HASH_V1_KEY = 'dA0j6HpVFcMgNjUBDr0QhwTBIzLHDIrynuQy4XNJ';
  function hashV1(a, b) {
    var h = function (s) { return hex(sha1(latin1(s))); };
    return h(h((a == null ? '' : h(String(a))) + HASH_V1_KEY) + (b == null ? '' : String(b)));
  }

  // Firmware env for RDir('getenv x') / RDir('setenv x value'). Kept in localStorage so it survives ExecAction('reboot')
  // (a page reload): the portal sets ntpurl, "reboots" and only continues once it reads the value back.
  // Native wipes web storage on every portal open but keeps env per profile (env.save) and seeds it back here, so only a
  // profile's first connect pays the extra reload.
  var ENV_KEY = '__stbEnv';
  var envSeed = window.__stbEnvSeed || {};
  delete window.__stbEnvSeed;
  function loadEnv() {
    var stored = {}, merged = {}, k;
    try { stored = JSON.parse(window.localStorage.getItem(ENV_KEY)) || {}; } catch (e) { /* empty */ }
    for (k in envSeed) if (envSeed.hasOwnProperty(k)) merged[k] = String(envSeed[k]);
    for (k in stored) if (stored.hasOwnProperty(k)) merged[k] = stored[k];
    return merged;
  }
  var env = loadEnv();
  function rdir(command) {
    var m = /^\s*(getenv|setenv)\s+(\S+)\s*(.*)$/.exec(String(command));
    if (!m) return '';
    if (m[1] === 'getenv') return env.hasOwnProperty(m[2]) ? env[m[2]] : '';
    env[m[2]] = m[3].trim().replace(/^"(.*)"$/, '$1');
    try { window.localStorage.setItem(ENV_KEY, JSON.stringify(env)); } catch (e) { /* storage blocked: env lasts this page only */ }
    send('env.save', env);
    return '';
  }

  // ChannelSource: the only place that knows where a Stalker portal keeps its channel data (pinned in plan Task 1).
  // Paths are dot paths from window; values are read, never modified. No API calls.
  // Pinned from a Stalker 5.6.10 portal (see Native/StbHost/test/portal_fixture.js). Another portal build → change only this.
  var CHANNEL_SOURCE = window.__stbChannelSource || {
    channels: 'stb.player.channels',          // itv/get_all_channels
    genres: 'module.tv.genres',               // itv/get_genres
    favIds: 'stb.player.fav_channels_ids',    // itv/get_fav_ids
    play: 'module.tv._play_now',              // what the portal's TV list calls on OK
    fields: { id: 'id', number: 'number', name: 'name', genreId: 'tv_genre_id', logo: 'logo', archive: 'archive' },
    // Logo file names are relative to <origin>/<portal_path>/<logoDir> (Stalker's player.js builds the same URL).
    portalPath: 'stb.portal_path',
    logoDir: 'misc/logos/320/',
    genreFields: { id: 'id', title: 'title' },
  };
  var ALL_GENRE = '*'; // Stalker's pseudo-genre "All"; the native list has its own "all" tab
  var MAX_NAME = 80;
  var lastChannelsSig = '';
  function at(path) {
    return String(path).split('.').reduce(function (o, k) { return o == null ? o : o[k]; }, window);
  }
  function readChannels() {
    var s = CHANNEL_SOURCE;
    if (!s) return null;
    try {
      var list = at(s.channels), genres = at(s.genres) || [], favs = at(s.favIds) || [];
      if (!Array.isArray(list) || (!list.length && !catalogValidated)) return null;
      if (!list.every(function (c) { return c && c.id != null && typeof c.name === 'string'; })) return null;
      return {
        channels: list.map(function (c) {
          var channel = { id: String(c[s.fields.id]), number: c[s.fields.number] == null ? null : Number(c[s.fields.number]),
                   name: String(c[s.fields.name] || '').slice(0, MAX_NAME), genreId: String(c[s.fields.genreId] || '') };
          var logo = c[s.fields.logo];
          if (logo) channel.logo = String(logo).slice(0, MAX_NAME);
          if (Number(c[s.fields.archive]) > 0) channel.archive = 1;
          var cmd = c[s.fields.cmd || 'cmd'];
          if (cmd) channel.cmd = String(cmd).slice(0, 512); // native keeps it for the reconnect fast path (channels.fastplay)
          var tags = c[s.fields.tags || 'tags'];
          if (typeof tags === 'string') tags = tags.split(',');
          if (Array.isArray(tags)) {
            channel.tags = tags.slice(0, 32).map(function (tag) {
              return String(typeof tag === 'string' ? tag : (tag && (tag.title || tag.name)) || '').trim().slice(0, MAX_NAME);
            }).filter(Boolean);
          }
          return channel;
        }),
        genres: genres.filter(function (g) { return String(g[s.genreFields.id]) !== ALL_GENRE; })
          .map(function (g) { return { id: String(g[s.genreFields.id]), title: String(g[s.genreFields.title] || '') }; }),
        favIds: favs.map(String),
        logoBase: logoBase(),
      };
    } catch (e) {
      console.warn('[stb] channel source unreadable: ' + e);
      return null;
    }
  }
  function publishChannels() {
    var data = readChannels();
    if (!data || (window.XMLHttpRequest && (!authorized || !catalogValidated))) return;
    // Metadata updates (including genre titles/tags) must refresh the native search index too.
    var sig = JSON.stringify(data);
    if (sig === lastChannelsSig) return;
    lastChannelsSig = sig;
    send('channels.data', data);
  }
  function playChannel(id) {
    var s = CHANNEL_SOURCE, list = s && at(s.channels);
    var ch = list && Array.prototype.find.call(list, function (c) { return String(c[s.fields.id]) === String(id); });
    var play = s && at(s.play);
    if (!ch || typeof play !== 'function') { send('channels.error', { code: 'CHANNEL_NOT_FOUND', id: String(id) }); return false; }
    var ownerPath = String(s.play).split('.').slice(0, -1).join('.');
    play.call(ownerPath ? at(ownerPath) : window, ch);
    return true;
  }
  function logoBase() {
    var path = at(CHANNEL_SOURCE.portalPath), loc = window.location;
    return path && loc && loc.origin ? loc.origin + '/' + path + '/' + CHANNEL_SOURCE.logoDir : '';
  }

  window.__stbPublishChannels = publishChannels; // test hook

  var api = {
    GetDeviceMacAddress: function () { return snap.mac || ''; },
    GetDeviceSerialNumber: function () { return snap.serialNumber || ''; },
    GetDeviceModel: function () { return snap.model || ''; },
    GetUID: function (a, b) { return getUid(arguments.length, a, b); },
    GetHashVersion1: hashV1,
    // Device/firmware identity sent in get_profile. ponytail: fixed MAG250 values; make them profile fields when a portal checks other models.
    GetDeviceModelExt: function () { return snap.model || ''; },
    GetDeviceVersionHardware: function () { return '1.7-BD-00'; },
    Version: function () { return 'JS API version: 343; STB API version: 146; Player Engine version: 0x566'; },
    // Undefined reads as "no network": the portal stops after get_localization. The phone has one link, report it for both.
    GetLanLinkStatus: function () { return navigator.onLine; },
    GetWifiLinkStatus: function () { return navigator.onLine; },
    // Value-returning start-up calls seen from a Stalker portal; StbEmu answers these with ""/false, never undefined.
    // ponytail: LoadUserData is empty (no persisted user data); fill in when a portal needs it.
    RDir: rdir,
    ExecAction: function (action) {
      if (String(action).trim() !== 'reboot') return false;
      setTimeout(function () { window.location.reload(); }, 0); // after the portal's own `return`
      return true;
    },
    LoadUserData: function () { return ''; },
    IsFileExist: function () { return false; },
    Play: function (command) {
      if (command == null) { send('player.stop'); return; }
      send('player.play', { command: String(command) });
    },
    Stop: function () { send('player.stop'); },
    Pause: function () { send('player.pause'); },
    Continue: function () { send('player.continue'); },
    // StbEmu s13 -> r03/q03: size first, then origin. pu.updateVideoSize uses a 1920x1080 plane.
    SetViewport: function (width, height, x, y) {
      var values = [width, height, x, y].map(Number);
      if (!values.every(Number.isFinite) || values[0] <= 0 || values[1] <= 0) return 0;
      send('video.viewport', { width: values[0], height: values[1], x: values[2], y: values[3] });
      return 0;
    },
    SetViewportEx: function (width, height, x, y) { return api.SetViewport(width, height, x, y); },
    GetVolume: function () { return volume; },
    SetVolume: function (value) {
      volume = Math.max(0, Math.min(100, Number(value) || 0));
      send('player.volume', { volume: volume });
    }
  };

  // ponytail: unknown members return a no-op that reports its name, so the portal keeps running while we trace
  // which MAG APIs it needs; this also defeats `if (gSTB.Foo)` feature checks — replace with explicit stubs once traced.
  var facade = new Proxy(api, {
    get: function (target, name) {
      if (name in target || typeof name !== 'string') return target[name];
      if (name === 'prototype') return undefined; // a function here sends Stalker's check_graphic_res to innerHeight
      return function () { unsupported(name); };
    }
  });

  // Stalker lays out for a TV (screen.height 480/576/720/1080) and otherwise "reboots" into graphicres 720 forever.
  // Present a 1280x720 screen and let WebView (wide viewport on) scale a viewport exactly 720 CSS px tall to the phone.
  var TV_W = 1280, TV_H = 720;
  if (window.screen) {
    [['width', TV_W], ['availWidth', TV_W], ['height', TV_H], ['availHeight', TV_H]].forEach(function (p) {
      Object.defineProperty(window.screen, p[0], { get: function () { return p[1]; }, configurable: true });
    });
  }
  var doc = window.document;
  if (doc) {
    var meta = doc.createElement('meta');
    meta.setAttribute('name', 'viewport');
    var fit = function () {
      var ratio = window.innerHeight > 0 ? window.innerWidth / window.innerHeight : 0; // no size yet at document start
      meta.setAttribute('content', 'width=' + Math.max(TV_W, Math.ceil(TV_H * ratio)));
    };
    fit();
    doc.documentElement.appendChild(meta);
    doc.addEventListener('DOMContentLoaded', function () { if (doc.head) doc.head.appendChild(meta); });
    window.addEventListener('resize', fit); // rotation
  }

  window.gSTB = facade;
  window.stb = facade;

  if (!native) {
    console.error('[stb] native bridge missing');
    return;
  }

  // Reconnect fast path: a reconnect only needs a fresh link for the pending channel, not the portal's whole channel list
  // (16k+ channels, 10-16 s). Once get_profile has authorized the new token, ask create_link directly with the URL and
  // headers the portal itself uses, then Play() the answer exactly like the portal would. Any failure → native falls
  // back to channels.play after the list has loaded.
  var portalApi = { url: '', headers: {} }, authorized = false, fastPlay = null;
  var sessionVersion = 0, rejectedVersion = -1, catalogValidated = false;
  var catalogRequest = null, catalogReloading = false;
  function reportFailure(kind, action, status) {
    send('portal.failure', { kind: kind, stage: action, httpStatus: status, sessionVersion: sessionVersion });
  }
  function sameOrigin(url) {
    try { return new URL(url, window.location.href).origin === window.location.origin; }
    catch (e) { return false; }
  }
  function classify(status, text, action, sentAuth) {
    var creating = action === 'handshake' || action === 'get_profile';
    if (status === 401 || (status === 200 && text.trim() === 'Authorization failed.'))
      return creating || !sentAuth ? 'AUTHENTICATION_REJECTED' : 'SESSION_REJECTED';
    if (status === 403) return 'FORBIDDEN';
    if (status === 429) return 'RATE_LIMITED';
    if (status >= 500) return 'SERVER_UNAVAILABLE';
    if (status === 0) return 'NETWORK';
    if (status < 200 || status >= 300) return 'INVALID_RESPONSE';
    return '';
  }
  function runFastPlay() {
    if (!fastPlay || !authorized || !portalApi.url || !window.XMLHttpRequest) return;
    var request = fastPlay, version = sessionVersion, xhr = new window.XMLHttpRequest();
    fastPlay = null;
    xhr.open('GET', portalApi.url + '?type=itv&action=create_link&cmd=' + encodeURIComponent(request.cmd)
        + '&series=&forced_storage=undefined&disable_ad=0&download=0&JsHttpRequest=1-xml');
    xhr.timeout = 8000; // a hung request ends as status 0 → failed → the normal path
    for (var name in portalApi.headers) {
      if (portalApi.headers.hasOwnProperty(name)) xhr.setRequestHeader(name, portalApi.headers[name]);
    }
    xhr.addEventListener('readystatechange', function () {
      if (xhr.readyState !== 4 || version !== sessionVersion) return;
      var js = null;
      try { js = JSON.parse(xhr.responseText).js; } catch (e) { /* not JSON, e.g. "Authorization failed." */ }
      if (!authorized || xhr.status < 200 || xhr.status >= 300 || !js || !js.cmd || js.error) {
        console.warn('[stb] fast play failed: HTTP ' + xhr.status);
        send('channels.fastplay.failed', { id: request.id });
        return;
      }
      console.log('[stb] fast play ' + request.id);
      send('channels.fastplay.playing', { id: request.id }); // before Play(): native tells it from startup autoplay
      api.Play(js.cmd);
    });
    xhr.send();
  }

  function reloadCatalog() {
    if (catalogReloading) return;
    if (!authorized || !catalogRequest) { send('portal.reconnect_required'); return; }
    catalogReloading = true;
    var xhr = new window.XMLHttpRequest(), version = sessionVersion;
    xhr.open('GET', catalogRequest);
    xhr.timeout = 12000;
    Object.keys(portalApi.headers).forEach(function (key) { xhr.setRequestHeader(key, portalApi.headers[key]); });
    xhr.addEventListener('readystatechange', function () {
      if (xhr.readyState !== 4) return;
      catalogReloading = false;
      if (version !== sessionVersion || !authorized) return;
      var js;
      try { js = JSON.parse(xhr.responseText).js; } catch (e) {}
      if (xhr.status !== 200 || !js || !Array.isArray(js.data) || !js.data.every(function (c) {
        return c && c.id != null && typeof c.name === 'string';
      })) return; // observer reports typed errors; preserve the old snapshot
      var owner = at('stb.player');
      if (!owner) { reportFailure('INVALID_RESPONSE', 'get_all_channels', xhr.status); return; }
      owner.channels = js.data;
      catalogValidated = true;
      lastChannelsSig = ''; // unchanged data still completes the user's reload
      publishChannels();
    });
    xhr.send();
  }

  // Native -> page: {version, sessionId, type, payload}. Adapters map these to portal callbacks (not guessed here).
  native.onmessage = function (event) {
    var message;
    try { message = JSON.parse(event.data); } catch (e) { return; }
    if (!message || message.sessionId !== snap.sessionId) return;
    if (message.type === 'portal.reload_catalog') { reloadCatalog(); return; }
    if (message.type === 'channels.fastplay' && message.payload && message.payload.cmd) {
      fastPlay = { id: String(message.payload.id), cmd: String(message.payload.cmd) };
      runFastPlay();
      return;
    }
    if (message.type === 'channels.play' && message.payload) {
      if (playChannel(message.payload.id)) send('channels.play.accepted', { id: String(message.payload.id) });
      return;
    }
    if (message.type === 'player.volume' && message.payload) volume = message.payload.volume;
    window.dispatchEvent(new CustomEvent('stb:event', { detail: message }));
  };

  // Observe only the same-origin Stalker endpoint discovered from handshake/profile.
  // The portal remains the owner of its protocol; never replay unknown requests or guess token TTLs.
  if (window.XMLHttpRequest) {
    var xhrOpen = window.XMLHttpRequest.prototype.open;
    var xhrSetHeader = window.XMLHttpRequest.prototype.setRequestHeader;
    window.XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
      if (this.__stbAction) this.__stbHeaders[String(name).toLowerCase()] = value;
      return xhrSetHeader.apply(this, arguments);
    };
    window.XMLHttpRequest.prototype.open = function (method, url) {
      if (this.__stbListener && this.removeEventListener)
        this.removeEventListener('readystatechange', this.__stbListener);
      var absolute;
      try { absolute = new URL(url, window.location.href); } catch (e) {}
      var action = absolute && sameOrigin(absolute.href) ? absolute.searchParams.get('action') || '' : '';
      var endpoint = absolute ? absolute.origin + absolute.pathname : '';
      if (action === 'handshake' || action === 'get_profile') {
        if (portalApi.url && portalApi.url !== endpoint) action = '';
        else portalApi.url = endpoint;
      } else if (endpoint !== portalApi.url) action = '';
      if (action === 'get_all_channels') {
        catalogValidated = false;
        if (String(method).toUpperCase() === 'GET') catalogRequest = absolute.href;
      }
      this.__stbAction = action;
      this.__stbHeaders = {};
      if (action === 'handshake') {
        sessionVersion++;
        authorized = false;
        portalApi.headers = {};
        catalogValidated = false;
        catalogRequest = null;
        catalogReloading = false;
        send('portal.phase', { phase: 'AUTHENTICATING' });
      }
      var version = sessionVersion, handled = false;
      this.__stbListener = function () {
        if (this.readyState !== 4 || handled || !action || version !== sessionVersion) return;
        handled = true;
        var text = this.responseType === '' || this.responseType === 'text' ? this.responseText : '';
        var failure = classify(this.status, text, action, !!this.__stbHeaders.authorization);
        var essential = action === 'handshake' || action === 'get_profile' || action === 'get_all_channels';
        if (failure) {
          if (failure === 'SESSION_REJECTED') {
            if (rejectedVersion === version) return;
            rejectedVersion = version;
            authorized = false;
            send('portal.auth_failed', { sessionVersion: version });
          } else if (essential) {
            if (action === 'handshake' || action === 'get_profile') authorized = false;
            reportFailure(failure, action, this.status);
          }
          return;
        }
        if (!essential) return; // EPG, VOD and stream errors must not invalidate Live.
        var js;
        try { js = JSON.parse(text).js; } catch (e) {}
        if (action === 'handshake') {
          if (!js || typeof js.token !== 'string' || !js.token.trim()) reportFailure('INVALID_RESPONSE', action, this.status);
          else send('portal.phase', { phase: 'VALIDATING' });
        } else if (action === 'get_profile') {
          // This adapter requires explicit status=0; an ID never overrides a rejection.
          if (!js || (js.status !== 0 && js.status !== '0')) {
            authorized = false;
            reportFailure(js && js.status != null ? 'PROFILE_REJECTED' : 'INVALID_RESPONSE', action, this.status);
          } else if (js.block_msg || js.blocked === true || js.blocked === 1 || js.expired === true || js.expired === 1) {
            authorized = false;
            reportFailure('PROFILE_REJECTED', action, this.status);
          } else {
            authorized = true;
            portalApi.headers = Object.assign({}, this.__stbHeaders);
            send('portal.phase', { phase: 'LOADING' });
            runFastPlay();
          }
        } else {
          if (!js || !Array.isArray(js.data) || !js.data.every(function (c) {
            return c && c.id != null && typeof c.name === 'string';
          })) reportFailure('INVALID_RESPONSE', action, this.status);
          else {
            catalogValidated = true;
            // Publish after the portal's own callback has assigned its channel array.
            if (window.setTimeout) window.setTimeout(publishChannels, 0);
          }
        }
      };
      this.addEventListener('readystatechange', this.__stbListener);
      return xhrOpen.apply(this, arguments);
    };
  }

  send('bridge.hello'); // gives native the reply channel before the first real event
  // Portal fills its lists after start-up and on refresh. window.setInterval: absent in the Node test harness.
  // The first list gates a (re)connect's pending channel request, so poll it every second, then every 5 s.
  if (window.setInterval) {
    var firstList = window.setInterval(function () {
      publishChannels();
      if (!lastChannelsSig) return;
      window.clearInterval(firstList);
      window.setInterval(publishChannels, 5000);
    }, 1000);
  }
})();
