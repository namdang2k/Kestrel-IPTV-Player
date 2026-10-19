package com.stbemu.host;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;

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
 * Native TV screen for the Xtream live catalog: genres → channels (logo, number, now playing, archive, ★) and a 16:9 player.
 * Portrait: player above the list. Landscape: player left, list right. The player box is never detached, so rotating
 * or going full screen does not recreate the video surface.
 */
final class TvScreen extends LinearLayout {
    interface Actions {
        void play(String channelId);
        void home();
        /** "landscape": either landscape side; "landscape-fixed": one landscape side; "portrait": portrait only. */
        void fullscreenChanged(String orientation);
        void reconnectPortal();
        /** False when skipped (auto reload already busy, or nothing to reload). */
        boolean reloadChannel();
        void togglePause();
        void stopPlayback();
        void showInfo();
        void requestDetails(String kind, String channelId, int requestId);
        // Pro menu (◆ on the player); only called for Pro users.
        void copyStreamUrl();
        void pictureInPicture();
        void openInVlc(String title);
        void pro(); // the menu's Get Pro button; C# opens the sheet
        void locked(String feature); // a Pro item tapped by a free user
    }

    /** The StbHost puts the video SurfaceView into this box (index 0, under the overlays). */
    final Stage16x9 playerBox;
    private final Actions actions;
    private final LogoLoader logos;
    private final NativeAdController nativeAds;
    private final LinearLayout topBar, body, listArea, playerColumn, playerControls, loading;
    private final TextView playerTitle, placeholder, channelInfo, breadcrumb, status, noticeText;
    private final LinearLayout notice;
    private final ProgressBar noticeSpinner;
    private final Button backButton, pauseButton, stopButton, reloadButton, fullscreenButton, proButton;
    private PopupWindow proMenu;
    private boolean pro, streamLoaded;
    private boolean pip; // in a picture-in-picture window: video only
    private final EditText search;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();

    private final LinearLayout channelsPanel, detailsContent;
    private final android.widget.ScrollView detailsPanel;
    private final Button channelsTab, epgTab;
    private String detailMode = "channels";
    private int detailRequest;
    private Runnable detailTimeout;
    private String epgChannel = "";
    private JSONObject lastEpg; // re-rendered when the EPG native ad arrives after the programs
    private int requestedTvAds;
    private int maxCategoryTvAds;

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
    private int bannerInset;

    boolean canShowBanner() { return !fullscreen && !pip && filter != null; }
    void setBannerInset(int heightPixels) {
        bannerInset = Math.max(0, heightPixels);
        listArea.setPadding(dp(12), dp(8), dp(12), fullscreen || getWidth() > getHeight() ? 0 : bannerInset);
    }
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

