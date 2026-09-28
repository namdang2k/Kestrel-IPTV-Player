package com.stbemu.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
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
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Timeline;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
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
    private StreamInfoDialog streamInfo;
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
    private boolean streamPortalRecovery;
    private String playingChannelId = "";
    private String playingUrl = "";
    private String recoveryChannelId = "";
    private final Runnable stablePlayback = () -> {
        if (player != null && player.isPlaying()) reloadPolicy.reset();
    };
    private final Runnable bufferingTimeout = () -> {
        if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) scheduleRetry();
    };
    private final Runnable recoveryCooldown = () -> {
        if (!canRecoverStream()) return;
        reloadPolicy.reset();
        scheduleRetry();
    };
    /** Network came back: retry a failed stream now instead of waiting for the backoff or recovery cooldown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network network) {
            ui.post(() -> {
                if (!canRecoverStream() || streamPortalRecovery || waitingForChannel || player.isPlaying()) return;
                if (player.getPlayerError() == null && !retryScheduled
                        && player.getPlaybackState() != Player.STATE_BUFFERING) return;
                Log.i(LOG_TAG, "Network available; restarting stream recovery");
                cancelRetry();
                reloadPolicy.reset();
                scheduleRetry();
            });
        }
    };
    private boolean backgrounded, resumeOnStart;
    private long lastLiveResync;
    private AdaptiveLoadControl loadControl;
    private DefaultBandwidthMeter bandwidthMeter;
    private final NetworkQuality networkQuality = new NetworkQuality();
    private int lastPlaybackState = Player.STATE_IDLE;
    private boolean insufficientWarned;
    private long authFailureWindowStart;
    private int authFailures;
    /** Background = no audio from an invisible player; foreground resumes at the live edge. */
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityStopped(@NonNull Activity a) {
            if (a != activity) return;
            backgrounded = true;
            if (player == null || player.getMediaItemCount() == 0 || !player.getPlayWhenReady()) return;
            resumeOnStart = true;
            cancelRetry();
            player.setPlayWhenReady(false);
        }
        @Override public void onActivityStarted(@NonNull Activity a) {
            if (a != activity) return;
            backgrounded = false;
            if (!resumeOnStart) return;
            resumeOnStart = false;
            if (player.getMediaItemCount() == 0 || explicitPlaybackStopped) return;
            if (isLive()) player.seekToDefaultPosition();
            player.setPlayWhenReady(true);
            if (player.getPlayerError() != null) scheduleRetry();
        }
        @Override public void onActivityCreated(@NonNull Activity a, Bundle b) {}
        @Override public void onActivityResumed(@NonNull Activity a) {}
        @Override public void onActivityPaused(@NonNull Activity a) {}
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        @Override public void onActivityDestroyed(@NonNull Activity a) {}
    };
    private final Runnable recoveryDeadline = () -> {
        if (streamPortalRecovery) onPortalUnavailable("STREAM_RECOVERY_TIMEOUT");
    };
    private boolean waitingForChannel;
    private String switchingChannelId = "";
    private boolean channelRequestSent, channelReconnectAttempted, suppressPortalStop, explicitPlaybackStopped;
    private final PortalChannelState portalChannelState = new PortalChannelState();
    private final Runnable channelSwitchTimeout;
    private boolean portalVisibleRequested;
    private String pendingViewportMessage;
    private final Runnable portalLoadTimeout = () -> {
        if (portalChannelState.portalState() == PortalChannelState.PortalState.CONNECTING)
            onPortalUnavailable("PORTAL_READY_TIMEOUT");
    };
    private final Runnable retryStream = () -> {
        retryScheduled = false;
        if (!canRecoverStream() || waitingForChannel || streamPortalRecovery || player.isPlaying()) return;
        reloadPolicy.retryStarted();
        Log.i(LOG_TAG, "Stream reload attempt=" + reloadPolicy.attempts());
        player.stop(); // Also restart a stuck BUFFERING source, without clearing the media item.
        // Live: the old position's segments are gone (HTTP 404 / BehindLiveWindow); rejoin at the live edge.
        if (isLive()) player.seekToDefaultPosition();
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
            if (streamPortalRecovery) {
                onPortalUnavailable("RECOVERY_CHANNEL_TIMEOUT");
                return;
            }
            if (!channelReconnectAttempted && !switchingChannelId.isEmpty()) {
                channelReconnectAttempted = true;
                String retryId = portalChannelState.pendingChannelId();
                if (retryId == null) retryId = switchingChannelId;
                onPortalUnavailable("CHANNEL_SWITCH_TIMEOUT");
                // Without media, onPortalUnavailable reported portal.error and AppFlow reconnects; a second request
                // would reload the fresh portal again.
                if (portalChannelState.selectWhileUnavailable(retryId) && player.getMediaItemCount() > 0)
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
        reloadButton = button("Auto reload", v -> {
            cancelRetry();
            reloadPolicy.cycle();
            reloadButton.setText(reloadPolicy.mode() == ReloadPolicy.Mode.ALWAYS ? "Auto reload" :
                    reloadPolicy.mode() == ReloadPolicy.Mode.ONCE ? "Reload one" : "No reload");
            reloadButton.setContentDescription(reloadPolicy.mode() == ReloadPolicy.Mode.ALWAYS ? "Lỗi: thử stream 3 lần, sau đó tải lại portal" :
                    reloadPolicy.mode() == ReloadPolicy.Mode.ONCE ? "Lỗi: thử lại một lần" : "Lỗi: không thử lại");
            if (player.getPlayerError() != null) scheduleRetry();
            armBufferingTimeout();
        });
        reloadButton.setContentDescription("Lỗi: thử stream 3 lần, sau đó tải lại portal");
        playbackControls.addView(reloadButton);
        Button infoButton = button("Info", v -> streamInfo.show());
        infoButton.setContentDescription("Thông tin stream");
        playbackControls.addView(infoButton);
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
            reloadPolicy.reset();
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
        DefaultAllocator allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        // Resume-after-stall starts at the good-network value; each stall re-rates the link (see onStall).
        loadControl = new AdaptiveLoadControl(new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(50_000, 60_000, 3_000, 3_000)
                .build(), NetworkQuality.resumeAfterStallMs(NetworkQuality.Tier.GOOD));
        bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(activity);
        player = new ExoPlayer.Builder(activity).setLoadControl(loadControl).setBandwidthMeter(bandwidthMeter)
                // Live: each stall moves the target 2 s further behind the edge (default 0.5 s), up to the item's max offset;
                // it drifts back while playback is smooth.
                .setLivePlaybackSpeedControl(new DefaultLivePlaybackSpeedControl.Builder()
                        .setTargetLiveOffsetIncrementOnRebufferMs(2000).build())
                .build();
        player.setWakeMode(C.WAKE_MODE_NETWORK); // high-performance Wi-Fi lock while playing: no power-save latency spikes
        streamInfo = new StreamInfoDialog(activity, ui, player, allocator, this::networkSummary);
        // Playback diagnostics in logcat (tag EventLogger): dropped frames, formats/bitrate, buffering, errors.
        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) player.addAnalyticsListener(new EventLogger());
        player.setVideoSurfaceView(videoView);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                emit("state", String.valueOf(state)); // Player.STATE_IDLE=1, BUFFERING=2, READY=3, ENDED=4
                if (state == Player.STATE_BUFFERING && lastPlaybackState == Player.STATE_READY && player.getPlayWhenReady()) onStall();
                lastPlaybackState = state;
                if (state == Player.STATE_READY) rejoinIfNearLiveWindowStart();
                updatePlaybackControls();
                armBufferingTimeout();
                if (state == Player.STATE_ENDED && isLive())
                    scheduleRetry();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updatePlaybackControls();
                armBufferingTimeout();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                emit("isPlaying", String.valueOf(isPlaying));
                ui.removeCallbacks(stablePlayback);
                if (isPlaying) {
                    cancelRetry();
                    ui.postDelayed(stablePlayback, 10000);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // Fell behind the live window (e.g. after a stall): rejoin the live edge at once, without using the
                // retry budget or telling the portal. A second one within 10 s is treated as a real failure.
                long now = SystemClock.elapsedRealtime();
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && now - lastLiveResync > 10000
                        && canRecoverStream() && !waitingForChannel && !streamPortalRecovery) {
                    lastLiveResync = now;
                    Log.i(LOG_TAG, "Behind live window; rejoining live edge");
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                emit("error", error.getErrorCodeName());
                if (error instanceof ExoPlaybackException && ((ExoPlaybackException) error).type == ExoPlaybackException.TYPE_RENDERER
                        && ((ExoPlaybackException) error).rendererFormatSupport != C.FORMAT_HANDLED) {
                    // The device cannot decode this format (e.g. 4K HEVC 10-bit); retries and portal reloads cannot help.
                    Format format = ((ExoPlaybackException) error).rendererFormat;
                    Log.w(LOG_TAG, "Unsupported format, not retrying: " + format);
                    cancelRetry();
                    Toast.makeText(activity, "Máy không giải mã được kênh này" + (format == null ? "" : " (" + format.sampleMimeType
                            + " " + format.width + "x" + format.height + ")"), Toast.LENGTH_LONG).show();
                    return;
                }
                scheduleRetry();
            }
        });
        activity.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        emit("ready", "");
    }

    private void installPortalWebViewClient(int generation) {
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (generation != portalGeneration || "about:blank".equals(url)) return;
                // A portal can reload itself without openPortal() or any WebView error.
                portalChannelState.connecting();
                portalReply = null;
                pendingViewportMessage = null;
                channelSessionId = "";
                channelRequestSent = false;
                if (player.getMediaItemCount() > 0) {
                    suppressPortalStop = true;
                    updatePortalVisibility();
                    ui.removeCallbacks(portalLoadTimeout);
                    ui.postDelayed(portalLoadTimeout, 30000);
                }
            }

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
            boolean preservingPlayback = player.getMediaItemCount() > 0;
            boolean reconnectingForChannel = preservingPlayback && portalChannelState.hasPendingChannel();
            closePortalOnUi(preservingPlayback || portalChannelState.hasPendingChannel()); // keep a queued channel
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
                if (data.contains("\"type\":\"portal.auth_failed\"")) { onPortalAuthFailed(); return; }
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
        ui.removeCallbacks(portalLoadTimeout);
        pendingViewportMessage = null;
        if (!preserveChannelState) portalVisibleRequested = false;
        if (!preserveChannelState) {
            streamPortalRecovery = false;
            playingChannelId = "";
            playingUrl = "";
            recoveryChannelId = "";
            ui.removeCallbacks(recoveryDeadline);
            ui.removeCallbacks(bufferingTimeout);
            ui.removeCallbacks(stablePlayback);
        }
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
        ui.removeCallbacks(portalLoadTimeout);
        Log.w(LOG_TAG, "Portal unavailable generation=" + portalGeneration + " reason=" + reason);
        String pendingId = portalChannelState.pendingChannelId();
        portalGeneration++;
        activeSessionId = "";
        channelSessionId = "";
        portalReply = null;
        channelRequestSent = false;
        suppressPortalStop = true;
        portalChannelState.unavailable();
        waitingForChannel = pendingId != null;
        if (player != null && player.getMediaItemCount() > 0) {
            webView.stopLoading();
            webView.setVisibility(View.GONE);
            Toast.makeText(activity, player.isPlaying() ? "Portal mất kết nối · luồng phát vẫn tiếp tục"
                    : "Portal mất kết nối · đang khôi phục stream", Toast.LENGTH_SHORT).show();
            emit("portal.unavailable", reason);
            if (streamPortalRecovery) {
                finishFailedStreamRecovery();
                return;
            }
            if (pendingId != null && !channelReconnectAttempted) {
                channelReconnectAttempted = true;
                if (portalChannelState.selectWhileUnavailable(pendingId)) emit("ui.portal-reconnect", pendingId);
            }
        } else {
            emit("portal.error", reason);
        }
    }

    /** The server dropped this portal session (e.g. the same MAC signed in elsewhere): re-handshake, at most 3 times per 5 min. */
    private void onPortalAuthFailed() {
        if (portalChannelState.portalState() != PortalChannelState.PortalState.READY) return; // login failure: the portal's own flow
        long now = SystemClock.elapsedRealtime();
        if (now - authFailureWindowStart > 300_000) {
            authFailureWindowStart = now;
            authFailures = 0;
        }
        boolean retry = ++authFailures <= 3;
        Log.w(LOG_TAG, "Portal session rejected (Authorization failed.) count=" + authFailures);
        // With media the stream keeps its URL and needs no portal: reconnect lazily (channel switch, stream recovery, ⟳),
        // not in the background, where it costs bandwidth/CPU during playback and kicks a device sharing the MAC.
        onPortalUnavailable(retry ? "AUTH_FAILED" : "AUTH_FAILED_REPEATED"); // without media: portal.error, AppFlow decides
        if (!retry) Toast.makeText(activity, "MAC đang được dùng ở thiết bị khác · portal bị đăng xuất", Toast.LENGTH_LONG).show();
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
                if (streamPortalRecovery) {
                    onPortalUnavailable("RECOVERY_CHANNEL_FAILED");
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
            ui.removeCallbacks(portalLoadTimeout);
            if (pendingViewportMessage != null) {
                String viewport = pendingViewportMessage;
                pendingViewportMessage = null;
                onViewportMessage(viewport);
            }
            updatePortalVisibility();
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
                streamInfo.dismiss();
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
            // Apply startup layout only when the portal is usable, preserving the old stream meanwhile.
            if (!portalChannelState.canPresent(player.getMediaItemCount() > 0)) {
                pendingViewportMessage = json;
                return;
            }
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
        // View-scoped keep-awake applies only while this video is visible in the foreground.
        // Include buffering so a network stall does not let the screen sleep mid-stream.
        int playbackState = player.getPlaybackState();
        videoView.setKeepScreenOn(active && player.getPlayWhenReady()
                && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING));
        if (!active && streamInfo != null) streamInfo.dismiss();
        playbackControls.setVisibility(overlayEnabled && !controlsHidden && active ? View.VISIBLE : View.GONE);
        pauseButton.setEnabled(player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING);
        pauseButton.setText(player.getPlayWhenReady() ? "Tạm dừng" : "Tiếp tục");
    }

    private void cancelRetry() {
        ui.removeCallbacks(retryStream);
        ui.removeCallbacks(recoveryCooldown);
        retryScheduled = false;
    }

    /**
     * Stalls leave a live stream further behind; near the start of a short live window its next segments expire
     * (HTTP 404 / BehindLiveWindow) and cost a full re-prepare. Jump back to the target offset while still playable.
     */
    private void rejoinIfNearLiveWindowStart() {
        if (!player.isCurrentMediaItemLive() || player.getCurrentTimeline().isEmpty()) return;
        Timeline.Window window = player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window());
        long position = player.getCurrentPosition();
        // The second condition avoids a seek loop when the default position is itself at the window start.
        if (position < 4000 && window.getDefaultPositionMs() - position > 5000) {
            Log.i(LOG_TAG, "Near live window start (" + position + " ms); rejoining live edge");
            player.seekToDefaultPosition();
        }
    }

    /** A stall re-rates the link and sets how much to buffer before playback resumes. */
    private void onStall() {
        long now = SystemClock.elapsedRealtime();
        networkQuality.stallStarted(now);
        long bandwidth = bandwidthMeter.getBitrateEstimate(), bitrate = streamBitrate();
        NetworkQuality.Tier tier = networkQuality.tier(now, bandwidth, bitrate);
        loadControl.setResumeAfterStallMs(NetworkQuality.resumeAfterStallMs(tier));
        Log.i(LOG_TAG, "Stall: tier=" + tier + " bandwidth=" + bandwidth + " bitrate=" + bitrate
                + " stalls5m=" + networkQuality.stalls(now) + " resumeAfter=" + loadControl.resumeAfterStallMs() + "ms");
        if (tier == NetworkQuality.Tier.INSUFFICIENT && !insufficientWarned) {
            insufficientWarned = true;
            Toast.makeText(activity, "Mạng không đủ cho kênh này: cần ~" + mbps(bitrate) + ", đo được " + mbps(bandwidth),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Video + audio bitrate from the source; 0 when it does not say. */
    private long streamBitrate() {
        Format video = player.getVideoFormat(), audio = player.getAudioFormat();
        long total = 0;
        if (video != null && video.bitrate > 0) total += video.bitrate;
        if (audio != null && audio.bitrate > 0) total += audio.bitrate;
        return total;
    }

    private String networkSummary() {
        long now = SystemClock.elapsedRealtime();
        long bandwidth = bandwidthMeter.getBitrateEstimate(), bitrate = streamBitrate();
        NetworkQuality.Tier tier = networkQuality.tier(now, bandwidth, bitrate);
        String label = tier == NetworkQuality.Tier.GOOD ? "Tốt" : tier == NetworkQuality.Tier.FAIR ? "Trung bình"
                : tier == NetworkQuality.Tier.WEAK ? "Yếu" : "Không đủ cho kênh này";
        return "Băng thông đo được: " + mbps(bandwidth)
                + "\nDư địa so với bitrate: " + (bitrate > 0 ? String.format(java.util.Locale.getDefault(), "%.1f×", (double) bandwidth / bitrate) : "Không rõ")
                + "\nGiật trong 5 phút: " + networkQuality.stalls(now)
                + "\nChất lượng mạng: " + label
                + "\nNạp trước khi phát lại sau giật: " + loadControl.resumeAfterStallMs() / 1000 + " giây";
    }

    private static String mbps(long bps) {
        return bps > 0 ? String.format(java.util.Locale.getDefault(), "%.1f Mbps", bps / 1_000_000.0) : "?";
    }

    private boolean isLive() {
        return player.isCurrentMediaItemLive() || player.getDuration() == C.TIME_UNSET;
    }

    private boolean canRecoverStream() {
        return player != null && player.getMediaItemCount() > 0 && player.getPlayWhenReady()
                && !explicitPlaybackStopped && reloadPolicy.mode() != ReloadPolicy.Mode.NONE;
    }

    private void armBufferingTimeout() {
        ui.removeCallbacks(bufferingTimeout);
        if (canRecoverStream() && !streamPortalRecovery && player.getPlaybackState() == Player.STATE_BUFFERING)
            ui.postDelayed(bufferingTimeout, 30000);
    }

    private void scheduleRetry() {
        if (!canRecoverStream() || retryScheduled || waitingForChannel || streamPortalRecovery) return;
        ui.removeCallbacks(stablePlayback);
        ui.removeCallbacks(bufferingTimeout);
        long delay = reloadPolicy.nextDelayMillis();
        if (delay < 0) {
            if (reloadPolicy.requestPortalReload()) recoverStreamThroughPortal();
            return;
        }
        retryScheduled = true;
        ui.postDelayed(retryStream, delay);
        Toast.makeText(activity, "Lỗi stream · thử lại sau " + (delay / 1000) + " giây", Toast.LENGTH_SHORT).show();
        updatePlaybackControls();
    }

    private void recoverStreamThroughPortal() {
        cancelRetry();
        streamPortalRecovery = true;
        channelReconnectAttempted = true;
        // Replay a native-selected channel to obtain a fresh URL, not the expired URL.
        String targetId = recoveryChannelId.isEmpty() ? playingChannelId : recoveryChannelId;
        if (!portalChannelState.hasPendingChannel() && !targetId.isEmpty())
            portalChannelState.select(targetId);
        portalChannelState.connecting();
        waitingForChannel = portalChannelState.hasPendingChannel();
        suppressPortalStop = true;
        updatePortalVisibility();
        Log.i(LOG_TAG, "Stream retries exhausted; reconnecting portal");
        Toast.makeText(activity, "Stream lỗi sau 3 lần thử · đang tải lại portal", Toast.LENGTH_SHORT).show();
        ui.removeCallbacks(portalLoadTimeout);
        ui.postDelayed(portalLoadTimeout, 30000);
        ui.removeCallbacks(recoveryDeadline);
        ui.postDelayed(recoveryDeadline, 45000);
        emit("ui.portal-reconnect", "stream-recovery");
    }

    private void finishFailedStreamRecovery() {
        ui.removeCallbacks(recoveryDeadline);
        streamPortalRecovery = false;
        String pendingId = portalChannelState.pendingChannelId();
        if (pendingId != null) recoveryChannelId = pendingId;
        clearChannelSwitch();
        suppressPortalStop = true;
        cancelRetry();
        // The network may still be down. Start a fresh bounded cycle after a cooldown.
        if (canRecoverStream() && reloadPolicy.mode() == ReloadPolicy.Mode.ALWAYS)
            ui.postDelayed(recoveryCooldown, 15000);
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
            portalVisibleRequested = visible;
            updatePortalVisibility();
        });
    }

    private void updatePortalVisibility() {
        boolean visible = portalVisibleRequested && !streamPortalRecovery
                && portalChannelState.canPresent(player.getMediaItemCount() > 0);
        webView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) webView.requestFocus();
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
            if (explicitPlaybackStopped) return;
            String scheme = Uri.parse(url).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                emit("error", "UNSUPPORTED_STREAM_SCHEME");
                return;
            }
            cancelRetry();
            if (streamPortalRecovery || !url.equals(playingUrl)) reloadPolicy.reset();
            if (!url.equals(playingUrl)) insufficientWarned = false;
            playingChannelId = channelRequestSent ? switchingChannelId : url.equals(playingUrl) ? playingChannelId : "";
            playingUrl = url;
            recoveryChannelId = "";
            streamPortalRecovery = false;
            ui.removeCallbacks(recoveryDeadline);
            ui.removeCallbacks(stablePlayback);
            clearChannelSwitch();
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
                            .setMaxOffsetMs(18_000) // stall increments stop here: short IPTV windows (~23 s) expire older segments
                            .build())
                    .build();
            player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(mediaItem));
            player.prepare();
            if (backgrounded) resumeOnStart = true; // e.g. portal recovery finished while the app was hidden
            else player.play();
            videoView.setVisibility(View.VISIBLE);
            applyVideoViewport();
            updatePlaybackControls();
            updatePortalVisibility();
            armBufferingTimeout();
        });
    }

    public void setPaused(boolean paused) {
        ui.post(() -> {
            if (paused) cancelRetry();
            player.setPlayWhenReady(!paused);
            if (!paused && player.getPlayerError() != null) scheduleRetry();
        });
    }

    public void setVolume(float volume) {
        ui.post(() -> player.setVolume(volume));
    }

    public void stop() {
        ui.post(() -> stopOnUi(true));
    }

    private void stopOnUi(boolean explicit) {
        streamPortalRecovery = false;
        playingChannelId = "";
        playingUrl = "";
        recoveryChannelId = "";
        ui.removeCallbacks(recoveryDeadline);
        ui.removeCallbacks(bufferingTimeout);
        ui.removeCallbacks(stablePlayback);
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
            if (!suppressPortalStop || channelRequestSent
                    || (streamPortalRecovery && !portalChannelState.hasPendingChannel())) play(url, headersJson);
        });
    }

    /** Portal channel switches commonly stop before resolving the next URL; keep the old item until play arrives. */
    public void stopFromPortal() {
        ui.post(() -> {
            // A portal's reaction to player.error must not erase the retryable item.
            if (player.getMediaItemCount() > 0 && (player.getPlayerError() != null
                    || retryScheduled || streamPortalRecovery
                    || (reloadPolicy.attempts() > 0 && player.getPlaybackState() != Player.STATE_READY))) return;
            if (!waitingForChannel && !suppressPortalStop) stopOnUi(false);
        });
    }

    public void release() {
        ui.post(() -> {
            ui.removeCallbacks(channelSwitchTimeout);
            activity.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            if (streamInfo != null) streamInfo.dismiss();
            closePortalOnUi();
            videoView.setKeepScreenOn(false);
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
