package com.stbemu.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native TV screen over the hidden portal: genres → channels (logo, number, now playing, archive, ★) and a 16:9 player.
 * Portrait: player above the list. Landscape: list left, player right. The player box is never detached, so rotating
 * or going full screen does not recreate the video surface.
 */
final class TvScreen extends LinearLayout {
    interface Actions {
        void play(String channelId);
        void home();
        void reloadPortal();
        void openPortalUi();
        void togglePause();
        void showInfo();
    }

    /** The StbHost puts the video SurfaceView into this box (index 0, under the overlays). */
    final Stage16x9 playerBox;
    private final Actions actions;
    private final LogoLoader logos;
    private final LinearLayout topBar, body, listArea, playerColumn, playerControls, loading;
    private final TextView playerTitle, placeholder, channelInfo, breadcrumb, status;
    private final Button backButton, pauseButton, fullscreenButton;
    private final EditText search;
    private final Adapter adapter = new Adapter();

    private ChannelFilter filter;
    private final List<String[]> genres = new ArrayList<>(); // {id, title}, portal order
    private final Map<String, Integer> genreCounts = new HashMap<>();
    private final Map<String, ChannelFilter.Channel> byId = new HashMap<>();
    private final Map<String, String> logoFiles = new HashMap<>();
    private final Set<String> archived = new HashSet<>();
    private String logoBase = "";
    private SharedPreferences favorites;
    private String profileKey;
    private String tab; // null: genre list; else a ChannelFilter tab
    private String tabTitle = "";
    private String nowPlaying = "";
    private boolean fullscreen, controlsShown = true;
    private boolean opening; // a channel was picked; cleared when the player (or an error) answers
    private boolean hasMedia;