    TvScreen(Context context, LogoLoader logos, NativeAdController nativeAds, Actions actions) {
        super(context);
        this.logos = logos;
        this.nativeAds = nativeAds;
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
        addDescribed(topBar, iconButton(R.drawable.overview_24, v -> selectDetails("info")), "Account info");
        addDescribed(topBar, iconButton(R.drawable.stb_ic_sync, v -> {
            if (reloading) return;
            reloading = true;
            showPlaceholder();
            removeCallbacks(reloadDone);
            postDelayed(reloadDone, 30000);
            actions.reconnectPortal();
        }), "Reload channel list (reconnects to the server first if it is disconnected)");
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
        playerTitle.setPadding(dp(12), dp(8), dp(60), dp(8)); // clear of ◆
        proButton = iconButton(R.drawable.stb_ic_diamond, v -> showProMenu());
        proButton.setPadding(dp(12), 0, dp(12), 0); // centres the 20dp icon in 44dp
        proButton.setContentDescription("Pro features");
        proButton.setTooltipText("Pro features");
        FrameLayout.LayoutParams proParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        proParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        playerBox.addView(proButton, proParams);
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
        LinearLayout tabs = new LinearLayout(context);
        channelsTab = barButton("Channels", v -> selectDetails("channels"));
        epgTab = barButton("EPG", v -> selectDetails("epg"));
        tabs.addView(channelsTab, new LayoutParams(0, dp(48), 1));
        LayoutParams tabGap = new LayoutParams(0, dp(48), 1);
        tabGap.leftMargin = dp(12);
        tabs.addView(epgTab, tabGap);
        LayoutParams tabsParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        tabsParams.bottomMargin = dp(8);
        listArea.addView(tabs, tabsParams);
        channelsPanel = new LinearLayout(context);
        channelsPanel.setOrientation(VERTICAL);
        listArea.addView(channelsPanel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        detailsPanel = new android.widget.ScrollView(context);
        detailsPanel.setFillViewport(true);
        detailsContent = new LinearLayout(context);
        detailsContent.setOrientation(VERTICAL);
        detailsContent.setPadding(dp(16), dp(20), dp(16), dp(20));
        detailsContent.setBackground(PortalStyle.surface(context, PortalStyle.SURFACE, PortalStyle.BORDER));
        detailsPanel.addView(detailsContent);
        listArea.addView(detailsPanel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
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
        channelsPanel.addView(crumbRow);
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
        channelsPanel.addView(search, searchParams);
        status = label("Loading channel list…", 15, PortalStyle.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(32), dp(16), dp(32));
        channelsPanel.addView(status);
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
        channelsPanel.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        body.addView(playerColumn);
        body.addView(listArea);

        // Until the server has delivered its channel list.
        loading = new LinearLayout(context);
        loading.setOrientation(VERTICAL);
        loading.setGravity(Gravity.CENTER);
        ProgressBar portalSpinner = new ProgressBar(context, null, android.R.attr.progressBarStyleLarge);
        portalSpinner.setIndeterminate(true);
        portalSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
        loading.addView(portalSpinner, new LayoutParams(dp(48), dp(48)));
        TextView loadingText = label("Connecting to server…", 17, PortalStyle.TEXT);
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
        selectDetails("channels");
    }

    // ---- data from StbHost ----

    /** Every portal (re)connect calls this; only a different profile resets the screen. */
    void setProfile(String key, String favoritesKey) {
        if (key.equals(profileKey)) return;
        profileKey = key;
        selectDetails("channels");
        detailsContent.removeAllViews();
        genreScrollState = null;
        favorites = getContext().getSharedPreferences("favorites-" + favoritesKey, Context.MODE_PRIVATE); // shared with ChannelPanel
        genreFavorites = getContext().getSharedPreferences("genre-favorites-" + favoritesKey, Context.MODE_PRIVATE);
        filter = null;
        tab = null;
        nowPlaying = "";
        opening = false;
        requestedTvAds = 0;
        maxCategoryTvAds = 0;
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

    /** Channel the player is on ("" when unknown). */
    void setNowPlaying(String channelId) {
        opening = false;
        openingHint = null;
        String previous = nowPlaying;
        nowPlaying = channelId == null ? "" : channelId;
        if (!previous.equals(nowPlaying) && "epg".equals(detailMode)) loadDetails();
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
        if (!hasMedia) setStreamLoaded(false);
    }

    /** ◆ only once the stream shows a frame; hidden again while a new channel opens or after stop. */
    void setStreamLoaded(boolean loaded) {
        streamLoaded = loaded;
        if (!loaded && proMenu != null) proMenu.dismiss();
        proButton.setVisibility(loaded && controlsShown ? VISIBLE : GONE);
    }

    void setPro(boolean pro) {
        this.pro = pro;
        // Same outlined icon; gold once Pro.
        setIcon(proButton, R.drawable.stb_ic_diamond);
        proButton.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(pro ? PortalStyle.GOLD : PortalStyle.TEXT));
        if (proMenu != null) proMenu.dismiss(); // rebuilt with/without Get Pro on the next tap
    }

    void onShown() {
        requestFocus();
        syncNativeAdDemand();
    }

    void onHidden() {
        hideKeyboard();
        if (proMenu != null) proMenu.dismiss();
        if (fullscreen) setFullscreen(false);
        syncNativeAdDemand();
    }

    // ---- navigation ----

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_UP) return true;
        if (fullscreen) setFullscreen(false);
        else if (!"channels".equals(detailMode)) selectDetails("channels");
        else if (tab != null || search.length() > 0) showGenres();
        else actions.home();
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(rotateObserver);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if ((w > h) != (oldw > oldh) || oldw == 0) post(() -> arrange(w > h));
        post(this::syncPip);
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        post(this::syncPip);
    }

