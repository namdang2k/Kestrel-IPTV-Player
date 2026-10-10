# Native Channel List Implementation Plan

## Hiện trạng 2026-10-10

Đối chiếu code hiện tại; dùng cùng [README](../../../README.md) trước khi sửa luồng này.

Đã triển khai danh sách kênh native.

- Facade trong WebView cung cấp channels.data; StbHost xử lý catalog và ChannelPanel/ChannelFilter quản lý hiển thị, tìm kiếm, nhóm và favorites.
- Chọn kênh quay về facade bằng channels.play. Catalog lớn không chuyển qua C#; Unity điều phối sự kiện chọn kênh/quảng cáo, không tải danh sách qua Stalker API.
- Chọn kênh đi qua ui.channel-selected hoặc ui.channel-selected.reconnect. AppFlow quyết định interstitial và gọi ContinueChannelSelection; không tạo đường chọn kênh bỏ qua ads/playback gate.
- Portal unavailable: reconnect khi cần chọn kênh, giữ stream hiện tại và xử lý yêu cầu đang chờ theo session. Không đánh đồng dữ liệu kênh sẵn sàng với player READY.
- Callback/session cũ phải bị loại khi Home, reconnect hoặc chọn kênh mới.

Điểm sửa chính: StbHost.java, ChannelPanel.java, ChannelFilter.java, stb_facade.js và AppFlow.cs. Chạy kiểm chứng Java/facade tương ứng, build copyToUnity khi đổi native và smoke đổi kênh/reconnect/ads trên thiết bị. Chỉ commit khi người dùng yêu cầu; không chạy lại các bước dựng mới bên dưới.

## Bản kế hoạch gốc và lịch sử triển khai

> Phần dưới giữ để tra cứu. Khi có khác biệt, dùng hiện trạng ở đầu tài liệu và README; không thực thi lại checklist hoặc chỉ dẫn công cụ/agent cũ mặc định.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native (Java RecyclerView) channel list over the portal WebView with search, genre tabs and favourites; tapping a channel makes the portal play it.

