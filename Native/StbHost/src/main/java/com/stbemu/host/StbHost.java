package com.stbemu.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Native layer on top of the Unity view: video SurfaceView (ExoPlayer) under a transparent WebView (portal).
 * All public methods may be called from any thread; work is posted to the Android UI thread,
 * which is also ExoPlayer's application looper.
 */
public final class StbHost {

    /** Implemented in C# with AndroidJavaProxy. Called on the Android UI thread, not Unity's main thread. */
    public interface Listener {
        void onEvent(String type, String data);
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** JS object name the facade uses to talk to native (WebViewCompat.addWebMessageListener). */
    private static final String BRIDGE_NAME = "stbNative";
    private static final String LOG_TAG = "StbPortal";

    private SurfaceView videoView;
    private Stage16x9 stage;
    private WebView webView;
    private ExoPlayer player;
    private String facadeJs;
    private ScriptHandler facadeScript;
    private JavaScriptReplyProxy portalReply;
    private LinearLayout overlay;
    private LinearLayout remote;
    private ChannelPanel channelPanel;
    private Button channelButton;
    private String channelSessionId = ""; // from channels.data; the facade drops messages without it
    private boolean controlsHidden;
    private LinearLayout playbackControls;
    private Button pauseButton, sizeButton;
    private boolean expandedVideo, overlayEnabled;
    private double viewportWidth = 1920, viewportHeight = 1080, viewportX, viewportY;
    private int portalGeneration;
    private String activeSessionId = "";
    private final ReloadPolicy reloadPolicy = new ReloadPolicy();
    private Button reloadButton;
    private boolean retryScheduled;
    private boolean waitingForChannel;
    private String switchingChannelId = "";
    private boolean channelRequestSent, channelReconnectAttempted, suppressPortalStop, explicitPlaybackStopped;
    private final PortalChannelState portalChannelState = new PortalChannelState();
    private final Runnable channelSwitchTimeout;
    private final Runnable retryStream = () -> {
        retryScheduled = false;
        if (player == null || player.getMediaItemCount() == 0 || reloadPolicy.mode() == ReloadPolicy.Mode.NONE) return;
        player.prepare();
        player.play();
        updatePlaybackControls();
    };
    /** Stalker: stb.player.on is true while the player is full screen (not the menu or a list preview). */
    private static final String PORTAL_FULLSCREEN_JS = "!!(window.stb && stb.player && stb.player.on)";

    // Virtual remote → Android key → DOM keyCode seen by the portal (MAG values: arrows 37-40, OK 13, Back 8, Menu 122).
    // ponytail: CH+/CH- sent as PAGE_UP/PAGE_DOWN (33/34); verify against the real portal and remap here if it expects other codes.
    private static final String[] REMOTE_LABELS = {"◀", "▲", "▼", "▶", "OK", "Back", "Menu", "CH+", "CH−"};
    private static final int[] REMOTE_KEYS = {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN};

    public StbHost(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        channelSwitchTimeout = () -> {
            if (!waitingForChannel) return;
            if (!channelReconnectAttempted && !switchingChannelId.isEmpty()) {
                channelReconnectAttempted = true;
                String retryId = portalChannelState.pendingChannelId();
                if (retryId == null) retryId = switchingChannelId;
                onPortalUnavailable("CHANNEL_SWITCH_TIMEOUT");
                portalChannelState.selectWhileUnavailable(retryId);
                emit("ui.portal-reconnect", retryId);
                return;
            }
            clearChannelSwitch();
            suppressPortalStop = true;
            if (player.getPlayerError() != null) scheduleRetry();
            Toast.makeText(activity, "Kênh mới không phản hồi · vẫn phát kênh cũ", Toast.LENGTH_SHORT).show();
        };
        ui.post(this::attach);
    }