    /** Entering PiP shrinks the whole activity: show only the video, restore the layout when it is expanded again. */
    private void syncPip() {
        boolean now = ((Activity) getContext()).isInPictureInPictureMode();
        if (now == pip) return;
        pip = now;
        if (proMenu != null) proMenu.dismiss();
        setControlsShown(!pip);
        arrange(getWidth() > getHeight());
    }

    private void arrange(boolean landscape) {
        setBannerInset(bannerInset);
        boolean loaded = filter != null, full = fullscreen || pip;
        loading.setVisibility(loaded ? GONE : VISIBLE);
        body.setVisibility(loaded ? VISIBLE : GONE);
        topBar.setVisibility(full || !loaded ? GONE : VISIBLE);
        listArea.setVisibility(full ? GONE : VISIBLE);
        channelInfo.setVisibility(full || byId.get(nowPlaying) == null ? GONE : VISIBLE);
        playerColumn.setGravity(full ? Gravity.CENTER : Gravity.TOP);
        body.setBackgroundColor(full ? Color.BLACK : Color.TRANSPARENT);
        if (full) {
            body.setOrientation(VERTICAL);
            playerColumn.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (landscape) {
            // Keep video on the left and the channel list/banner on the right.
            body.setOrientation(HORIZONTAL);
            body.setLayoutDirection(LAYOUT_DIRECTION_LTR);
            playerColumn.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
            listArea.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        } else {
            body.setOrientation(VERTICAL);
            body.setLayoutDirection(LAYOUT_DIRECTION_LTR);
            playerColumn.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            listArea.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        syncNativeAdDemand();
    }

    private boolean autoRotateOn() {
        return android.provider.Settings.System.getInt(getContext().getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    private final ContentObserver rotateObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { syncOrientation(); }
    };

    private void syncOrientation() {
        actions.fullscreenChanged(fullscreen ? (autoRotateOn() ? "landscape" : "landscape-fixed") : "portrait");
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        // Unity owns the screen orientation: full screen is always landscape (system auto-rotate ignored); with
        // auto-rotate on it may flip between both landscape sides, otherwise it stays on one. Video zooms to fill.
        syncOrientation();
        ContentResolver resolver = getContext().getContentResolver();
        resolver.unregisterContentObserver(rotateObserver);
        // Full screen keeps listening so turning auto-rotate on while already landscape lets the video flip.
        if (on) resolver.registerContentObserver(android.provider.Settings.System.getUriFor(
                android.provider.Settings.System.ACCELEROMETER_ROTATION), false, rotateObserver);
        setIcon(fullscreenButton, on ? R.drawable.stb_ic_fullscreen_exit : R.drawable.stb_ic_fullscreen);
        fullscreenButton.setContentDescription(on ? "Exit full screen" : "Full screen");
        setControlsShown(true);
        arrange(getWidth() > getHeight());
    }

    private void setControlsShown(boolean shown) {
        controlsShown = shown;
        playerControls.setVisibility(shown ? VISIBLE : GONE);
        playerTitle.setVisibility(shown ? VISIBLE : GONE);
        proButton.setVisibility(shown && streamLoaded ? VISIBLE : GONE);
    }

    /** ◆ menu anchored under the button. Free users see the same items; a tap explains it is Pro, Get Pro opens the sheet. */
    private void showProMenu() {
        if (proMenu != null && proMenu.isShowing()) { proMenu.dismiss(); return; }
        Context context = getContext();
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        menu.setBackground(PortalStyle.surface(context, PortalStyle.SURFACE, PortalStyle.BORDER));
        proMenu = new PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        proMenu.setElevation(dp(8));
        ChannelFilter.Channel channel = byId.get(nowPlaying);
        String title = channel == null ? "" : channel.name;
        menuItem(menu, R.drawable.stb_ic_copy, "Copy stream URL", actions::copyStreamUrl);
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            menuItem(menu, R.drawable.stb_ic_pip, "Picture in picture", actions::pictureInPicture);
        menuItem(menu, R.drawable.stb_ic_vlc, "Open in VLC", () -> actions.openInVlc(title));
        if (!pro) {
            Button getPro = barButton("Upgrade to Pro", v -> { proMenu.dismiss(); actions.pro(); });
            getPro.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            getPro.setTextSize(15);
            getPro.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.stb_ic_diamond, 0, 0, 0);
            getPro.setCompoundDrawablePadding(dp(12));
            getPro.setPadding(dp(12), 0, dp(20), 0);
            getPro.setBackground(PortalStyle.interactive(context, true));
            getPro.setTextColor(PortalStyle.BACKGROUND);
            getPro.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(PortalStyle.BACKGROUND));
            getPro.setFocusable(true);
            LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            params.topMargin = dp(6);
            menu.addView(getPro, params);
        }
        proMenu.showAsDropDown(proButton, 0, dp(4), Gravity.END);
    }

    private void menuItem(LinearLayout menu, int icon, String text, Runnable action) {
        TextView item = label(text, 15, PortalStyle.TEXT);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        item.setCompoundDrawablePadding(dp(12));
        item.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(PortalStyle.TEXT));
        item.setPadding(dp(12), 0, dp(20), 0);
        item.setBackground(PortalStyle.interactive(getContext()));
        item.setFocusable(true);
        item.setOnClickListener(v -> {
            proMenu.dismiss();
            if (pro) action.run();
            else actions.locked(text);
        });
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        if (menu.getChildCount() > 0) params.topMargin = dp(4);
        menu.addView(item, params);
    }