    TvScreen(Context context, LogoLoader logos, Actions actions) {
        super(context);
        this.logos = logos;
        this.actions = actions;
        setOrientation(VERTICAL);
        setBackgroundColor(PortalStyle.BACKGROUND);
        setFocusableInTouchMode(true);

        topBar = new LinearLayout(context);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView title = label("TV", 20, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        topBar.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topBar.addView(barButton("★ Yêu thích", v -> openTab(ChannelFilter.TAB_FAV, "Yêu thích")));
        addDescribed(topBar, barButton("⟳", v -> actions.reloadPortal()), "Tải lại portal");
        addDescribed(topBar, barButton("Portal", v -> actions.openPortalUi()), "Mở giao diện portal gốc");
        addDescribed(topBar, barButton("✕", v -> actions.home()), "Thoát");
        addView(topBar);

        body = new LinearLayout(context);
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        playerColumn = new LinearLayout(context);
        playerColumn.setOrientation(VERTICAL);
        playerColumn.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        playerBox = new Stage16x9(context);
        playerBox.setBackgroundColor(Color.BLACK);
        playerBox.setOnClickListener(v -> setControlsShown(!controlsShown));
        placeholder = label("Chọn kênh để xem", 15, PortalStyle.MUTED);
        placeholder.setGravity(Gravity.CENTER);
        playerBox.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerTitle = label("", 15, Color.WHITE);
        playerTitle.setShadowLayer(4, 0, 0, Color.BLACK);
        playerTitle.setPadding(dp(12), dp(8), dp(12), dp(8));
        playerTitle.setSingleLine(true);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        playerBox.addView(playerTitle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        playerControls = new LinearLayout(context);
        playerControls.setPadding(dp(8), dp(8), dp(8), dp(8));
        pauseButton = barButton("⏸", v -> actions.togglePause());
        playerControls.addView(pauseButton);
        addDescribed(playerControls, barButton("Info", v -> actions.showInfo()), "Thông tin stream");
        fullscreenButton = barButton("⛶", v -> setFullscreen(!fullscreen));
        playerControls.addView(fullscreenButton);
        fullscreenButton.setContentDescription("Toàn màn hình");
        playerBox.addView(playerControls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
        playerColumn.addView(playerBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        channelInfo = label("", 14, PortalStyle.MUTED);
        channelInfo.setPadding(dp(12), dp(8), dp(12), dp(8));
        playerColumn.addView(channelInfo);

        listArea = new LinearLayout(context);
        listArea.setOrientation(VERTICAL);
        listArea.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        listArea.setPadding(dp(12), dp(8), dp(12), 0);
        LinearLayout crumbRow = new LinearLayout(context);
        crumbRow.setGravity(Gravity.CENTER_VERTICAL);
        backButton = barButton("‹", v -> showGenres());
        backButton.setContentDescription("Quay lại thể loại");
        crumbRow.addView(backButton);
        breadcrumb = label("TV", 14, Color.WHITE);
        breadcrumb.setPadding(dp(8), 0, 0, 0);
        breadcrumb.setSingleLine(true);
        breadcrumb.setEllipsize(TextUtils.TruncateAt.END);
        crumbRow.addView(breadcrumb, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        listArea.addView(crumbRow);
        search = new EditText(context);
        search.setSingleLine(true);
        // No keyboard composition (see ChannelPanel): matching ignores diacritics anyway.
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setHint("Tìm kênh");
        search.setTextSize(15);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x88FFFFFF);
        search.setBackground(PortalStyle.interactive(context));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        LayoutParams searchParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.topMargin = searchParams.bottomMargin = dp(6);
        listArea.addView(search, searchParams);
        status = label("Đang tải danh sách kênh…", 15, PortalStyle.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(32), dp(16), dp(32));
        listArea.addView(status);
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(16));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard();
            }
        });
        listArea.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        body.addView(playerColumn);
        body.addView(listArea);

        // Until the portal has delivered its channel list: the portal loads hidden behind this.
        loading = new LinearLayout(context);
        loading.setOrientation(VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.addView(new ProgressBar(context));
        TextView loadingText = label("Đang tải portal…", 17, Color.WHITE);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, dp(16), 0, dp(4));
        loading.addView(loadingText, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView loadingHint = label("Đang lấy danh sách kênh", 14, PortalStyle.MUTED);
        loadingHint.setGravity(Gravity.CENTER);
        loading.addView(loadingHint, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button portalUi = barButton("Mở giao diện portal gốc", v -> actions.openPortalUi());
        ((LayoutParams) portalUi.getLayoutParams()).topMargin = dp(32);
        loading.addView(portalUi);
        Button exit = barButton("Quay lại", v -> actions.home());
        ((LayoutParams) exit.getLayoutParams()).topMargin = dp(8);
        loading.addView(exit);
        addView(loading, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        arrange(false);
        refresh();
    }

    // ---- data from StbHost ----

    /** Every portal (re)connect calls this; only a different profile resets the screen. */
    void setProfile(String key) {
        if (key.equals(profileKey)) return;
        profileKey = key;
        favorites = getContext().getSharedPreferences("favorites-" + key, Context.MODE_PRIVATE); // shared with ChannelPanel
        filter = null;
        tab = null;
        nowPlaying = "";
        opening = false;
        search.setText("");
        refresh();
    }

    void setData(JSONObject payload) throws JSONException {
        genres.clear();
        genreCounts.clear();
        byId.clear();
        logoFiles.clear();
        archived.clear();
        logoBase = payload.optString("logoBase", "");
        Map<String, String> genreNames = new HashMap<>();
        JSONArray g = payload.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) {
            JSONObject genre = g.getJSONObject(i);
            genres.add(new String[] {genre.getString("id"), genre.optString("title", "")});
            genreNames.put(genre.getString("id"), genre.optString("title", ""));
        }
        List<ChannelFilter.Channel> channels = new ArrayList<>();
        JSONArray arr = payload.getJSONArray("channels");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String id = c.getString("id"), genreId = c.optString("genreId", "");
            StringBuilder terms = new StringBuilder(genreNames.getOrDefault(genreId, ""));
            JSONArray tags = c.optJSONArray("tags");
            for (int j = 0; tags != null && j < tags.length(); j++) terms.append(' ').append(tags.optString(j, ""));
            ChannelFilter.Channel channel = new ChannelFilter.Channel(id, c.isNull("number") ? null : c.getInt("number"),
                    c.getString("name"), genreId, terms.toString());
            channels.add(channel);
            byId.put(id, channel);
            genreCounts.merge(genreId, 1, Integer::sum);
            if (c.has("logo")) logoFiles.put(id, c.getString("logo"));
            if (c.optInt("archive") > 0) archived.add(id);
        }
        Set<String> favs = new HashSet<>();
        JSONArray favArr = payload.optJSONArray("favIds");
        for (int i = 0; favArr != null && i < favArr.length(); i++) favs.add(favArr.getString(i));
        if (favorites != null) { // local choices override portal favorites (as in ChannelPanel)
            for (ChannelFilter.Channel channel : channels) {
                if (!favorites.contains(channel.id)) continue;
                if (favorites.getBoolean(channel.id, false)) favs.add(channel.id);
                else favs.remove(channel.id);
            }
        }
        filter = new ChannelFilter(channels, favs);
        arrange(getWidth() > getHeight()); // leaves the loading screen
        refresh();
        showNowPlaying();
    }

    /** Channel the player is on ("" when unknown, e.g. the portal's own start-up autoplay). */
    void setNowPlaying(String channelId) {
        opening = false;
        nowPlaying = channelId == null ? "" : channelId;
        adapter.notifyDataSetChanged();
        showNowPlaying();
    }

    void setPlayback(boolean hasMedia, boolean playWhenReady) {
        this.hasMedia = hasMedia;
        showPlaceholder();
        pauseButton.setText(playWhenReady ? "⏸" : "▶");
        pauseButton.setContentDescription(playWhenReady ? "Tạm dừng" : "Tiếp tục");
        pauseButton.setEnabled(hasMedia);
    }

    void onShown() {
        requestFocus();
    }

    void onHidden() {
        hideKeyboard();
        if (fullscreen) setFullscreen(false);
    }

    // ---- navigation ----

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_UP) return true;
        if (fullscreen) setFullscreen(false);
        else if (tab != null || search.length() > 0) showGenres();
        else actions.home();
        return true;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if ((w > h) != (oldw > oldh) || oldw == 0) post(() -> arrange(w > h));
    }

    private void arrange(boolean landscape) {
        boolean loaded = filter != null;
        loading.setVisibility(loaded ? GONE : VISIBLE);
        body.setVisibility(loaded ? VISIBLE : GONE);
        topBar.setVisibility(fullscreen || !loaded ? GONE : VISIBLE);
        listArea.setVisibility(fullscreen ? GONE : VISIBLE);
        channelInfo.setVisibility(fullscreen || byId.get(nowPlaying) == null ? GONE : VISIBLE);
        playerColumn.setGravity(fullscreen ? Gravity.CENTER : Gravity.TOP);
        body.setBackgroundColor(fullscreen ? Color.BLACK : Color.TRANSPARENT);
        if (fullscreen) {
            body.setOrientation(VERTICAL);
            playerColumn.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (landscape) {
            // RTL puts the first child (player) on the right without detaching its surface; children stay LTR.
            body.setOrientation(HORIZONTAL);
            body.setLayoutDirection(LAYOUT_DIRECTION_RTL);
            playerColumn.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
            listArea.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        } else {
            body.setOrientation(VERTICAL);
            body.setLayoutDirection(LAYOUT_DIRECTION_LTR);
            playerColumn.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            listArea.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        fullscreenButton.setText(on ? "🗗" : "⛶");
        fullscreenButton.setContentDescription(on ? "Thoát toàn màn hình" : "Toàn màn hình");
        setControlsShown(true);
        arrange(getWidth() > getHeight());
    }

    private void setControlsShown(boolean shown) {
        controlsShown = shown;
        playerControls.setVisibility(shown ? VISIBLE : GONE);
        playerTitle.setVisibility(shown ? VISIBLE : GONE);
    }

    private void openTab(String id, String title) {
        tab = id;
        tabTitle = title;
        refresh();
    }

    private void showGenres() {
        tab = null;
        search.setText(""); // refreshes
        hideKeyboard();
    }

    private void refresh() {
        String query = search.getText().toString();
        boolean searching = !query.trim().isEmpty();
        backButton.setVisibility(tab != null || searching ? VISIBLE : GONE);
        if (filter == null) {
            status.setVisibility(VISIBLE);
            status.setText("Đang tải danh sách kênh…");
            breadcrumb.setText("TV");
            adapter.set(new ArrayList<>());
            return;
        }
        List<Object> items = new ArrayList<>();
        if (tab == null && !searching) {
            items.add(new String[] {ChannelFilter.TAB_FAV, "★ Yêu thích", String.valueOf(filter.apply(ChannelFilter.TAB_FAV, "").size())});
            items.add(new String[] {ChannelFilter.TAB_ALL, "Tất cả", String.valueOf(byId.size())});
            for (String[] genre : genres) items.add(new String[] {genre[0], genre[1], String.valueOf(genreCounts.getOrDefault(genre[0], 0))});
            breadcrumb.setText("TV · " + genres.size() + " thể loại · " + byId.size() + " kênh");
        } else {
            List<ChannelFilter.Channel> shown = filter.apply(tab == null ? ChannelFilter.TAB_ALL : tab, query);
            items.addAll(shown);
            breadcrumb.setText("TV / " + (tab == null ? "Tất cả" : tabTitle) + (searching ? " / \"" + query.trim() + "\"" : "")
                    + " / " + shown.size() + " kênh");
        }
        status.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        status.setText("Không tìm thấy kênh");
        adapter.set(items);
    }

    private void showPlaceholder() {
        ChannelFilter.Channel channel = byId.get(nowPlaying);
        placeholder.setText(opening ? "Đang mở " + (channel == null ? "kênh" : channel.name) + "…" : "Chọn kênh để xem");
        placeholder.setBackgroundColor(opening && hasMedia ? 0x99000000 : Color.TRANSPARENT);
        placeholder.setVisibility(opening || !hasMedia ? VISIBLE : GONE);
    }

    private void showNowPlaying() {
        showPlaceholder();
        ChannelFilter.Channel channel = byId.get(nowPlaying);
        playerTitle.setText(channel == null ? "" : channel.name);
        channelInfo.setVisibility(channel == null || fullscreen ? GONE : VISIBLE);
        if (channel == null) return;
        String genre = "";
        for (String[] g : genres) if (g[0].equals(channel.genreId)) genre = g[1];
        channelInfo.setText((channel.number == null ? "" : "#" + channel.number + "  ") + channel.name
                + (genre.isEmpty() ? "" : "  ·  " + genre) + (archived.contains(channel.id) ? "  ·  ⟲ Có lưu trữ" : ""));
    }

    private void pick(ChannelFilter.Channel channel) {
        hideKeyboard();
        nowPlaying = channel.id; // optimistic: the portal answers with play (or channels.error → setNowPlaying)
        opening = true;
        adapter.notifyDataSetChanged();
        showNowPlaying();
        actions.play(channel.id);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        search.clearFocus();
        requestFocus();
    }

    // ---- views ----

    private int dp(int value) { return PortalStyle.dp(getContext(), value); }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button barButton(String text, OnClickListener onClick) {
        Button button = new Button(getContext());
        button.setText(text);
        PortalStyle.button(button);
        button.setFocusable(false); // keep focus (and Back) on the screen
        button.setOnClickListener(onClick);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private void addDescribed(LinearLayout row, Button button, String description) {
        button.setContentDescription(description);
        button.setTooltipText(description);
        row.addView(button);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Object> items = new ArrayList<>();

        void set(List<Object> next) {
            items = next;
            notifyDataSetChanged(); // ponytail: full rebind per keystroke, as in ChannelPanel; DiffUtil if it stutters
        }

        @Override public int getItemViewType(int position) { return items.get(position) instanceof String[] ? 0 : 1; }

        @Override public int getItemCount() { return items.size(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(PortalStyle.interactive(context));
            row.setMinimumHeight(dp(viewType == 0 ? 52 : 64));
            row.setPadding(dp(12), dp(6), dp(6), dp(6));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(6);
            row.setLayoutParams(params);
            if (viewType == 0) {
                row.addView(label("", 16, Color.WHITE), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(label("", 13, PortalStyle.MUTED));
                TextView arrow = label("›", 22, PortalStyle.MUTED);
                arrow.setPadding(dp(12), 0, dp(8), 0);
                row.addView(arrow);
            } else {
                ImageView logo = new ImageView(context);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LayoutParams logoParams = new LayoutParams(dp(64), dp(40));
                logoParams.rightMargin = dp(12);
                row.addView(logo, logoParams);
                LinearLayout text = new LinearLayout(context);
                text.setOrientation(VERTICAL);
                TextView name = label("", 16, Color.WHITE);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(name);
                text.addView(label("", 13, PortalStyle.MUTED));
                row.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button star = new Button(context);
                PortalStyle.button(star);
                star.setFocusable(false);
                star.setTextSize(22);
                row.addView(star, new LayoutParams(dp(52), dp(52)));
            }
            return new RecyclerView.ViewHolder(row) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LinearLayout row = (LinearLayout) holder.itemView;
            Object item = items.get(position);
            if (item instanceof String[]) {
                String[] genre = (String[]) item;
                ((TextView) row.getChildAt(0)).setText(genre[1]);
                ((TextView) row.getChildAt(1)).setText(genre[2]);
                row.setOnClickListener(v -> openTab(genre[0], genre[1].replace("★ ", "")));
                return;
            }
            ChannelFilter.Channel c = (ChannelFilter.Channel) item;
            boolean playing = c.id.equals(nowPlaying);
            row.setBackground(PortalStyle.interactive(getContext(), false));
            if (playing) row.setBackground(PortalStyle.surface(getContext(), 0xFF1D3A4A, PortalStyle.ACCENT));
            String file = logoFiles.get(c.id);
            logos.load(file == null || logoBase.isEmpty() ? null : logoBase + file, (ImageView) row.getChildAt(0));
            LinearLayout text = (LinearLayout) row.getChildAt(1);
            ((TextView) text.getChildAt(0)).setText(c.name);
            TextView meta = (TextView) text.getChildAt(1);
            meta.setText((c.number == null ? "" : "#" + c.number) + (archived.contains(c.id) ? "   ⟲ Lưu trữ" : "")
                    + (playing ? opening ? "   … Đang mở" : "   ▶ Đang phát" : ""));
            meta.setTextColor(playing ? PortalStyle.ACCENT : PortalStyle.MUTED);
            row.setOnClickListener(v -> pick(c));
            Button star = (Button) row.getChildAt(2);
            boolean favorite = filter.isFavorite(c.id);
            star.setText(favorite ? "★" : "☆");
            star.setTextColor(favorite ? 0xFFFFC940 : PortalStyle.MUTED);
            star.setContentDescription((favorite ? "Bỏ yêu thích: " : "Thêm yêu thích: ") + c.name);
            star.setOnClickListener(v -> {
                boolean selected = filter.toggleFavorite(c.id);
                if (favorites != null) favorites.edit().putBoolean(c.id, selected).apply();
                if (ChannelFilter.TAB_FAV.equals(tab)) refresh();
                else notifyItemChanged(holder.getBindingAdapterPosition());
            });
        }
    }
}