**Architecture:** The facade reads channels/genres/favourites the portal already holds in page memory (one `ChannelSource` descriptor knows the portal's internal names) and posts them to Java as `channels.data`; Java keeps `channels.*` off the C# path, filters with a pure `ChannelFilter`, shows `ChannelPanel`, and posts `channels.play {id}` back; the facade calls the portal's own channel-switch function.

**Tech Stack:** Java 17, AndroidX RecyclerView, Media3/webkit (existing), JUnit 4 (JVM unit tests), Node 22 (`facade_check.js`), CDP over `adb forward` for device checks.

**Spec:** `Docs/superpowers/specs/2026-09-25-native-channel-list-design.md`

## Global Constraints
- No Stalker API calls from the app; data comes only from the portal page's memory, playback only through the portal's own function.
- `channels.*` messages never reach C# (C# rejects > 16 KB; 16.000 channels ≈ 1 MB).
- 4 fields per channel: `id, number, name, genreId`; names cut to 80 chars.
- Search ignores case and Vietnamese diacritics ("thoi su" matches "Thời sự"; đ → d).
- No readable source → 📺 hidden, nothing else changes.
- Dependencies pinned in both `Native/StbHost/build.gradle` and `Assets/Plugins/Android/mainTemplate.gradle`, recorded in `Docs/dependency-lock.md`.
- Build AAR: from `Native/StbHost`, `JAVA_HOME=<Unity>/OpenJDK`, `"$A/OpenJDK/bin/java" -cp "$A/Tools/gradle/lib/gradle-gradle-cli-main-9.3.1.jar" org.gradle.launcher.GradleMain <task>` (`A` = `C:/Program Files/Unity/Hub/Editor/6000.6.3f1/Editor/Data/PlaybackEngines/AndroidPlayer`).
- Commit only when the user asks (user rule); commit steps below are "stage + ask".

## Review Focus
- Portal still loading when 📺 is pressed (no data yet) → 📺 stays hidden until the first `channels.data`; test in Task 2 (no data → no message) and Task 4 (button GONE until data).
- Same data re-read every 5 s → no resend and no panel reset while the user scrolls; test in Task 2 (unchanged signature → one message).
- Genre tab + search together → intersection, not either; test in Task 3.
- Channel id arrives as number from portal but string from native (or vice versa) → play still finds it; test in Task 2 (`play('12')` finds id `12`).
- Search text with only spaces → shows the whole tab, not "no channels"; test in Task 3.

---

### Task 1: Pin the portal's channel data contract

Needs a portal that currently loads (not 403). Output is data, not code: the values of `SOURCE` used by Task 2 and a fake-portal fixture that mirrors the real shape.

**Files:**
- Create: `Native/StbHost/test/portal_fixture.js`
- Modify: `Docs/superpowers/specs/2026-09-25-native-channel-list-design.md` (§3.1: replace the "xác minh ở Task 1" line with the pinned names)

**Interfaces:**
- Produces: `SOURCE` descriptor values (paths are dot paths from `window`):
  `{ channels: '<path to array>', genres: '<path to array>', favIds: '<path to array of ids>', play: '<path to function(channelObject)>', fields: { id, number, name, genreId }, genreFields: { id, title } }`
- Produces: `portal_fixture.js` exporting `function makePortal()` that returns a `window`-like object with those paths filled by 3 channels, 2 genres, 1 favourite, and a play function recording its argument.

- [ ] **Step 1: Open a working portal on the phone (Development build), forward DevTools**

```bash
ADB="$A/SDK/platform-tools/adb.exe"; P=$("$ADB" shell pidof com.namdc.StbEmu | tr -d '\r\n')
"$ADB" forward tcp:9222 localabstract:webview_devtools_remote_$P
```

- [ ] **Step 2: Save the TV/player sources and find where `get_all_channels`, `get_genres`, `get_all_fav_channels`/`get_fav_ids` results are stored and which function switches channel**

```bash
node scratchpad/cdp_src.js scratchpad/portal tv.js player.js xpcom.common.js
grep -n "get_all_channels\|get_genres\|get_fav_ids\|get_all_fav_channels" scratchpad/portal/*.js
```
Read each callback: the `result`/`result.data` assignment target is the path. For play, find the function the TV list calls on OK (the one that ends in `stb.player.play(...)`) and note its path and argument (channel object).

- [ ] **Step 3: Confirm on the live page that the paths hold data**

Run in `cdp.js` (Runtime.evaluate) with the found paths, e.g.:
```js
JSON.stringify({ n: <channels path>.length, keys: Object.keys(<channels path>[0]), g: <genres path>.length, f: <favIds path>.length, play: typeof <play path> })
```
Expected: `n` > 0, keys include the id/number/name/genre fields, `play` = `"function"`.

- [ ] **Step 4: Write the fixture with the confirmed shape**

```js
// Native/StbHost/test/portal_fixture.js — shape pinned in Task 1 from <portal host>, <date>.
// Only the structure is real; values are made up.
module.exports.makePortal = function makePortal() {
  const played = [];
  const w = {};
  // Fill each SOURCE path below with the confirmed nesting, e.g. if channels live at stb.player.channels:
  //   w.stb = { player: { channels: [...] } };
  // 3 channels (ids 1, 12, 13; channel 12 name 'Thời sự 24/7', genre 'g1'), 2 genres ('g1' 'News', 'g2' 'Sports'),
  // favourite ids [13], and the play function: (ch) => played.push(ch).
  w.__played = played;
  return w;
};
module.exports.SOURCE = { /* the confirmed descriptor from Step 2/3 */ };
```
Replace both comment blocks with the concrete objects confirmed in Step 3 before finishing the task; the file must `require()` cleanly and `makePortal()` must return data at every `SOURCE` path.

- [ ] **Step 5: Update spec §3.1 with the pinned names; stage both files and ask the user whether to commit**

---

### Task 2: Facade `ChannelSource` + `channels.*` messaging

**Files:**
- Modify: `Native/StbHost/src/main/assets/stb_facade.js`
- Modify: `Native/StbHost/test/facade_check.js`

**Interfaces:**
- Consumes: `SOURCE`, `makePortal()` from `Native/StbHost/test/portal_fixture.js`.
- Produces (page → native): `{version:1, sessionId, requestId, type:'channels.data', payload:{channels:[{id:String, number:Number|null, name:String, genreId:String}], genres:[{id:String, title:String}], favIds:[String]}}` (all ids as strings).
- Consumes (native → page): `{version:1, sessionId, type:'channels.play', payload:{id:String}}`.
- Produces (page → native on failure): `{type:'channels.error', payload:{code:'CHANNEL_NOT_FOUND'}}` (a `channels.*` type so Java shows it and it never reaches C#).

- [ ] **Step 1: Write the failing test (append before the final `process.stdout.write('facade ok\n')` block)**

```js
// ChannelSource: reads the portal's in-memory channels once they exist, resends only on change, plays by id.
{
  const { makePortal, SOURCE } = require('./portal_fixture');
  const sent = [];
  const w = Object.assign(makePortal(), {
    __stbSnapshot: { sessionId: 's' }, dispatchEvent() {},
    stbNative: { postMessage: m => sent.push(JSON.parse(m)) },
    __stbChannelSource: SOURCE, // test hook: production uses the built-in descriptor
  });
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  w.__stbPublishChannels();
  w.__stbPublishChannels(); // unchanged → no second message
  const data = sent.filter(m => m.type === 'channels.data');
  assert.strictEqual(data.length, 1);
  const p = data[0].payload;
  assert.strictEqual(p.channels.length, 3);
  assert.deepStrictEqual(Object.keys(p.channels[0]).sort(), ['genreId', 'id', 'name', 'number']);
  assert.ok(p.channels.every(c => typeof c.id === 'string'));
  assert.deepStrictEqual(p.favIds, ['13']);
  assert.strictEqual(p.genres.length, 2);

  w.stbNative.onmessage({ data: JSON.stringify({ sessionId: 's', type: 'channels.play', payload: { id: '12' } }) });
  assert.strictEqual(w.__played.length, 1);
  assert.strictEqual(String(w.__played[0][SOURCE.fields.id]), '12');

  w.stbNative.onmessage({ data: JSON.stringify({ sessionId: 's', type: 'channels.play', payload: { id: '999' } }) });
  assert.strictEqual(sent.filter(m => m.type === 'channels.error').pop().payload.code, 'CHANNEL_NOT_FOUND');
}
{
  // No readable source (portal still loading / other portal) → nothing sent, no throw.
  const sent = [];
  const w = { __stbSnapshot: { sessionId: 's' }, dispatchEvent() {}, stbNative: { postMessage: m => sent.push(JSON.parse(m)) } };
  new Function('window', 'console', fs.readFileSync(process.argv[2], 'utf8'))(w, console);
  w.__stbPublishChannels();
  assert.strictEqual(sent.filter(m => m.type === 'channels.data').length, 0);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `node Native/StbHost/test/facade_check.js Native/StbHost/src/main/assets/stb_facade.js`
Expected: FAIL with `TypeError: w.__stbPublishChannels is not a function`.

- [ ] **Step 3: Implement in `stb_facade.js` (above `var api = {`)**

```js
  // ChannelSource: the only place that knows where a Stalker portal keeps its channel data (pinned in plan Task 1).
  // Paths are dot paths from window; values are read, never modified. No API calls.
  var CHANNEL_SOURCE = window.__stbChannelSource || /* Task 1 descriptor */ null;
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
      if (!list || !list.length) return null;
      return {
        channels: list.map(function (c) {
          return { id: String(c[s.fields.id]), number: c[s.fields.number] == null ? null : Number(c[s.fields.number]),
                   name: String(c[s.fields.name] || '').slice(0, MAX_NAME), genreId: String(c[s.fields.genreId] || '') };
        }),
        genres: genres.map(function (g) { return { id: String(g[s.genreFields.id]), title: String(g[s.genreFields.title] || '') }; }),
        favIds: favs.map(String),
      };
    } catch (e) {
      console.warn('[stb] channel source unreadable: ' + e);
      return null;
    }
  }
  function publishChannels() {
    var data = readChannels();
    if (!data) return;
    var sig = data.channels.length + ':' + data.channels[0].id + ':' + data.channels[data.channels.length - 1].id + ':' + data.favIds.join(',');
    if (sig === lastChannelsSig) return;
    lastChannelsSig = sig;
    send('channels.data', data);
  }
  function playChannel(id) {
    var s = CHANNEL_SOURCE, list = s && at(s.channels);
    var ch = list && Array.prototype.find.call(list, function (c) { return String(c[s.fields.id]) === String(id); });
    var play = s && at(s.play);
    if (!ch || typeof play !== 'function') { send('channels.error', { code: 'CHANNEL_NOT_FOUND' }); return; }
    var owner = at(String(s.play).split('.').slice(0, -1).join('.') || 'window');
    play.call(owner, ch);
  }
  window.__stbPublishChannels = publishChannels; // test hook
```
In `native.onmessage`, after the sessionId check, add before the `player.volume` line:
```js
    if (message.type === 'channels.play' && message.payload) { playChannel(message.payload.id); return; }
```
At the end of the IIFE, after `send('bridge.hello');`, add:
```js
  setInterval(publishChannels, 5000); // portal fills its lists after start-up and on refresh
```
Replace `/* Task 1 descriptor */ null` with the `SOURCE` object pinned in Task 1 (same literal as the fixture's `SOURCE`).

- [ ] **Step 4: Run to verify it passes**

Run: `node Native/StbHost/test/facade_check.js Native/StbHost/src/main/assets/stb_facade.js`
Expected: `facade ok`

- [ ] **Step 5: Stage `stb_facade.js`, `facade_check.js`; ask the user whether to commit**

---

### Task 3: `ChannelFilter` (pure Java) + JUnit

**Files:**
- Create: `Native/StbHost/src/main/java/com/stbemu/host/ChannelFilter.java`
- Create: `Native/StbHost/src/test/java/com/stbemu/host/ChannelFilterTest.java`
- Modify: `Native/StbHost/build.gradle` (add `testImplementation 'junit:junit:4.13.2'`)

**Interfaces:**
- Produces:
  ```java
  public final class ChannelFilter {
      public static final String TAB_ALL = "", TAB_FAV = "*";
      public static final class Channel { public final String id, name, genreId; public final Integer number; ... }
      public ChannelFilter(List<Channel> channels, Set<String> favIds);
      public List<Channel> apply(String tab, String query); // tab: TAB_ALL, TAB_FAV or a genre id
      static String normalize(String s);
  }
  ```

- [ ] **Step 1: Add the test dependency to `Native/StbHost/build.gradle`**

```gradle
dependencies {
    // ...existing...
    testImplementation 'junit:junit:4.13.2'
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.stbemu.host;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class ChannelFilterTest {
    private final ChannelFilter filter = new ChannelFilter(Arrays.asList(
            new ChannelFilter.Channel("1", 1, "ESPN HD", "g2"),
            new ChannelFilter.Channel("12", 12, "Thời sự 24/7", "g1"),
            new ChannelFilter.Channel("13", 13, "Đà Nẵng TV", "g1")),
            new HashSet<>(Collections.singletonList("13")));

    private static List<String> ids(List<ChannelFilter.Channel> list) {
        return list.stream().map(c -> c.id).collect(Collectors.toList());
    }

    @Test public void allTab_emptyQuery_everything() { assertEquals(Arrays.asList("1", "12", "13"), ids(filter.apply(ChannelFilter.TAB_ALL, ""))); }
    @Test public void blankQuery_isEmptyQuery() { assertEquals(3, filter.apply(ChannelFilter.TAB_ALL, "   ").size()); }
    @Test public void ignoresCaseAndDiacritics() { assertEquals(Arrays.asList("12"), ids(filter.apply(ChannelFilter.TAB_ALL, "THOI SU"))); }
    @Test public void dStroke() { assertEquals(Arrays.asList("13"), ids(filter.apply(ChannelFilter.TAB_ALL, "da nang"))); }
    @Test public void favTab() { assertEquals(Arrays.asList("13"), ids(filter.apply(ChannelFilter.TAB_FAV, ""))); }
    @Test public void genreTab() { assertEquals(Arrays.asList("12", "13"), ids(filter.apply("g1", ""))); }
    @Test public void genreAndQuery_intersect() { assertEquals(Arrays.asList("13"), ids(filter.apply("g1", "nang"))); }
    @Test public void noMatch_empty() { assertEquals(0, filter.apply(ChannelFilter.TAB_ALL, "zzz").size()); }
    @Test public void matchesNumber() { assertEquals(Arrays.asList("12"), ids(filter.apply(ChannelFilter.TAB_ALL, "12"))); }
}
```

- [ ] **Step 3: Run to verify it fails**

Run (from `Native/StbHost`): `... GradleMain testReleaseUnitTest`
Expected: FAIL, `cannot find symbol: class ChannelFilter`.

- [ ] **Step 4: Implement**

```java
package com.stbemu.host;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Search + tab filter for the native channel list. Names are normalised once; each keystroke is a linear scan. */
public final class ChannelFilter {
    public static final String TAB_ALL = "";
    public static final String TAB_FAV = "*";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    public static final class Channel {
        public final String id, name, genreId;
        public final Integer number;
        final String key; // normalised "number name" for matching

        public Channel(String id, Integer number, String name, String genreId) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.genreId = genreId;
            this.key = normalize((number == null ? "" : number + " ") + name);
        }
    }

    private final List<Channel> channels;
    private final Set<String> favIds;

    public ChannelFilter(List<Channel> channels, Set<String> favIds) {
        this.channels = channels;
        this.favIds = favIds;
    }

    public List<Channel> apply(String tab, String query) {
        String q = normalize(query == null ? "" : query.trim());
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels) {
            boolean inTab = TAB_ALL.equals(tab) || (TAB_FAV.equals(tab) ? favIds.contains(c.id) : tab.equals(c.genreId));
            if (inTab && (q.isEmpty() || c.key.contains(q))) out.add(c);
        }
        return out;
    }

    static String normalize(String s) {
        String noMarks = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `... GradleMain testReleaseUnitTest`
Expected: `BUILD SUCCESSFUL`, 9 tests passed (report under `build/reports/tests/testReleaseUnitTest/index.html`).

- [ ] **Step 6: Stage the three files; ask the user whether to commit**

---

### Task 4: `ChannelPanel` UI, 📺 button, message routing, dependency

**Files:**
- Create: `Native/StbHost/src/main/java/com/stbemu/host/ChannelPanel.java`
- Modify: `Native/StbHost/src/main/java/com/stbemu/host/StbHost.java` (web message listener, overlay, Back, release)
- Modify: `Native/StbHost/build.gradle`, `Assets/Plugins/Android/mainTemplate.gradle` (add `androidx.recyclerview:recyclerview:1.4.0`)
- Modify: `Docs/dependency-lock.md` (row for recyclerview 1.4.0)

**Interfaces:**
- Consumes: `ChannelFilter`, `ChannelFilter.Channel`, `TAB_ALL`, `TAB_FAV` (Task 3); `channels.data` / `channels.play` shapes (Task 2).
- Produces:
  ```java
  final class ChannelPanel extends LinearLayout {
      interface OnPick { void pick(String channelId); }
      ChannelPanel(Context context, OnPick onPick);
      void setData(JSONObject payload) throws JSONException; // payload of channels.data
      boolean hasData();
  }
  ```

- [ ] **Step 1: Add the dependency (both Gradle files) and the lock row**

`Native/StbHost/build.gradle` and `Assets/Plugins/Android/mainTemplate.gradle` dependencies:
```gradle
    implementation 'androidx.recyclerview:recyclerview:1.4.0'
```
`Docs/dependency-lock.md`: add `| androidx.recyclerview | 1.4.0 | native channel list |`.

- [ ] **Step 2: Create `ChannelPanel.java`**

```java
package com.stbemu.host;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native channel list over the portal: search box, tabs (All, ★, genres), smooth RecyclerView. */
final class ChannelPanel extends LinearLayout {
    interface OnPick { void pick(String channelId); }

    private final OnPick onPick;
    private final EditText search;
    private final LinearLayout tabs;
    private final Adapter adapter = new Adapter();
    private final TextView empty;
    private ChannelFilter filter;
    private String tab = ChannelFilter.TAB_ALL;

    ChannelPanel(Context context, OnPick onPick) {
        super(context);
        this.onPick = onPick;
        setOrientation(VERTICAL);
        setBackgroundColor(0xE6101010);

        search = new EditText(context);
        search.setSingleLine(true);
        search.setHint("Tìm kênh");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x88FFFFFF);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        addView(search, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabs = new LinearLayout(context);
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.addView(tabs);
        addView(tabScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = new TextView(context);
        empty.setText("Không có kênh phù hợp");
        empty.setTextColor(0xAAFFFFFF);
        empty.setVisibility(GONE);
        addView(empty);

        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    boolean hasData() {
        return filter != null;
    }

    void setData(JSONObject payload) throws JSONException {
        List<ChannelFilter.Channel> channels = new ArrayList<>();
        JSONArray arr = payload.getJSONArray("channels");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            channels.add(new ChannelFilter.Channel(c.getString("id"), c.isNull("number") ? null : c.getInt("number"),
                    c.getString("name"), c.optString("genreId", "")));
        }
        Set<String> favs = new HashSet<>();
        JSONArray favArr = payload.optJSONArray("favIds");
        for (int i = 0; favArr != null && i < favArr.length(); i++) favs.add(favArr.getString(i));
        filter = new ChannelFilter(channels, favs);

        tabs.removeAllViews();
        addTab("Tất cả", ChannelFilter.TAB_ALL);
        addTab("★", ChannelFilter.TAB_FAV);
        JSONArray genres = payload.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            JSONObject g = genres.getJSONObject(i);
            addTab(g.getString("title"), g.getString("id"));
        }
        refresh();
    }

    private void addTab(String title, String id) {
        Button b = new Button(getContext());
        b.setText(title);
        b.setAllCaps(false);
        b.setOnClickListener(v -> { tab = id; refresh(); });
        tabs.addView(b);
    }

    private void refresh() {
        if (filter == null) return;
        List<ChannelFilter.Channel> shown = filter.apply(tab, search.getText().toString());
        adapter.set(shown);
        empty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<ChannelFilter.Channel> items = new ArrayList<>();

        void set(List<ChannelFilter.Channel> next) {
            items = next;
            notifyDataSetChanged(); // ponytail: full rebind per keystroke; DiffUtil if this ever stutters
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView row = new TextView(parent.getContext());
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, parent.getResources().getDisplayMetrics());
            row.setPadding(pad, pad, pad, pad);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ChannelFilter.Channel c = items.get(position);
            TextView row = (TextView) holder.itemView;
            row.setText((c.number == null ? "" : c.number + "  ") + c.name);
            row.setOnClickListener(v -> onPick.pick(c.id));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
```

- [ ] **Step 3: Wire into `StbHost.java`**

Fields:
```java
    private ChannelPanel channelPanel;
    private Button channelButton;
    private String sessionId = ""; // from channels.data, needed to answer the facade
```
In `attach()`, after the overlay buttons:
```java
        channelButton = button("📺", v -> channelPanel.setVisibility(channelPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        channelButton.setVisibility(View.GONE); // until the portal's channel data arrives
        overlay.addView(channelButton, 0);
        channelPanel = new ChannelPanel(activity, id -> {
            channelPanel.setVisibility(View.GONE);
            if (portalReply != null) portalReply.postMessage("{\"version\":1,\"sessionId\":" + JSONObject.quote(sessionId)
                    + ",\"type\":\"channels.play\",\"payload\":{\"id\":" + JSONObject.quote(id) + "}}");
        });
        channelPanel.setVisibility(View.GONE);
        channelPanel.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
```
and add it to `root` after `remote`: `root.addView(channelPanel);`
In the `addWebMessageListener` callback, before `emit("portal.message", ...)`:
```java
                String data = message.getData();
                if (data.contains("\"type\":\"channels.")) { onChannelMessage(data); return; } // never forwarded to C# (size)
```
New method:
```java
    private void onChannelMessage(String json) {
        try {
            JSONObject msg = new JSONObject(json);
            if ("channels.error".equals(msg.optString("type"))) {
                Toast.makeText(activity, "Không mở được kênh", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!"channels.data".equals(msg.optString("type"))) return;
            sessionId = msg.optString("sessionId");
            channelPanel.setData(msg.getJSONObject("payload"));
            channelButton.setVisibility(View.VISIBLE);
        } catch (JSONException e) {
            Log.w(LOG_TAG, "bad channels.data: " + e);
        }
    }
```
In the WebView key listener, handle Back when the panel is open (before sending DEL):
```java
            if (channelPanel.getVisibility() == View.VISIBLE) {
                if (event.getAction() == KeyEvent.ACTION_UP) channelPanel.setVisibility(View.GONE);
                return true;
            }
```
In `closePortalOnUi()`: `channelPanel.setVisibility(View.GONE); channelButton.setVisibility(View.GONE);` (new portal = new data).
In `setOverlayVisible(false)`: also `channelPanel.setVisibility(View.GONE);`.
In `release()`: `root.removeView(channelPanel);`.

Imports to add in `StbHost.java`: `android.widget.Toast`.

- [ ] **Step 4: Build AAR + run all checks**

Run: `... GradleMain testReleaseUnitTest copyToUnity` and `node Native/StbHost/test/facade_check.js Native/StbHost/src/main/assets/stb_facade.js`
Expected: `BUILD SUCCESSFUL` (9 unit tests pass), `facade ok`, `unzip -l Assets/Plugins/Android/StbHost.aar` lists `classes.jar` containing `ChannelPanel`.

- [ ] **Step 5: Stage; ask the user whether to commit**

---

### Task 5: Device verification (spec §1 criteria)

**Files:** none (evidence in the report).

- [ ] **Step 1:** User builds **StbEmu → Build And Run Android (Dev)** and opens a working portal.
- [ ] **Step 2:** Within ~5 s of the main menu, 📺 appears. Evidence: screenshot `adb exec-out screencap -p`.
- [ ] **Step 3:** Tap 📺 (`adb shell input tap <x> <y>` at the button) → panel visible; measure open time with `adb logcat -v time` around the tap (target < 300 ms).
- [ ] **Step 4:** Type a query: `adb shell input tap` on the search box, `adb shell input text espn` → list filtered (screenshot).
- [ ] **Step 5:** Tap ★ and one genre tab → lists change (screenshots).
- [ ] **Step 6:** Tap a channel → panel closes; logcat shows the facade's `player.play` path (`[Portal]` / ExoPlayer `MediaCodec` lines) and video plays (screenshot).
- [ ] **Step 7:** Back with panel open → panel closes, portal unchanged. Query `zzz` → "Không có kênh phù hợp".
- [ ] **Step 8:** Report results per criterion; any failure → systematic-debugging, not ad-hoc fixes.