    private void openTab(String id, String title) {
        selectDetails("channels");
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

    private static final class AdSlot {
        final int index;
        AdSlot(int index) { this.index = index; }
    }

    private void syncNativeAdDemand() {
        if (nativeAds == null) return;
        boolean screenActive = getVisibility() == VISIBLE && !fullscreen && !pip && filter != null;
        boolean inCategories = screenActive && "channels".equals(detailMode) && tab == null
                && search.getText().toString().trim().isEmpty() && maxCategoryTvAds > 0;
        if (inCategories && requestedTvAds < 1) requestedTvAds = 1;
        nativeAds.setTvNeededCount(inCategories ? Math.min(requestedTvAds, maxCategoryTvAds) : 0);
        boolean inEpg = screenActive && "epg".equals(detailMode);
        nativeAds.setEpgNeeded(inEpg);
    }

    void onNativeAdChanged() {
        syncNativeAdDemand();
        if ("epg".equals(detailMode)) {
            syncEpgHeaderAd();
        }
        if (filter == null || tab != null || !search.getText().toString().trim().isEmpty()) return;
        android.os.Parcelable scrollState = list.getLayoutManager() != null
                ? list.getLayoutManager().onSaveInstanceState() : null;
        refresh();
        if (scrollState != null && list.getLayoutManager() != null)
            list.getLayoutManager().onRestoreInstanceState(scrollState);
    }

    private void refresh() {
        String query = search.getText().toString();
        boolean searching = !query.trim().isEmpty();
        backButton.setVisibility(tab != null || searching ? VISIBLE : GONE);
        if (filter == null) {
            maxCategoryTvAds = 0;
            syncNativeAdDemand();
            status.setVisibility(VISIBLE);
            status.setText("Loading channel list…");
            breadcrumb.setText("TV");
            adapter.set(new ArrayList<>());
            return;
        }
        List<Object> items = new ArrayList<>();
        if (tab == null && !searching) {
            List<String[]> categoryRows = new ArrayList<>();
            categoryRows.add(new String[] {ChannelFilter.TAB_FAV, "★ Favorites", String.valueOf(filter.apply(ChannelFilter.TAB_FAV, "").size())});
            categoryRows.add(new String[] {ChannelFilter.TAB_ALL, "All", String.valueOf(byId.size())});
            // Stable partition: starred tags first, preserving portal order in both groups.
            for (boolean starred : new boolean[] {true, false})
                for (String[] genre : genres)
                    if (isGenreFavorite(genre[0]) == starred)
                        categoryRows.add(new String[] {genre[0], genre[1], String.valueOf(genreCounts.getOrDefault(genre[0], 0))});
            int totalPositions = categoryRows.isEmpty() ? 0 : Math.max(1, categoryRows.size() / 10);
            maxCategoryTvAds = Math.min(3, totalPositions);
            int loadedAds = nativeAds != null ? nativeAds.getTvAdCount() : 0;
            int adSlot = 0;
            for (int i = 0; i < categoryRows.size(); i++) {
                items.add(categoryRows.get(i));
                if ((i + 1) % 10 == 0) {
                    if (adSlot < loadedAds || loadedAds >= 3) {
                        items.add(new AdSlot(adSlot));
                    }
                    adSlot++;
                }
            }
            if (adSlot == 0 && !categoryRows.isEmpty() && loadedAds > 0) {
                items.add(new AdSlot(0));
            }
            breadcrumb.setText("TV · " + genres.size() + " categories · " + byId.size() + " channels");
        } else {
            List<ChannelFilter.Channel> shown = filter.apply(tab == null ? ChannelFilter.TAB_ALL : tab, query);
            items.addAll(shown);
            breadcrumb.setText("TV / " + (tab == null ? "All" : tabTitle) + (searching ? " / \"" + query.trim() + "\"" : "")
                    + " / " + shown.size() + " channels");
        }
        syncNativeAdDemand();
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
                : reloading ? "Reloading channel list…" : null;
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
        nowPlaying = lastChannel = channel.id; // optimistic: play() or a failure (setNowPlaying) follows
        if ("epg".equals(detailMode)) loadDetails();
        opening = true;
        message = null;
        adapter.notifyDataSetChanged();
        showNowPlaying();
        actions.play(channel.id);
    }

    private void selectDetails(String mode) {
        if (detailTimeout != null) removeCallbacks(detailTimeout);
        detailMode = mode;
        detailRequest++; // invalidate callbacks from a previous panel/request
        hideKeyboard();
        boolean channels = "channels".equals(mode), epg = "epg".equals(mode);
        channelsPanel.setVisibility(channels ? VISIBLE : GONE);
        detailsPanel.setVisibility(channels ? GONE : VISIBLE);
        channelsTab.setBackground(PortalStyle.interactive(getContext(), channels));
        channelsTab.setTextColor(channels ? PortalStyle.BACKGROUND : PortalStyle.TEXT);
        epgTab.setBackground(PortalStyle.interactive(getContext(), epg));
        epgTab.setTextColor(epg ? PortalStyle.BACKGROUND : PortalStyle.TEXT);
        syncNativeAdDemand();
        if (!channels) loadDetails();
    }

    private void loadDetails() {
        if (detailTimeout != null) removeCallbacks(detailTimeout);
        detailsContent.removeAllViews();
        lastEpg = null;
        syncNativeAdDemand();
        detailsContent.setGravity("info".equals(detailMode) ? Gravity.CENTER : Gravity.TOP);
        detailsPanel.scrollTo(0, 0);
        epgChannel = nowPlaying;
        int request = ++detailRequest;
        if ("epg".equals(detailMode)) {
            syncEpgHeaderAd();
            if (epgChannel.isEmpty()) {
                detailText("No EPG available", 15, PortalStyle.MUTED);
                return;
            }
        }
        detailText("Loading...", 15, PortalStyle.MUTED);
        detailTimeout = () -> {
            if (request != detailRequest) return;
            detailsContent.removeAllViews();
            syncEpgHeaderAd();
            detailText("Could not load account data. Tap the tab to retry.", 15, PortalStyle.MUTED);
        };
        postDelayed(detailTimeout, 12000);
        actions.requestDetails(detailMode, epgChannel, request);
    }

    void setDetails(JSONObject data) {
        if (data == null || data.optInt("requestId", -1) != detailRequest
                || !detailMode.equals(data.optString("kind"))) return;
        if ("epg".equals(detailMode) && (!epgChannel.equals(data.optString("id")) || !epgChannel.equals(nowPlaying))) return;
        if (detailTimeout != null) removeCallbacks(detailTimeout);
        detailsContent.removeAllViews();
        if ("info".equals(detailMode)) {
            syncNativeAdDemand();
            detailText("Subscription Expiry Date:", 20, PortalStyle.TEXT);
            detailText(data.optString("expiry", "").isEmpty() ? "Not provided by server" : data.optString("expiry"), 20, PortalStyle.TEXT);
            detailText("Username: " + data.optString("username", ""), 20, PortalStyle.TEXT);
            detailText("Status: " + data.optString("status", "") + " · Connections: " + data.optString("connections", ""), 17, PortalStyle.MUTED);
        } else {
            syncNativeAdDemand();
            syncEpgHeaderAd();
            JSONArray programs = data.optJSONArray("programs");
            if (programs == null || programs.length() == 0) {
                detailText(data.optBoolean("error") ? "Could not load EPG. Tap EPG to retry." : "No EPG available", 15, PortalStyle.MUTED);
                return;
            }
            lastEpg = data;
            for (int i = 0; i < programs.length(); i++) {
                JSONObject program = programs.optJSONObject(i);
                if (program == null) continue;
                detailText(program.optString("time"), 14, PortalStyle.MUTED);
                detailText(program.optString("title"), 17, PortalStyle.TEXT);
                String description = program.optString("description");
                if (!description.isEmpty()) detailText(description, 14, PortalStyle.MUTED);
                View divider = new View(getContext());
                divider.setBackgroundColor(PortalStyle.BORDER);
                LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                params.topMargin = params.bottomMargin = dp(12);
                detailsContent.addView(divider, params);
            }
        }
    }

    private void syncEpgHeaderAd() {
        if (!"epg".equals(detailMode)) return;
        NativeAd epgAd = nativeAds != null ? nativeAds.getEpgAd() : null;
        boolean hasHeaderAd = detailsContent.getChildCount() > 0 && detailsContent.getChildAt(0) instanceof NativeAdView;
        if (epgAd == null) {
            if (hasHeaderAd) detailsContent.removeViewAt(0);
            return;
        }
        if (hasHeaderAd) {
            bindAd((NativeAdView) detailsContent.getChildAt(0), epgAd);
            return;
        }
        NativeAdView adView = createAdView(getContext());
        bindAd(adView, epgAd);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        detailsContent.addView(adView, 0, params);
    }

    private NativeAdView createAdView(Context context) {
        NativeAdView adView = new NativeAdView(context);

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(PortalStyle.interactive(context));
        row.setMinimumHeight(Math.max(dp(36), search.getMeasuredHeight()));
        row.setPadding(dp(10), dp(2), dp(4), dp(2));

        TextView badge = label("Ad", 10, PortalStyle.ACCENT);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setBackground(PortalStyle.surface(context, PortalStyle.BACKGROUND, PortalStyle.BORDER));
        badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        LayoutParams badgeParams = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.rightMargin = dp(8);
        row.addView(badge, badgeParams);

        TextView headline = label("", 14, PortalStyle.TEXT);
        headline.setSingleLine(true);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(headline, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView cta = label("", 11, PortalStyle.MUTED);
        cta.setSingleLine(true);
        cta.setEllipsize(TextUtils.TruncateAt.END);
        cta.setMaxWidth(dp(96));
        LayoutParams ctaParams = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctaParams.leftMargin = dp(6);
        row.addView(cta, ctaParams);

        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.stb_ic_chevron_right);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(PortalStyle.MUTED));
        arrow.setPadding(dp(8), 0, dp(4), 0);
        row.addView(arrow);

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(5), dp(3), dp(5), dp(3));
        row.addView(icon, new LayoutParams(dp(36), dp(32)));