    private void attach() {
        ViewGroup root = activity.findViewById(android.R.id.content);

        videoView = new SurfaceView(activity);
        videoView.setZOrderMediaOverlay(true); // above Unity's SurfaceView, below window views (WebView)
        videoView.setVisibility(View.GONE);

        // Development builds only: inspect the portal's requests/responses via chrome://inspect.
        WebView.setWebContentsDebuggingEnabled((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView = new WebView(activity);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // Honour the facade's <meta viewport width=…> so the 1280x720 TV layout is scaled to the phone screen.
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.setVisibility(View.GONE);
        installPortalWebViewClient(portalGeneration);
        // Portal JS console (incl. uncaught errors) → logcat; release WebView builds do not log it on their own.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.i(LOG_TAG, message.messageLevel() + " " + message.message() + " @" + message.sourceId() + ":" + message.lineNumber());
                return true;
            }
        });
        // System Back while the portal has focus goes to the portal as its Back key instead of closing the Unity activity.
        webView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (expandedVideo) {
                if (event.getAction() == KeyEvent.ACTION_UP) setExpandedVideo(false);
                return true;
            }
            if (channelPanel.getVisibility() == View.VISIBLE) { // Back closes the channel list first
                if (event.getAction() == KeyEvent.ACTION_UP) channelPanel.dismiss();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) sendKeyOnUi(KeyEvent.KEYCODE_DEL);
            return true;
        });
        webView.setOnTouchListener(touchAsRemote());
        facadeJs = readAsset("stb_facade.js");

        overlay = row(Gravity.TOP | Gravity.END, false);
        overlay.addView(button("⟳", v -> emit("ui.refresh", "")));
        overlay.addView(button("⌂", v -> emit("ui.home", "")));
        overlay.addView(button("🔍", v -> promptText()));
        overlay.addView(button("🎮", v -> remote.setVisibility(remote.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        remote = row(Gravity.BOTTOM, true);
        for (int i = 0; i < REMOTE_KEYS.length; i++) {
            int key = REMOTE_KEYS[i];
            remote.addView(button(REMOTE_LABELS[i], v -> sendKeyOnUi(key)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        playbackControls = row(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, false);
        playbackControls.setOrientation(LinearLayout.VERTICAL);
        ((FrameLayout.LayoutParams) playbackControls.getLayoutParams()).bottomMargin = dp(66);
        LinearLayout transport = new LinearLayout(activity);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        playbackControls.addView(transport);
        pauseButton = button("Tạm dừng", v -> setPaused(player.getPlayWhenReady()));
        transport.addView(pauseButton);
        transport.addView(button("Dừng", v -> { waitingForChannel = false; stop(); }));
        sizeButton = button("Phóng to", v -> setExpandedVideo(!expandedVideo));
        transport.addView(sizeButton);
        reloadButton = button("No reload", v -> {
            cancelRetry();
            reloadPolicy.cycle();
            reloadButton.setText(reloadPolicy.mode() == ReloadPolicy.Mode.ALWAYS ? "Reload" :
                    reloadPolicy.mode() == ReloadPolicy.Mode.ONCE ? "Reload one" : "No reload");
            reloadButton.setContentDescription(reloadPolicy.mode() == ReloadPolicy.Mode.ALWAYS ? "Lỗi: thử lại liên tục" :
                    reloadPolicy.mode() == ReloadPolicy.Mode.ONCE ? "Lỗi: thử lại một lần" : "Lỗi: không thử lại");
            if (player.getPlayerError() != null) scheduleRetry();
        });
        reloadButton.setContentDescription("Lỗi: không thử lại");
        playbackControls.addView(reloadButton);
        channelButton = button("📺", v -> toggleChannels());
        channelButton.setVisibility(View.GONE); // until the portal's channel data arrives
        overlay.addView(channelButton, 0);
        String[] actions = {"Danh sách kênh", "Tải lại portal", "Trang chủ", "Nhập văn bản", "Điều khiển"};
        for (int i = 0; i < actions.length; i++) {
            overlay.getChildAt(i).setContentDescription(actions[i]);
            overlay.getChildAt(i).setTooltipText(actions[i]);
        }
        channelPanel = new ChannelPanel(activity, id -> {
            cancelRetry();
            explicitPlaybackStopped = false;
            boolean requestInFlight = channelRequestSent;
            if (!requestInFlight) channelReconnectAttempted = false;
            waitingForChannel = true;
            setExpandedVideo(false); // Channel selection returns to the portal's own presentation.
            channelPanel.dismiss();
            webView.requestFocus();
            if (portalChannelState.portalState() == PortalChannelState.PortalState.READY) {
                portalChannelState.select(id);
                if (!requestInFlight) sendChannelRequest(id);
            } else if (portalChannelState.selectWhileUnavailable(id)) {
                emit("ui.portal-reconnect", id);
            }
        }, () -> { channelPanel.dismiss(); webView.requestFocus(); });
        channelPanel.dismiss();
        channelPanel.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stage = new Stage16x9(activity); // video + portal share one centred 16:9 box; controls below stay full-screen
        stage.addView(videoView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyVideoViewport();
        });
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(overlay);
        root.addView(remote);
        root.addView(playbackControls);
        root.addView(channelPanel);
        channelPanel.bringToFront(); // The sheet owns its close action.

        // Prefer a reservoir of media over fast startup. Keep the default byte threshold
        // so high-bitrate streams do not allocate unbounded buffers on smaller devices.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(50_000, 60_000, 3_000, 6_000)
                .build();
        player = new ExoPlayer.Builder(activity).setLoadControl(loadControl).build();
        // Playback diagnostics in logcat (tag EventLogger): dropped frames, formats/bitrate, buffering, errors.
        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) player.addAnalyticsListener(new EventLogger());
        player.setVideoSurfaceView(videoView);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                emit("state", String.valueOf(state)); // Player.STATE_IDLE=1, BUFFERING=2, READY=3, ENDED=4
                updatePlaybackControls();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updatePlaybackControls();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                emit("isPlaying", String.valueOf(isPlaying));
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                emit("error", error.getErrorCodeName());
                scheduleRetry();
            }
        });
        emit("ready", "");
    }

    private void installPortalWebViewClient(int generation) {
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (generation == portalGeneration && request.isForMainFrame())
                    onPortalUnavailable("LOAD_FAILED:" + error.getErrorCode() + " " + error.getDescription() + " " + request.getUrl());
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                Log.w(LOG_TAG, "HTTP " + response.getStatusCode() + " " + request.getMethod() + " " + request.getUrl());
                if (generation == portalGeneration && request.isForMainFrame() && response.getStatusCode() >= 400)
                    onPortalUnavailable("HTTP_" + response.getStatusCode() + " " + request.getUrl());
            }
        });
    }

    /**
     * Loads the portal with the STB facade installed at document start, before any portal script runs.
     * Facade and message listener are limited to the portal's origin; messages from sub-frames are dropped.
     *
     * @param snapshotJson JSON object serialized by C# (identity + sessionId); becomes window.__stbSnapshot for the facade.
     * @param userAgent    empty = WebView default.
     */
    public void openPortal(String url, String snapshotJson, String userAgent) {
        ui.post(() -> {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                onPortalUnavailable("WEBVIEW_UNSUPPORTED"); // do not fall back to late injection: portal may already have failed
                return;
            }
            boolean reconnectingForChannel = player.getMediaItemCount() > 0 && portalChannelState.hasPendingChannel();
            closePortalOnUi(reconnectingForChannel);
            portalChannelState.connecting();
            explicitPlaybackStopped = false;
            channelSessionId = "";
            channelRequestSent = false;
            suppressPortalStop = player.getMediaItemCount() > 0;
            installPortalWebViewClient(portalGeneration);
            if (reconnectingForChannel) {
                ui.removeCallbacks(channelSwitchTimeout);
                ui.postDelayed(channelSwitchTimeout, 30000);
            }
            final int generation = portalGeneration;
            try {
                activeSessionId = new JSONObject(snapshotJson).getString("sessionId");
                String identity = url + "\n" + new JSONObject(snapshotJson).optString("mac");
                channelPanel.setProfile(java.util.UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
            } catch (JSONException e) {
                onPortalUnavailable("BAD_SNAPSHOT");
                return;
            }

            // ponytail: one portal session at a time, so wipe web storage on every open; per-profile isolation needs WebView profiles (MULTI_PROFILE).
            WebStorage.getInstance().deleteAllData();

            Uri uri = Uri.parse(url);
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
            Set<String> rules = Collections.singleton(origin);

            webView.getSettings().setUserAgentString(userAgent == null || userAgent.isEmpty() ? null : userAgent);
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, rules, (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                if (!isMainFrame || message.getData() == null || generation != portalGeneration) return;
                portalReply = replyProxy;
                String data = message.getData();
                if (data.contains("\"type\":\"video.viewport\"")) { onViewportMessage(data); return; }
                if (data.contains("\"type\":\"channels.")) { onChannelMessage(data); return; } // never forwarded to C# (size)
                emit("portal.message", data);
            });
            facadeScript = WebViewCompat.addDocumentStartJavaScript(webView, "window.__stbSnapshot=" + snapshotJson + ";\n" + facadeJs, rules);

            // Cookie deletion is asynchronous: do not erase cookies belonging to the new handshake.
            CookieManager.getInstance().removeAllCookies(removed -> {
                if (generation == portalGeneration) webView.loadUrl(url);
            });
        });
    }

    /** Sends a JSON string to the facade (stbNative.onmessage). Dropped until the page has sent its first message. */
    public void publishToPortal(String json) {
        ui.post(() -> {
            if (portalReply != null) portalReply.postMessage(json);
        });
    }

    public void closePortal() {
        ui.post(() -> closePortalOnUi(false));
    }

    public void abortPortalReconnect(String reason) {
        ui.post(() -> onPortalUnavailable(reason));
    }

    private void closePortalOnUi() { closePortalOnUi(false); }

    private void closePortalOnUi(boolean preserveChannelState) {
        if (!preserveChannelState) cancelRetry();
        if (!preserveChannelState) {
            clearChannelSwitch();
            portalChannelState.reset();
        }
        portalGeneration++;
        activeSessionId = "";
        webView.stopLoading();
        if (!preserveChannelState) {
            viewportWidth = 1920;
            viewportHeight = 1080;
            viewportX = viewportY = 0;
            setExpandedVideo(false);
        }
        if (facadeScript != null) {
            facadeScript.remove();
            facadeScript = null;
            WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME);
        }
        portalReply = null;
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE);
        channelPanel.dismiss();
        if (!preserveChannelState) channelButton.setVisibility(View.GONE);
    }

    private void onPortalUnavailable(String reason) {
        String pendingId = portalChannelState.pendingChannelId();
        portalGeneration++;
        activeSessionId = "";
        channelSessionId = "";
        portalReply = null;
        portalChannelState.unavailable();
        waitingForChannel = pendingId != null;
        if (player != null && player.getMediaItemCount() > 0) {
            webView.stopLoading();
            webView.setVisibility(View.GONE);
            Toast.makeText(activity, "Portal mất kết nối · luồng phát vẫn tiếp tục", Toast.LENGTH_SHORT).show();
            emit("portal.unavailable", reason);
            if (pendingId != null && !channelReconnectAttempted) {
                channelReconnectAttempted = true;
                if (portalChannelState.selectWhileUnavailable(pendingId)) emit("ui.portal-reconnect", pendingId);
            }
        } else {
            emit("portal.error", reason);
        }
    }

    private void sendChannelRequest(String id) {
        if (portalReply == null || channelSessionId.isEmpty() || channelRequestSent) return;
        channelRequestSent = true;
        switchingChannelId = id;
        ui.removeCallbacks(channelSwitchTimeout);
        ui.postDelayed(channelSwitchTimeout, 15000);
        portalReply.postMessage("{\"version\":1,\"sessionId\":" + JSONObject.quote(channelSessionId)
                + ",\"type\":\"channels.play\",\"payload\":{\"id\":" + JSONObject.quote(id) + "}}");
    }

    private void onChannelMessage(String json) {
        try {
            JSONObject msg = new JSONObject(json);
            if (msg.optInt("version") != 1 || !activeSessionId.equals(msg.optString("sessionId"))) return;
            if ("channels.error".equals(msg.optString("type"))) {
                String responseId = msg.optJSONObject("payload") == null ? "" : msg.optJSONObject("payload").optString("id");
                if (!switchingChannelId.equals(responseId)) return;
                String latestId = portalChannelState.pendingChannelId();
                if (latestId != null && !latestId.equals(responseId)) {
                    channelRequestSent = false;
                    sendChannelRequest(latestId);
                    return;
                }
                ui.removeCallbacks(channelSwitchTimeout);
                clearChannelSwitch();
                suppressPortalStop = true;
                if (player.getPlayerError() != null) scheduleRetry();
                setExpandedVideo(false);
                channelPanel.setVisibility(View.VISIBLE);
                Toast.makeText(activity, "Không mở được kênh", Toast.LENGTH_SHORT).show();
                return;
            }
            if ("channels.play.accepted".equals(msg.optString("type"))) {
                String responseId = msg.optJSONObject("payload") == null ? "" : msg.optJSONObject("payload").optString("id");
                if (!switchingChannelId.equals(responseId)) return;
                return;
            }
            if (!"channels.data".equals(msg.optString("type"))) return;
            channelSessionId = msg.optString("sessionId");
            portalChannelState.ready(channelSessionId);
            channelPanel.setData(msg.getJSONObject("payload"));
            channelButton.setVisibility(View.VISIBLE);
            if (!portalChannelState.hasPendingChannel()) suppressPortalStop = false;
            if (portalChannelState.canSendPending(channelSessionId)) sendChannelRequest(portalChannelState.pendingChannelId());
        } catch (JSONException e) {
            Log.w(LOG_TAG, "bad channels message: " + e);
        }
    }

    /** Native ⟳ ⌂ 🎮 bar (and the remote it toggles) above the WebView; uGUI is below the WebView and cannot be tapped there. */
    public void setOverlayVisible(boolean visible) {
        ui.post(() -> {
            overlayEnabled = visible;
            controlsHidden = false;
            overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
            updatePlaybackControls();
            if (!visible) {
                remote.setVisibility(View.GONE);
                channelPanel.dismiss();
            }
        });
    }

    /**
     * Stalker portals only understand remote keys, not touch. Drag = arrow keys (one step per 48dp, so a long drag
     * scrolls a list), tap = OK, long press = Menu. All touches are consumed; the page never sees them.
     */
    private View.OnTouchListener touchAsRemote() {
        float step = dp(48);
        GestureDetector detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            private float dx, dy;

            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                dx = dy = 0;
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (controlsHidden) { // first tap brings the controls back
                    setControlsHidden(false);
                    return true;
                }
                if (expandedVideo) { setControlsHidden(true); return true; }
                // Full-screen video in the portal: tap hides the controls; anywhere else it is OK.
                webView.evaluateJavascript(PORTAL_FULLSCREEN_JS, result -> {
                    if ("true".equals(result)) setControlsHidden(true);
                    else sendKeyOnUi(KeyEvent.KEYCODE_ENTER);
                });
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                sendKeyOnUi(KeyEvent.KEYCODE_F11); // MAG Menu
            }

            @Override
            public boolean onScroll(MotionEvent start, @NonNull MotionEvent e, float distanceX, float distanceY) {
                dx -= distanceX; // distance* is last minus current; flip to finger direction
                dy -= distanceY;
                while (Math.abs(dx) >= step || Math.abs(dy) >= step) {
                    if (Math.abs(dx) >= Math.abs(dy)) {
                        sendKeyOnUi(dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                        dx -= Math.signum(dx) * step;
                    } else {
                        sendKeyOnUi(dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
                        dy -= Math.signum(dy) * step;
                    }
                }
                return true;
            }
        });
        return (v, event) -> {
            detector.onTouchEvent(event);
            return true;
        };
    }

    /**
     * Phone keyboard for the portal's own text fields (search, login): the portal only reads key events, so the
     * entered text is typed into the focused field as real key presses.
     */
    private void promptText() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("Nhập để tìm");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(input)
                .setPositiveButton("OK", (d, which) -> typeText(input.getText().toString()))
                .setNegativeButton("Huỷ", null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    private void typeText(String text) {
        webView.requestFocus();
        // ponytail: only characters on a virtual keyboard (Latin letters, digits, punctuation); accented text is dropped.
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) {
            Log.w(LOG_TAG, "text has characters without a key mapping: " + text);
            return;
        }
        for (KeyEvent event : events) webView.dispatchKeyEvent(event);
    }

    private void setControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        overlay.setVisibility(hidden || !overlayEnabled ? View.GONE : View.VISIBLE);
        if (hidden) remote.setVisibility(View.GONE);
        updatePlaybackControls();
    }

    private void clearChannelSwitch() {
        ui.removeCallbacks(channelSwitchTimeout);
        waitingForChannel = false;
        switchingChannelId = "";
        channelRequestSent = false;
        channelReconnectAttempted = false;
        suppressPortalStop = false;
        portalChannelState.pendingHandled();
    }

    private void onViewportMessage(String json) {
        try {
            JSONObject message = new JSONObject(json);
            if (message.optInt("version") != 1 || !activeSessionId.equals(message.optString("sessionId"))) return;
            JSONObject payload = message.getJSONObject("payload");
            double width = payload.getDouble("width"), height = payload.getDouble("height");
            double x = payload.getDouble("x"), y = payload.getDouble("y");
            if (VideoViewport.scale(1920, 1080, width, height, x, y) == null) return;
            viewportWidth = width; viewportHeight = height; viewportX = x; viewportY = y;
            setExpandedVideo(false); // Portal navigation owns the viewport; manual enlargement is temporary.
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Invalid video viewport");
        }
    }

    private void setExpandedVideo(boolean expanded) {
        expandedVideo = expanded;
        webView.setAlpha(expanded ? 0f : 1f); // Keep portal layout and touch/Back dispatch alive.
        if (sizeButton != null) sizeButton.setText(expanded ? "Thu nhỏ" : "Phóng to");
        applyVideoViewport();
    }

    private void applyVideoViewport() {
        if (stage == null || stage.getWidth() == 0 || stage.getHeight() == 0) return;
        int[] rect = VideoViewport.scale(stage.getWidth(), stage.getHeight(),
                expandedVideo ? 1920 : viewportWidth, expandedVideo ? 1080 : viewportHeight,
                expandedVideo ? 0 : viewportX, expandedVideo ? 0 : viewportY);
        if (rect == null) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(rect[0], rect[1], Gravity.TOP | Gravity.LEFT);
        params.leftMargin = rect[2]; params.topMargin = rect[3];
        videoView.setLayoutParams(params);
    }

    private void updatePlaybackControls() {
        if (player == null || playbackControls == null) return;
        boolean active = player.getMediaItemCount() > 0;
        playbackControls.setVisibility(overlayEnabled && !controlsHidden && active ? View.VISIBLE : View.GONE);
        pauseButton.setEnabled(player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING);
        pauseButton.setText(player.getPlayWhenReady() ? "Tạm dừng" : "Tiếp tục");
    }

    private void cancelRetry() {
        ui.removeCallbacks(retryStream);
        retryScheduled = false;
    }

    private void scheduleRetry() {
        if (retryScheduled || waitingForChannel || player.getMediaItemCount() == 0) return;
        long delay = reloadPolicy.nextDelayMillis();
        if (delay < 0) return;
        retryScheduled = true;
        ui.postDelayed(retryStream, delay);
        Toast.makeText(activity, "Lỗi stream · thử lại sau " + (delay / 1000) + " giây", Toast.LENGTH_SHORT).show();
        updatePlaybackControls();
    }

    private void sendKeyOnUi(int keyCode) {
        webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private LinearLayout row(int gravity, boolean fullWidth) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackground(PortalStyle.surface(activity, PortalStyle.BACKGROUND, PortalStyle.BORDER));
        layout.setPadding(dp(6), dp(6), dp(6), dp(6));
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        android.graphics.drawable.GradientDrawable divider = new android.graphics.drawable.GradientDrawable();
        divider.setColor(Color.TRANSPARENT);
        divider.setSize(dp(6), 1);
        layout.setDividerDrawable(divider);
        layout.setVisibility(View.GONE);
        layout.setLayoutParams(new FrameLayout.LayoutParams(
                fullWidth ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity));
        return layout;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        PortalStyle.button(button);
        button.setMinWidth(dp(48));
        button.setFocusable(false); // keep focus (and Back) on the WebView
        button.setOnClickListener(onClick);
        return button;
    }

    private void toggleChannels() {
        if (channelPanel.getVisibility() == View.VISIBLE) {
            channelPanel.dismiss();
            webView.requestFocus();
        } else {
            remote.setVisibility(View.GONE);
            channelPanel.setVisibility(View.VISIBLE);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

    public void setPortalVisible(boolean visible) {
        ui.post(() -> {
            webView.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) webView.requestFocus(); // so the Back key reaches the portal
        });
    }

    /** @param headersJson flat JSON object of request headers, e.g. {"User-Agent":"...","Referer":"..."}; may be null. */
    public void play(String url, String headersJson) {
        Map<String, String> headers;
        try {
            headers = parseHeaders(headersJson);
        } catch (JSONException e) {
            emit("error", "BAD_HEADERS");
            return;
        }
        ui.post(() -> {
            cancelRetry();
            clearChannelSwitch();
            reloadPolicy.reset();
            String scheme = Uri.parse(url).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                emit("error", "UNSUPPORTED_STREAM_SCHEME");
                return;
            }
            // ponytail: headers go to every host the stream touches; add a host-filtering OkHttp interceptor when a portal needs cookies.
            String userAgent = headers.remove("User-Agent"); // the factory's own UA would override a plain header
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            // https://developer.android.com/media/media3/exoplayer/live-streaming
            // For timeline-aware live sources (e.g. HLS), leave room behind the live edge.
            // Progressive live TS still benefits from LoadControl's startup/rebuffer thresholds.
            MediaItem mediaItem = new MediaItem.Builder().setUri(url)
                    .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(12_000)
                            .build())
                    .build();
            player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(mediaItem));
            player.prepare();
            player.play();
            videoView.setVisibility(View.VISIBLE);
            applyVideoViewport();
            updatePlaybackControls();
        });
    }

    public void setPaused(boolean paused) {
        ui.post(() -> player.setPlayWhenReady(!paused));
    }

    public void setVolume(float volume) {
        ui.post(() -> player.setVolume(volume));
    }

    public void stop() {
        ui.post(() -> stopOnUi(true));
    }

    private void stopOnUi(boolean explicit) {
        clearChannelSwitch();
        suppressPortalStop = true;
        explicitPlaybackStopped = explicit;
        cancelRetry();
        reloadPolicy.reset();
        player.stop();
        player.clearMediaItems();
        updatePlaybackControls();
        videoView.setVisibility(View.GONE);
        setExpandedVideo(false);
        setControlsHidden(false);
    }

    /** Ignore portal startup autoplay while a reconnect is still resolving the requested channel. */
    public void playFromPortal(String url, String headersJson) {
        ui.post(() -> {
            if (explicitPlaybackStopped) return;
            String pendingId = portalChannelState.pendingChannelId();
            if (channelRequestSent && pendingId != null && !pendingId.equals(switchingChannelId)) {
                channelRequestSent = false;
                sendChannelRequest(pendingId);
                return;
            }
            if (!suppressPortalStop || channelRequestSent) play(url, headersJson);
        });
    }

    /** Portal channel switches commonly stop before resolving the next URL; keep the old item until play arrives. */
    public void stopFromPortal() {
        ui.post(() -> {
            if (!waitingForChannel && !suppressPortalStop) stopOnUi(false);
        });
    }

    public void release() {
        ui.post(() -> {
            ui.removeCallbacks(channelSwitchTimeout);
            closePortalOnUi();
            player.release();
            webView.destroy();
            ViewGroup root = activity.findViewById(android.R.id.content);
            root.removeView(stage);
            root.removeView(overlay);
            root.removeView(remote);
            root.removeView(playbackControls);
            root.removeView(channelPanel);
        });
    }

    private void emit(String type, String data) {
        if (listener != null) listener.onEvent(type, data);
    }

    private String readAsset(String name) {
        try (InputStream in = activity.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + name, e);
        }
    }

    private static Map<String, String> parseHeaders(String json) throws JSONException {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;
        JSONObject obj = new JSONObject(json);
        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
            String key = it.next();
            map.put(key, obj.getString(key));
        }
        return map;
    }
}
