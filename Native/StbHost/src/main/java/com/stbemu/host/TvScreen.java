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
import android.widget.Toast;

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
        void reconnectPortal();
        /** False when skipped (auto reload already busy, or nothing to reload). */
        boolean reloadChannel();
        void togglePause();
        void stopPlayback();
        void showInfo();
    }

    /** The StbHost puts the video SurfaceView into this box (index 0, under the overlays). */
    final Stage16x9 playerBox;
    private final Actions actions;
    private final LogoLoader logos;
    private final LinearLayout topBar, body, listArea, playerColumn, playerControls, loading;
    private final TextView playerTitle, placeholder, channelInfo, breadcrumb, status, noticeText;
    private final LinearLayout notice;
    private final ProgressBar noticeSpinner;
    private final Button backButton, pauseButton, stopButton, reloadButton, fullscreenButton;
    private final EditText search;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();

    private ChannelFilter filter;
    private final List<String[]> genres = new ArrayList<>(); // {id, title}, portal order
    private final Map<String, Integer> genreCounts = new HashMap<>();
    private final Map<String, ChannelFilter.Channel> byId = new HashMap<>();
    private final Map<String, String> logoFiles = new HashMap<>();
    private final Set<String> archived = new HashSet<>();
    private String logoBase = "";
    private SharedPreferences favorites;
    private SharedPreferences genreFavorites;
    private String profileKey;
    private String tab; // null: genre list; else a ChannelFilter tab
    private android.os.Parcelable genreScrollState;
    private String tabTitle = "";
    private String nowPlaying = "";
    private boolean fullscreen, controlsShown = true;
    private boolean opening; // a channel was picked; cleared when the player (or an error) answers
    private boolean reloading; // Reconnect pressed; cleared by the reloaded portal's channel list (or after 30 s)
    private final Runnable reloadDone = () -> {
        reloading = false;
        showPlaceholder();
    };
    private boolean hasMedia;
    private String lastChannel = ""; // replayed by ▶ after ⏹
    private String openingHint; // replaces "Opening …" while opening, e.g. during a portal reconnect
    private String message;     // a failed channel, shown on the player for a few seconds
    private final Runnable clearMessage = () -> {
        message = null;
        showPlaceholder();
    };

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
        TextView title = label("TV", 20, PortalStyle.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        topBar.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button favoritesButton = barButton("Favorites", v -> openTab(ChannelFilter.TAB_FAV, "Favorites"));
        setIcon(favoritesButton, R.drawable.stb_ic_star);
        favoritesButton.setCompoundDrawablePadding(dp(6));
        topBar.addView(favoritesButton);
        addDescribed(topBar, iconButton(R.drawable.stb_ic_sync, v -> {
            if (reloading) return;
            reloading = true;
            showPlaceholder();
            removeCallbacks(reloadDone);
            postDelayed(reloadDone, 30000);
            actions.reconnectPortal();
        }), "Reload channel list (reconnects the portal first if it is disconnected)");
        addDescribed(topBar, iconButton(R.drawable.stb_ic_home, v -> actions.home()), "Home");
        addView(topBar);

        body = new LinearLayout(context);
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        playerColumn = new LinearLayout(context);
        playerColumn.setOrientation(VERTICAL);
        playerColumn.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        playerBox = new Stage16x9(context);
        playerBox.setBackgroundColor(Color.BLACK);
        playerBox.setOnClickListener(v -> setControlsShown(!controlsShown));
        placeholder = label("Pick a channel to watch", 15, PortalStyle.MUTED);
        placeholder.setGravity(Gravity.CENTER);
        playerBox.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        notice = new LinearLayout(context);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setBackground(PortalStyle.surface(context, PortalStyle.dark ? 0xE00B1220 : 0xE0F5F7FA, PortalStyle.BORDER));
        notice.setPadding(dp(14), dp(10), dp(16), dp(10));
        noticeSpinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        LayoutParams spinnerParams = new LayoutParams(dp(20), dp(20));
        spinnerParams.rightMargin = dp(10);
        notice.addView(noticeSpinner, spinnerParams);
        noticeText = label("", 14, PortalStyle.TEXT);
        noticeText.setMaxLines(2);
        noticeText.setEllipsize(TextUtils.TruncateAt.END);
        notice.addView(noticeText);
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        noticeParams.leftMargin = noticeParams.rightMargin = dp(24);
        playerBox.addView(notice, noticeParams);
        playerTitle = label("", 15, PortalStyle.TEXT);
        playerTitle.setShadowLayer(4, 0, 0, Color.BLACK);
        playerTitle.setPadding(dp(12), dp(8), dp(12), dp(8));
        playerTitle.setSingleLine(true);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        playerBox.addView(playerTitle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        playerControls = new LinearLayout(context);
        playerControls.setBackground(PortalStyle.surface(context, PortalStyle.dark ? 0xE00B1220 : 0xE0F5F7FA, PortalStyle.BORDER));
        playerControls.setPadding(dp(4), dp(4), dp(4), dp(4));
        pauseButton = iconButton(R.drawable.stb_ic_play, v -> {
            if (hasMedia) actions.togglePause();
            else if (byId.containsKey(lastChannel)) pick(byId.get(lastChannel));
        });
        playerControls.addView(pauseButton);
        stopButton = iconButton(R.drawable.stb_ic_stop, v -> actions.stopPlayback());
        addDescribed(playerControls, stopButton, "Stop");
        reloadButton = iconButton(R.drawable.stb_ic_refresh, v -> {
            ChannelFilter.Channel channel = byId.get(nowPlaying);
            if (channel == null || !actions.reloadChannel()) return;
            opening = true;
            message = null;
            adapter.notifyDataSetChanged();
            showNowPlaying();
        });
        addDescribed(playerControls, reloadButton, "Reload channel");
        addDescribed(playerControls, iconButton(R.drawable.stb_ic_info, v -> actions.showInfo()), "Stream info");
        fullscreenButton = iconButton(R.drawable.stb_ic_fullscreen, v -> setFullscreen(!fullscreen));
        playerControls.addView(fullscreenButton);
        fullscreenButton.setContentDescription("Full screen");
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        for (int i = 0; i < playerControls.getChildCount(); i++) playerControls.getChildAt(i).setBackgroundResource(ripple.resourceId);
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        controlsParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        playerBox.addView(playerControls, controlsParams);
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
        backButton = iconButton(R.drawable.stb_ic_back, v -> showGenres());
        backButton.setContentDescription("Back to categories");
        crumbRow.addView(backButton);
        breadcrumb = label("TV", 14, PortalStyle.TEXT);
        breadcrumb.setPadding(dp(8), 0, 0, 0);
        breadcrumb.setSingleLine(true);
        breadcrumb.setEllipsize(TextUtils.TruncateAt.END);
        crumbRow.addView(breadcrumb, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        listArea.addView(crumbRow);
        search = new EditText(context);
        search.setSingleLine(true);
        // No keyboard composition (see ChannelPanel): matching ignores diacritics anyway.
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setHint("Search channels");
        search.setTextSize(15);
        search.setTextColor(PortalStyle.TEXT);
        search.setHintTextColor(PortalStyle.MUTED);
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
        status = label("Loading channel list…", 15, PortalStyle.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(32), dp(16), dp(32));
        listArea.addView(status);
        list = new RecyclerView(context);
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
        ProgressBar portalSpinner = new ProgressBar(context, null, android.R.attr.progressBarStyleLarge);
        portalSpinner.setIndeterminate(true);
        portalSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
        loading.addView(portalSpinner, new LayoutParams(dp(48), dp(48)));
        TextView loadingText = label("Loading portal…", 17, PortalStyle.TEXT);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, dp(16), 0, dp(4));
        loading.addView(loadingText, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView loadingHint = label("Fetching channel list", 14, PortalStyle.MUTED);
        loadingHint.setGravity(Gravity.CENTER);
        loading.addView(loadingHint, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button exit = barButton("Back", v -> actions.home());
        ((LayoutParams) exit.getLayoutParams()).topMargin = dp(32);
        loading.addView(exit);
        addView(loading, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        arrange(false);
        refresh();
        setPlayback(false, false);
    }

    // ---- data from StbHost ----

    /** Every portal (re)connect calls this; only a different profile resets the screen. */
    void setProfile(String key, String favoritesKey) {
        if (key.equals(profileKey)) return;
        profileKey = key;
        genreScrollState = null;
        favorites = getContext().getSharedPreferences("favorites-" + favoritesKey, Context.MODE_PRIVATE); // shared with ChannelPanel
        genreFavorites = getContext().getSharedPreferences("genre-favorites-" + favoritesKey, Context.MODE_PRIVATE);
        filter = null;
        tab = null;
        nowPlaying = "";
        opening = false;
        search.setText("");
        refresh();
    }

    void reloadFailed() {
        removeCallbacks(reloadDone);
        reloading = false;
        showPlaceholder();
        if (filter != null) Toast.makeText(getContext(), "Could not refresh - showing saved channels", Toast.LENGTH_SHORT).show();
    }

    void setData(JSONObject payload) throws JSONException {
        removeCallbacks(reloadDone);
        reloading = false;
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
        openingHint = null;
        nowPlaying = channelId == null ? "" : channelId;
        if (!nowPlaying.isEmpty()) lastChannel = nowPlaying;
        adapter.notifyDataSetChanged();
        showNowPlaying();
    }

    /** Status while the picked channel is still opening (cleared when the player or an error answers). */
    void setOpeningHint(String hint) {
        if (!opening) return;
        openingHint = hint;
        showPlaceholder();
    }

    /** The picked channel did not start: message on the player, then back to what is really playing. */
    void channelFailed(String reason, String playingId) {
        ChannelFilter.Channel failed = byId.get(nowPlaying);
        String text = "Could not open " + (failed == null ? "the channel" : failed.name) + ": " + reason;
        setNowPlaying(playingId);
        message = text;
        removeCallbacks(clearMessage);
        postDelayed(clearMessage, 4000);
        showPlaceholder();
    }

    void setPlayback(boolean hasMedia, boolean playWhenReady) {
        this.hasMedia = hasMedia;
        showPlaceholder();
        boolean pausable = hasMedia && playWhenReady;
        setIcon(pauseButton, pausable ? R.drawable.stb_ic_pause : R.drawable.stb_ic_play);
        pauseButton.setContentDescription(pausable ? "Pause" : hasMedia ? "Resume" : "Replay last channel");
        setEnabled(pauseButton, hasMedia || byId.containsKey(lastChannel));
        setEnabled(stopButton, hasMedia);
        setEnabled(reloadButton, hasMedia);
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
        setIcon(fullscreenButton, on ? R.drawable.stb_ic_fullscreen_exit : R.drawable.stb_ic_fullscreen);
        fullscreenButton.setContentDescription(on ? "Exit full screen" : "Full screen");
        setControlsShown(true);
        arrange(getWidth() > getHeight());
    }

    private void setControlsShown(boolean shown) {
        controlsShown = shown;
        playerControls.setVisibility(shown ? VISIBLE : GONE);
        playerTitle.setVisibility(shown ? VISIBLE : GONE);
    }

    private void openTab(String id, String title) {
        if (tab == null && search.getText().toString().trim().isEmpty())
            genreScrollState = list.getLayoutManager().onSaveInstanceState();
        tab = id;
        tabTitle = title;
        refresh();
        list.scrollToPosition(0);
    }

    private void showGenres() {
        tab = null;
        search.setText(""); // refreshes
        if (genreScrollState != null)
            list.getLayoutManager().onRestoreInstanceState(genreScrollState);
        hideKeyboard();
    }

    private void refresh() {
        String query = search.getText().toString();
        boolean searching = !query.trim().isEmpty();
        backButton.setVisibility(tab != null || searching ? VISIBLE : GONE);
        if (filter == null) {
            status.setVisibility(VISIBLE);
            status.setText("Loading channel list…");
            breadcrumb.setText("TV");
            adapter.set(new ArrayList<>());
            return;
        }
        List<Object> items = new ArrayList<>();
        if (tab == null && !searching) {
            items.add(new String[] {ChannelFilter.TAB_FAV, "★ Favorites", String.valueOf(filter.apply(ChannelFilter.TAB_FAV, "").size())});
            items.add(new String[] {ChannelFilter.TAB_ALL, "All", String.valueOf(byId.size())});
            // Stable partition: starred tags first, preserving portal order in both groups.
            for (boolean starred : new boolean[] {true, false})
                for (String[] genre : genres)
                    if (isGenreFavorite(genre[0]) == starred)
                        items.add(new String[] {genre[0], genre[1], String.valueOf(genreCounts.getOrDefault(genre[0], 0))});
            breadcrumb.setText("TV · " + genres.size() + " categories · " + byId.size() + " channels");
        } else {
            List<ChannelFilter.Channel> shown = filter.apply(tab == null ? ChannelFilter.TAB_ALL : tab, query);
            items.addAll(shown);
            breadcrumb.setText("TV / " + (tab == null ? "All" : tabTitle) + (searching ? " / \"" + query.trim() + "\"" : "")
                    + " / " + shown.size() + " channels");
        }
        status.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        status.setText("No channels found");
        adapter.set(items);
    }

    private boolean isGenreFavorite(String id) {
        return genreFavorites != null && genreFavorites.getBoolean(id, false);
    }

    /** Status as a small centred panel over the video (the stream stays visible); idle hint only without media. */
    private void showPlaceholder() {
        ChannelFilter.Channel channel = byId.get(nowPlaying);
        String text = message != null ? "⚠ " + message
                : opening ? (openingHint != null ? openingHint : "Opening " + (channel == null ? "channel" : channel.name) + "…")
                : reloading ? "Reloading portal…" : null;
        notice.setVisibility(text != null ? VISIBLE : GONE);
        noticeText.setText(text);
        noticeSpinner.setVisibility(message == null ? VISIBLE : GONE);
        placeholder.setVisibility(text == null && !hasMedia ? VISIBLE : GONE);
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
                + (genre.isEmpty() ? "" : "  ·  " + genre) + (archived.contains(channel.id) ? "  ·  ⟲ Archive available" : ""));
    }

    private void pick(ChannelFilter.Channel channel) {
        hideKeyboard();
        nowPlaying = lastChannel = channel.id; // optimistic: the portal answers with play (or channels.error → setNowPlaying)
        opening = true;
        message = null;
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

    private Button iconButton(int icon, OnClickListener onClick) {
        Button button = barButton("", onClick);
        setIcon(button, icon);
        return button;
    }

    private static void setIcon(Button button, int icon) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(PortalStyle.TEXT)); // icons are white fills
    }

    private static void setEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
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
                row.addView(label("", 16, PortalStyle.TEXT), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(label("", 13, PortalStyle.MUTED));
                ImageView arrow = new ImageView(context);
                arrow.setImageResource(R.drawable.stb_ic_chevron_right);
                arrow.setImageTintList(android.content.res.ColorStateList.valueOf(PortalStyle.MUTED));
                arrow.setPadding(dp(8), 0, dp(4), 0);
                row.addView(arrow);
                Button star = new Button(context);
                PortalStyle.button(star);
                star.setFocusable(false);
                star.setPadding(dp(12), 0, dp(12), 0);
                row.addView(star, new LayoutParams(dp(44), dp(44)));
            } else {
                ImageView logo = new ImageView(context);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LayoutParams logoParams = new LayoutParams(dp(64), dp(40));
                logoParams.rightMargin = dp(12);
                row.addView(logo, logoParams);
                LinearLayout text = new LinearLayout(context);
                text.setOrientation(VERTICAL);
                TextView name = label("", 16, PortalStyle.TEXT);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(name);
                text.addView(label("", 13, PortalStyle.MUTED));
                row.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button star = new Button(context);
                PortalStyle.button(star);
                star.setFocusable(false);
                star.setPadding(dp(12), 0, dp(12), 0); // centres the 20dp icon in 44dp
                row.addView(star, new LayoutParams(dp(44), dp(44)));
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
                Button star = (Button) row.getChildAt(3);
                boolean canFavorite = !ChannelFilter.TAB_ALL.equals(genre[0]) && !ChannelFilter.TAB_FAV.equals(genre[0]);
                star.setVisibility(canFavorite ? VISIBLE : GONE);
                boolean favorite = isGenreFavorite(genre[0]);
                setIcon(star, favorite ? R.drawable.stb_ic_star : R.drawable.stb_ic_star_border);
                star.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(favorite ? 0xFFFFC940 : PortalStyle.MUTED));
                star.setContentDescription((favorite ? "Remove favorite tag: " : "Add favorite tag: ") + genre[1]);
                star.setOnClickListener(v -> {
                    if (!canFavorite || genreFavorites == null) return;
                    android.os.Parcelable scrollState = list.getLayoutManager().onSaveInstanceState();
                    genreFavorites.edit().putBoolean(genre[0], !isGenreFavorite(genre[0])).apply();
                    refresh();
                    list.getLayoutManager().onRestoreInstanceState(scrollState);
                });
                return;
            }
            ChannelFilter.Channel c = (ChannelFilter.Channel) item;
            boolean playing = c.id.equals(nowPlaying);
            row.setBackground(PortalStyle.interactive(getContext(), false));
            if (playing) row.setBackground(PortalStyle.surface(getContext(), PortalStyle.dark ? 0xFF1D3A4A : 0xFFD7EEE8, PortalStyle.ACCENT));
            String file = logoFiles.get(c.id);
            logos.load(file == null || logoBase.isEmpty() ? null : logoBase + file, (ImageView) row.getChildAt(0));
            LinearLayout text = (LinearLayout) row.getChildAt(1);
            ((TextView) text.getChildAt(0)).setText(c.name);
            TextView meta = (TextView) text.getChildAt(1);
            meta.setText((c.number == null ? "" : "#" + c.number) + (archived.contains(c.id) ? "   ⟲ Archive" : "")
                    + (playing ? opening ? "   … Opening" : "   ▶ Now playing" : ""));
            meta.setTextColor(playing ? PortalStyle.ACCENT : PortalStyle.MUTED);
            row.setOnClickListener(v -> pick(c));
            Button star = (Button) row.getChildAt(2);
            boolean favorite = filter.isFavorite(c.id);
            setIcon(star, favorite ? R.drawable.stb_ic_star : R.drawable.stb_ic_star_border);
            star.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(favorite ? 0xFFFFC940 : PortalStyle.MUTED));
            star.setContentDescription((favorite ? "Remove favorite: " : "Add favorite: ") + c.name);
            star.setOnClickListener(v -> {
                boolean selected = filter.toggleFavorite(c.id);
                if (favorites != null) favorites.edit().putBoolean(c.id, selected).apply();
                if (ChannelFilter.TAB_FAV.equals(tab)) refresh();
                else notifyItemChanged(holder.getBindingAdapterPosition());
            });
        }
    }
}