        adView.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        adView.setHeadlineView(headline);
        adView.setCallToActionView(cta);
        adView.setIconView(icon);
        return adView;
    }

    private void bindAd(NativeAdView adView, NativeAd ad) {
        if (adView.getChildCount() > 0) {
            adView.getChildAt(0).setMinimumHeight(Math.max(dp(36), search.getMeasuredHeight()));
        }
        TextView headline = (TextView) adView.getHeadlineView();
        TextView cta = (TextView) adView.getCallToActionView();
        ImageView icon = (ImageView) adView.getIconView();
        if (headline != null) {
            String title = ad.getHeadline() != null ? ad.getHeadline() : "";
            String body = ad.getBody();
            if (body != null && !body.isEmpty() && !body.equals(title)) {
                headline.setText(title + " · " + body);
            } else {
                headline.setText(title);
            }
        }
        if (cta != null) {
            String ctaText = ad.getCallToAction();
            if (ctaText == null || ctaText.isEmpty()) ctaText = ad.getAdvertiser();
            cta.setText(ctaText != null ? ctaText : "");
        }
        if (icon != null) {
            if (ad.getIcon() != null && ad.getIcon().getDrawable() != null) {
                icon.setImageDrawable(ad.getIcon().getDrawable());
                icon.setVisibility(VISIBLE);
            } else {
                icon.setImageDrawable(null);
                icon.setVisibility(INVISIBLE);
            }
        }
        adView.setNativeAd(ad);
    }

    private void detailText(String text, int size, int color) {
        TextView view = label(text, size, color);
        view.setPadding(0, dp(6), 0, dp(6));
        if ("info".equals(detailMode)) view.setGravity(Gravity.CENTER);
        detailsContent.addView(view, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        search.clearFocus();
        requestFocus();
    }

    // ---- views ----

    private int dp(int value) { return PortalStyle.dp(getContext(), value); }

    /** Xtream sends absolute icon URLs; Stalker-style relative names need logoBase. */
    static String logoUrl(String logoBase, String file) {
        if (file == null || file.isEmpty()) return null;
        if (file.startsWith("http://") || file.startsWith("https://")) return file;
        return logoBase.isEmpty() ? null : logoBase + file;
    }

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

    private ImageView favoriteIcon(Context context) {
        ImageView star = new ImageView(context);
        star.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        star.setPadding(dp(7), dp(5), dp(7), dp(5));
        star.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        star.setBackgroundResource(ripple.resourceId);
        return star;
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Object> items = new ArrayList<>();

        void set(List<Object> next) {
            items = next;
            notifyDataSetChanged(); // ponytail: full rebind per keystroke, as in ChannelPanel; DiffUtil if it stutters
        }

        @Override public int getItemViewType(int position) {
            Object item = items.get(position);
            if (item instanceof String[]) return 0;
            if (item instanceof AdSlot) return 2;
            return 1;
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            if (viewType == 2) {
                NativeAdView adView = createAdView(context);
                RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(4);
                adView.setLayoutParams(params);
                return new RecyclerView.ViewHolder(adView) { };
            }
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(PortalStyle.interactive(context));
            // Category rows match the search box (measured before the list in this vertical layout).
            row.setMinimumHeight(viewType == 0 ? Math.max(dp(36), search.getMeasuredHeight()) : dp(44));
            row.setPadding(dp(10), dp(2), dp(4), dp(2));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(4);
            row.setLayoutParams(params);
            if (viewType == 0) {
                row.addView(label("", 14, PortalStyle.TEXT), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(label("", 11, PortalStyle.MUTED));
                ImageView arrow = new ImageView(context);
                arrow.setImageResource(R.drawable.stb_ic_chevron_right);
                arrow.setImageTintList(android.content.res.ColorStateList.valueOf(PortalStyle.MUTED));
                arrow.setPadding(dp(8), 0, dp(4), 0);
                row.addView(arrow);
                ImageView star = favoriteIcon(context);
                row.addView(star, new LayoutParams(dp(36), dp(32)));
            } else {
                ImageView logo = new ImageView(context);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LayoutParams logoParams = new LayoutParams(dp(44), dp(28));
                logoParams.rightMargin = dp(8);
                row.addView(logo, logoParams);
                LinearLayout text = new LinearLayout(context);
                text.setOrientation(VERTICAL);
                TextView name = label("", 14, PortalStyle.TEXT);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(name);
                text.addView(label("", 11, PortalStyle.MUTED));
                row.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                ImageView star = favoriteIcon(context);
                row.addView(star, new LayoutParams(dp(36), dp(32)));
            }
            return new RecyclerView.ViewHolder(row) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = items.get(position);
            if (item instanceof AdSlot) {
                AdSlot slot = (AdSlot) item;
                if (slot.index + 2 <= maxCategoryTvAds && requestedTvAds < slot.index + 2) {
                    requestedTvAds = slot.index + 2;
                    syncNativeAdDemand();
                }
                NativeAd ad = nativeAds != null ? nativeAds.getTvAd(slot.index) : null;
                if (ad == null) return;
                bindAd((NativeAdView) holder.itemView, ad);
                return;
            }
            LinearLayout row = (LinearLayout) holder.itemView;
            if (item instanceof String[]) {
                String[] genre = (String[]) item;
                ((TextView) row.getChildAt(0)).setText(genre[1]);
                ((TextView) row.getChildAt(1)).setText(genre[2]);
                row.setOnClickListener(v -> openTab(genre[0], genre[1].replace("★ ", "")));
                ImageView star = (ImageView) row.getChildAt(3);
                boolean canFavorite = !ChannelFilter.TAB_ALL.equals(genre[0]) && !ChannelFilter.TAB_FAV.equals(genre[0]);
                star.setVisibility(canFavorite ? VISIBLE : GONE);
                boolean favorite = isGenreFavorite(genre[0]);
                star.setImageResource(favorite ? R.drawable.stb_ic_star : R.drawable.stb_ic_star_border);
                star.setImageTintList(android.content.res.ColorStateList.valueOf(favorite ? 0xFFFFC940 : PortalStyle.MUTED));
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
            logos.load(logoUrl(logoBase, file), (ImageView) row.getChildAt(0));
            LinearLayout text = (LinearLayout) row.getChildAt(1);
            ((TextView) text.getChildAt(0)).setText(c.name);
            TextView meta = (TextView) text.getChildAt(1);
            meta.setText((c.number == null ? "" : "#" + c.number) + (archived.contains(c.id) ? "   ⟲ Archive" : "")
                    + (playing ? opening ? "   … Opening" : "   ▶ Now playing" : ""));
            meta.setTextColor(playing ? PortalStyle.ACCENT : PortalStyle.MUTED);
            row.setOnClickListener(v -> pick(c));
            ImageView star = (ImageView) row.getChildAt(2);
            boolean favorite = filter.isFavorite(c.id);
            star.setImageResource(favorite ? R.drawable.stb_ic_star : R.drawable.stb_ic_star_border);
            star.setImageTintList(android.content.res.ColorStateList.valueOf(favorite ? 0xFFFFC940 : PortalStyle.MUTED));
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
