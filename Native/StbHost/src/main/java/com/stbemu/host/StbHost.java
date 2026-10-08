package com.stbemu.host;

import android.app.Activity;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Timeline;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
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
    private final android.content.ComponentCallbacks themeConfiguration = new android.content.ComponentCallbacks() {
        @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
            ui.post(() -> PortalStyle.update(activity, activity.findViewById(android.R.id.content)));
        }
        @Override public void onLowMemory() { }
    };

    /** JS object name the facade uses to talk to native (WebViewCompat.addWebMessageListener). */
    private static final String BRIDGE_NAME = "stbNative";
    private static final String LOG_TAG = "StbPortal";

    private SurfaceView videoView;
    private Stage16x9 stage;
    private FrameLayout nativeLayer; // every native view, hidden as one while Unity owns an ad or modal
    private FrameLayout rewardPrompt; // dims the still-playing stream; Unity sets its text and buttons
    private TextView rewardText;
    private LinearLayout portalLoading;
    private TextView portalLoadingMessage;
    private TextView portalStatusTitle;
    private android.widget.ProgressBar portalStatusSpinner;
    private Button portalStatusPrimary, portalStatusBack;
    private Button rewardWatch, rewardExit;
    private WebView webView;
    private ExoPlayer player;
    private StreamInfoDialog streamInfo;
    private String facadeJs;
    private ScriptHandler facadeScript;
    private JavaScriptReplyProxy portalReply;
    private LinearLayout overlay;
    private ChannelPanel channelPanel;
    private TvScreen tvScreen;
    private HomeScreen home; // profile list + Setting tab; Unity drives it through showProfileHome/hideProfileHome
    private ProDialog proDialog; // Upgrade to Pro sheet; Unity drives it through showProPanel/setProState
    private ProfileEditorDialog profileEditor; // its own window: hidden/released explicitly, unlike nativeLayer children
    private LogoLoader logoLoader;
    private final boolean nativeUi = true; // native TV screen; the portal's own UI is never shown (data source only)
    private Button channelButton;
    private String channelSessionId = ""; // from channels.data; the facade drops messages without it
    private boolean controlsHidden;
    private LinearLayout playbackControls;
    private Button pauseButton, sizeButton;
    private boolean expandedVideo, overlayEnabled;
    private double viewportWidth = 1920, viewportHeight = 1080, viewportX, viewportY;
    private int portalGeneration;
    private String activeSessionId = "";
    private String accountConfigKey = "";
    private final ReloadPolicy reloadPolicy = new ReloadPolicy();
    private final HevcDecoderSelector hevcDecoders = new HevcDecoderSelector();
    private boolean decoderFailureTerminal;
    private Button reloadButton;
    private boolean retryScheduled;
    private boolean streamPortalRecovery;
    private boolean resolvingStreamLink;
    private volatile long playGeneration;
    private long recoveryStartedAt = -1;
    private final java.util.ArrayDeque<Long> recoveryIncidents = new java.util.ArrayDeque<>();
    private final Runnable streamDeadline = () -> {
        if (canRecoverStream() && !this.backgrounded && !this.playbackGate) failStream("RECOVERY_TIMEOUT");
    };
    private String playingChannelId = "";
    private String playingUrl = "";
    private String playingHeaders;
    // ⏹ keeps the stopped channel's URL: replaying that channel tries it first and needs the portal only if it has expired.
    private String stoppedChannelId = "", stoppedUrl = "", stoppedHeaders;
    private boolean replayingStoppedUrl;
    private final Runnable stoppedUrlTimeout = () -> {
        if (replayingStoppedUrl && !player.isPlaying()) fallBackFromStoppedUrl();
    };
    // ⏹ leaves no media, but the viewer is still in the portal: an outage then waits for the next pick, no Error screen.
    private boolean stoppedByUser;
    private String recoveryChannelId = "";
    private final Runnable stablePlayback = () -> {
        if (player != null && player.isPlaying()) {
            reloadPolicy.reset();
            recoveryStartedAt = -1;
        }
    };
    private final Runnable bufferingTimeout = () -> {
        if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) scheduleRetry();
    };
    /** A network change may retry sooner, but cannot reset the incident budget. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network network) {
            ui.post(() -> {
                if (!canRecoverStream() || streamPortalRecovery || waitingForChannel || player.isPlaying()) return;
                if (player.getPlayerError() == null && !retryScheduled
                        && player.getPlaybackState() != Player.STATE_BUFFERING) return;
                Log.i(LOG_TAG, "Network available; restarting stream recovery");
                cancelRetry();
                scheduleRetry();
            });
        }
    };
    private boolean backgrounded, resumeOnStart;
    private long lastLiveResync;
    private DefaultBandwidthMeter bandwidthMeter;
    private final NetworkQuality networkQuality = new NetworkQuality();
    private int lastPlaybackState = Player.STATE_IDLE;
    private boolean playbackGate;
    private boolean resumeAfterGate;
    private long authFailureWindowStart;
    /** ISP DNS often blocks or slows IPTV portals; shown with auth, network and stream failures. */
    private static final String DNS_HINT = "Try again with Cloudflare DNS (1.1.1.1)";
    /** Channel id → portal cmd, kept across reconnects: a reconnect asks create_link without the 10-16 s channel list. */
    private final Map<String, String> channelCmds = new HashMap<>();
    private boolean fastRequestSent, fastLinkArrived;
    private String heldChannelId = ""; // its first play() waits under the gate for Unity's interstitial
    private long portalOpenedAt; // logcat timings of a (re)connect: "+N ms" since openPortal
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
            if (playbackGate || !resumeOnStart) return;
            resumeOnStart = false;
            if (player.getMediaItemCount() == 0 || explicitPlaybackStopped) return;
            if (isLive()) player.seekToDefaultPosition();
            player.setPlayWhenReady(true);
            if (player.getPlayerError() != null) scheduleRetry();
        }
        @Override public void onActivityCreated(@NonNull Activity a, Bundle b) {}
        @Override public void onActivityResumed(@NonNull Activity a) {
            if (a == activity) PortalStyle.update(activity, activity.findViewById(android.R.id.content));
        }
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


    public StbHost(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        PortalStyle.update(activity, null);
        activity.registerComponentCallbacks(themeConfiguration);
        channelSwitchTimeout = () -> {
            if (!waitingForChannel) return;
            if (streamPortalRecovery) {
                reconnectPortalForStream("LINK_RESOLVE_TIMEOUT");
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
            tvScreen.channelFailed(player.getMediaItemCount() > 0 ? "the portal is not responding · still playing the previous channel"
                    : "the portal is not responding", playingChannelId);
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
        // Portal JS errors (incl. uncaught) → logcat; other console output is dropped.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    Log.w(LOG_TAG, message.message() + " @" + message.sourceId() + ":" + message.lineNumber());
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
        playbackControls = row(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, false);
        playbackControls.setOrientation(LinearLayout.VERTICAL);
        ((FrameLayout.LayoutParams) playbackControls.getLayoutParams()).bottomMargin = dp(66);
        LinearLayout transport = new LinearLayout(activity);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        playbackControls.addView(transport);
        pauseButton = button("Pause", v -> setPaused(player.getPlayWhenReady()));
        transport.addView(pauseButton);
        transport.addView(button("Stop", v -> { waitingForChannel = false; stop(); }));
        sizeButton = button("Expand", v -> setExpandedVideo(!expandedVideo));
        transport.addView(sizeButton);
        reloadButton = button("Auto reload", v -> {
            cancelRetry();
            reloadPolicy.cycle();
            reloadButton.setText(reloadPolicy.mode() == ReloadPolicy.Mode.ALWAYS ? "Auto reload" :
                    reloadPolicy.mode() == ReloadPolicy.Mode.ONCE ? "Reload one" : "No reload");
            reloadButton.setContentDescription(reloadPolicy.mode() == ReloadPolicy.Mode.ALWAYS ? "On error: retry the stream twice, then resolve a fresh link" :
                    reloadPolicy.mode() == ReloadPolicy.Mode.ONCE ? "On error: retry once" : "On error: no retry");
            if (player.getPlayerError() != null) scheduleRetry();
            armBufferingTimeout();
        });
        reloadButton.setContentDescription("On error: retry the stream twice, then resolve a fresh link");
        playbackControls.addView(reloadButton);
        Button infoButton = button("Info", v -> streamInfo.show());
        infoButton.setContentDescription("Stream info");
        playbackControls.addView(infoButton);
        channelButton = button("📺", v -> toggleChannels());
        channelButton.setVisibility(View.GONE); // until the portal's channel data arrives
        overlay.addView(channelButton, 0);
        String[] actions = {"Channel list", "Reload portal", "Home"};
        for (int i = 0; i < actions.length; i++) {
            overlay.getChildAt(i).setContentDescription(actions[i]);
            overlay.getChildAt(i).setTooltipText(actions[i]);
        }
        channelPanel = new ChannelPanel(activity, id -> {
            channelPanel.dismiss();
            webView.requestFocus();
            pickChannel(id);
        }, () -> { channelPanel.dismiss(); webView.requestFocus(); });
        logoLoader = new LogoLoader(ui);
        tvScreen = new TvScreen(activity, logoLoader, new TvScreen.Actions() {
            @Override public void play(String channelId) { pickChannel(channelId); }
            @Override public void home() { emit("ui.home", ""); }
            /** Live portal: reload its channel list; otherwise reconnect, and the new session delivers the list. */
            @Override public void reconnectPortal() {
                if (portalReply == null || activeSessionId.isEmpty()
                        || portalChannelState.portalState() != PortalChannelState.PortalState.READY) { emit("ui.refresh", ""); return; }
                portalReply.postMessage("{\"version\":1,\"sessionId\":" + JSONObject.quote(activeSessionId)
                        + ",\"type\":\"portal.reload_catalog\"}");
            }
            /** Replays the current URL like ▶ after ⏹ (a fresh link only if it fails); no ad; skipped while auto reload runs. */
            @Override public boolean reloadChannel() {
                if (playbackGate || playingChannelId.isEmpty() || playingUrl.isEmpty() || retryScheduled || waitingForChannel
                        || streamPortalRecovery || resolvingStreamLink) return false;
                stoppedChannelId = playingChannelId;
                stoppedUrl = playingUrl;
                stoppedHeaders = playingHeaders;
                pickChannelAfterAds(playingChannelId);
                return true;
            }
            @Override public void togglePause() { setPaused(player.getPlayWhenReady()); }
            @Override public void stopPlayback() {
                waitingForChannel = false;
                String id = playingChannelId, url = playingUrl, headers = playingHeaders;
                stopOnUi(true);
                stoppedByUser = true;
                if (id.isEmpty() || url.isEmpty()) return;
                stoppedChannelId = id;
                stoppedUrl = url;
                stoppedHeaders = headers;
            }
            @Override public void showInfo() { streamInfo.show(); }
        });
        tvScreen.setVisibility(View.GONE);
        channelPanel.dismiss();
        channelPanel.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stage = new Stage16x9(activity); // video + portal share one centred 16:9 box; controls below stay full-screen
        (nativeUi ? tvScreen.playerBox : stage).addView(videoView, 0,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyVideoViewport();
        });
        nativeLayer = new FrameLayout(activity);
        nativeLayer.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        nativeLayer.addView(tvScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nativeLayer.addView(overlay);
        nativeLayer.addView(playbackControls);
        nativeLayer.addView(channelPanel);
        root.addView(nativeLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        channelPanel.bringToFront(); // The sheet owns its close action.
        nativeLayer.addView(buildRewardPrompt(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        home = new HomeScreen(activity, new HomeScreen.Actions() {
            @Override public void add() { emit("ui.profile.add", ""); }
            @Override public void edit(String profileId) { emit("ui.profile.edit", profileId); }
            @Override public void delete(String profileId) { emit("ui.profile.delete", profileId); }
            @Override public void connect(String profileId) { emit("ui.profile.connect", profileId); }
            @Override public void pro() { emit("ui.pro.open", ""); }
        });
        proDialog = new ProDialog(activity, new ProDialog.Actions() {
            @Override public void buy(String plan) { emit("ui.pro.buy", plan); }
            @Override public void restore() { emit("ui.pro.restore", ""); }
        });
        nativeLayer.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        profileEditor = new ProfileEditorDialog(activity, new ProfileEditorDialog.Actions() {
            @Override public void save(JSONObject form) { emit("ui.profile.save", form.toString()); }
            @Override public void cancel() { emit("ui.profile.cancel", ""); }
        });

        // Bounded IPTV buffer; device/bitrate tuning still needs playback fixtures.
        DefaultAllocator allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(5_000, 15_000, 1_500, 3_000)
                .setTargetBufferBytes(32 * 1024 * 1024)
                .build();
        bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(activity);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(activity)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(hevcDecoders);
        player = new ExoPlayer.Builder(activity, renderers).setLoadControl(loadControl).setBandwidthMeter(bandwidthMeter)
                // Live: each stall moves the target 2 s further behind the edge (default 0.5 s), up to the item's max offset;
                // it drifts back while playback is smooth.
                .setLivePlaybackSpeedControl(new DefaultLivePlaybackSpeedControl.Builder()
                        .setTargetLiveOffsetIncrementOnRebufferMs(2000).build())
                .build();
        player.setWakeMode(C.WAKE_MODE_NETWORK); // high-performance Wi-Fi lock while playing: no power-save latency spikes
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        streamInfo = new StreamInfoDialog(activity, ui, player, this::networkSummary);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                    long initializedTimestampMs, long initializationDurationMs) {
                Log.i(LOG_TAG, "Video decoder=" + decoderName);
            }
        });
        // Do not install EventLogger: upstream exceptions can contain signed stream URLs.
        player.setVideoSurfaceView(videoView);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() { emit("firstFrame", "true"); }
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
                if (isPlaying) emit("channel.playing", playingChannelId);
                emit("isPlaying", String.valueOf(isPlaying));
                ui.removeCallbacks(stablePlayback);
                if (isPlaying) {
                    ui.removeCallbacks(streamDeadline);
                    replayingStoppedUrl = false;
                    ui.removeCallbacks(stoppedUrlTimeout);
                    cancelRetry();
                    ui.postDelayed(stablePlayback, 10000);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (tryRecoverHevcDecoder(error)) return;
                // Fell behind the live window (e.g. after a stall): rejoin the live edge at once, without using the
                // retry budget or telling the portal. A second one within 10 s is treated as a real failure.
                long now = SystemClock.elapsedRealtime();
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && now - lastLiveResync > 10000
                        && canRecoverStream() && !waitingForChannel && !streamPortalRecovery
                        && reloadPolicy.nextDelayMillis() >= 0 && beginRecovery()) {
                    reloadPolicy.retryStarted();
                    lastLiveResync = now;
                    Log.i(LOG_TAG, "Behind live window; rejoining live edge");
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                emit("error", error.getErrorCodeName());
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                        int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                        if (status == 401 || status == 403) {
                            resolveStreamLink();
                            return;
                        }
                    }
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                        || error.errorCode >= 6000) {
                    failStream(error.getErrorCodeName());
                    return;
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) {
                    // A new portal token cannot repair an incompatible decoder or bitstream.
                    decoderFailureTerminal = true;
                    ui.removeCallbacks(streamDeadline);
                    cancelRetry();
                    ui.removeCallbacks(bufferingTimeout);
                    tvScreen.channelFailed("video decoder unavailable or incompatible. Try an H.264/lower-resolution channel; see Stream info", playingChannelId);
                    return;
                }
                if (error instanceof ExoPlaybackException && ((ExoPlaybackException) error).type == ExoPlaybackException.TYPE_RENDERER
                        && ((ExoPlaybackException) error).rendererFormatSupport != C.FORMAT_HANDLED) {
                    // The device cannot decode this format (e.g. 4K HEVC 10-bit); retries and portal reloads cannot help.
                    decoderFailureTerminal = true;
                    ui.removeCallbacks(streamDeadline);
                    Format format = ((ExoPlaybackException) error).rendererFormat;
                    Log.w(LOG_TAG, "Unsupported format, not retrying: " + format);
                    cancelRetry();
                    tvScreen.channelFailed("this device cannot decode it" + (format == null ? "" : " " + format.sampleMimeType
                            + " " + format.width + "x" + format.height), playingChannelId);
                    return;
                }
                scheduleRetry();
            }
        });
        activity.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        emit("ready", "");
    }

    private boolean tryRecoverHevcDecoder(PlaybackException error) {
        if (!(error instanceof ExoPlaybackException) || explicitPlaybackStopped || player.getMediaItemCount() == 0
                || error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false;
        ExoPlaybackException playbackError = (ExoPlaybackException) error;
        if (playbackError.type != ExoPlaybackException.TYPE_RENDERER) return false;
        String failedDecoder = null;
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof MediaCodecDecoderException) {
                MediaCodecDecoderException decoderError = (MediaCodecDecoderException) cause;
                if (decoderError.codecInfo != null) failedDecoder = decoderError.codecInfo.name;
                break;
            }
        }
        try {
            if (!hevcDecoders.excludeAndHasAlternative(activity, playbackError.rendererFormat, failedDecoder)) return false;
        } catch (androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException queryError) {
            Log.w(LOG_TAG, "Unable to query alternative HEVC decoders");
            return false;
        }
        boolean resume = player.getPlayWhenReady() && !playbackGate && !backgrounded;
        boolean live = isLive();
        long position = player.getCurrentPosition();
        cancelRetry();
        ui.removeCallbacks(bufferingTimeout);
        // Stop releases the failed renderer; prepare queries the selector again for the same media source/headers.
        player.stop();
        if (live) player.seekToDefaultPosition();
        else player.seekTo(position);
        player.prepare();
        player.setPlayWhenReady(resume);
        Log.i(LOG_TAG, "HEVC decoder fallback attempt=" + hevcDecoders.attempts() + " failedDecoder=" + failedDecoder);
        return true;
    }

    private void installPortalWebViewClient(int generation) {
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (generation != portalGeneration || "about:blank".equals(url)) return;
                Log.i(LOG_TAG, "Portal page started +" + sincePortalOpen() + " ms");
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
                    ui.postDelayed(portalLoadTimeout, 180000);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (generation == portalGeneration && request.isForMainFrame())
                    onPortalUnavailable("LOAD_FAILED:" + error.getErrorCode());
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                Log.w(LOG_TAG, "Portal HTTP status=" + response.getStatusCode());
                if (generation == portalGeneration && request.isForMainFrame() && response.getStatusCode() >= 400)
                    onPortalUnavailable("HTTP_" + response.getStatusCode());
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
                try { emit("portal.error", new JSONObject(snapshotJson).getString("sessionId") + "|WEBVIEW_UNSUPPORTED"); }
                catch (JSONException ignored) { onPortalUnavailable("BAD_SNAPSHOT"); }
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
            portalOpenedAt = SystemClock.elapsedRealtime();
            Log.i(LOG_TAG, "Portal open generation=" + generation + (preservingPlayback ? " (stream kept)" : "")
                    + (reconnectingForChannel ? " (channel pending)" : ""));
            final String profileKey;
            try {
                activeSessionId = new JSONObject(snapshotJson).getString("sessionId");
                JSONObject identitySnapshot = new JSONObject(snapshotJson);
                identitySnapshot.remove("sessionId");
                identitySnapshot.remove("operationId");
                identitySnapshot.remove("forceLoginPortal");
                String identity = url + "\n" + identitySnapshot.toString() + "\n" + userAgent;
                profileKey = java.util.UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
                if (!profileKey.equals(accountConfigKey)) {
                    accountConfigKey = profileKey;
                    authFailures = 0;
                    authFailureWindowStart = SystemClock.elapsedRealtime();
                    channelCmds.clear();
                }
                String favoritesIdentity = url + "\n" + identitySnapshot.optString("mac");
                String favoritesKey = java.util.UUID.nameUUIDFromBytes(favoritesIdentity.getBytes(StandardCharsets.UTF_8)).toString();
                channelPanel.setProfile(favoritesKey);
                tvScreen.setProfile(profileKey, favoritesKey);
            } catch (JSONException e) {
                onPortalUnavailable("BAD_SNAPSHOT");
                return;
            }

            // Content cache is scoped by the immutable account/configuration fingerprint in the facade.

            Uri uri = Uri.parse(url);
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
            Set<String> rules = Collections.singleton(origin);

            webView.getSettings().setUserAgentString(userAgent == null || userAgent.isEmpty() ? null : userAgent);
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, rules, (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                if (!isMainFrame || message.getData() == null || generation != portalGeneration) return;
                boolean pageHello = portalReply == null; // first message of this page (also after the portal's reboot)
                portalReply = replyProxy;
                if (pageHello) sendFastChannelRequest();
                String data = message.getData();
                try {
                    JSONObject envelope = new JSONObject(data);
                    if (envelope.optInt("version") != 1 || !activeSessionId.equals(envelope.optString("sessionId"))) return;
                    if ("portal.failure".equals(envelope.optString("type"))) {
                        JSONObject failure = envelope.optJSONObject("payload");
                        String kind = failure == null ? "INVALID_RESPONSE" : failure.optString("kind", "INVALID_RESPONSE");
                        if (!java.util.Arrays.asList("AUTHENTICATION_REJECTED", "PROFILE_REJECTED", "INVALID_RESPONSE",
                                "FORBIDDEN", "RATE_LIMITED", "SERVER_UNAVAILABLE", "NETWORK", "INVALID_HANDSHAKE").contains(kind)) kind = "INVALID_RESPONSE";
                        if (failure != null && "get_all_channels".equals(failure.optString("stage"))) {
                            tvScreen.reloadFailed();
                            emit("portal.catalog-error", activeSessionId + "|" + kind);
                        } else {
                            String stage = failure == null ? "" : failure.optString("stage");
                            if ("get_profile".equals(stage) && !"PROFILE_REJECTED".equals(kind)) {
                                if ("RATE_LIMITED".equals(kind) && failure.optLong("retryAfter", -1) >= 0)
                                    kind += ":" + failure.optLong("retryAfter");
                                kind = "PROFILE_" + kind;
                            } else if ("handshake".equals(stage) && !"INVALID_HANDSHAKE".equals(kind)) kind = "HANDSHAKE_" + kind;
                            onPortalUnavailable(kind);
                        }
                        return;
                    }
                    if ("portal.phase".equals(envelope.optString("type"))) {
                        JSONObject payload = envelope.optJSONObject("payload");
                        String phase = payload == null ? "" : payload.optString("phase");
                        if (java.util.Arrays.asList("AUTHENTICATING", "VALIDATING", "LOADING").contains(phase))
                            emit("portal.phase", activeSessionId + "|" + phase);
                        return;
                    }
                    if ("portal.reconnect_required".equals(envelope.optString("type"))) { emit("ui.refresh", ""); return; }
                } catch (JSONException invalid) { return; }
                if (data.contains("\"type\":\"video.viewport\"")) { onViewportMessage(data); return; }
                if (data.contains("\"type\":\"portal.auth_failed\"")) { onPortalAuthFailed(); return; }
                if (data.contains("\"type\":\"env.save\"")) { saveEnv(profileKey, data); return; }
                if (data.contains("\"type\":\"channels.")) { onChannelMessage(data); return; } // never forwarded to C# (size)
                emit("portal.message", data);
            });
            facadeScript = WebViewCompat.addDocumentStartJavaScript(webView, "window.__stbSnapshot=" + snapshotJson
                    + ";\nwindow.__stbCacheKey=" + JSONObject.quote(profileKey) + ";\nwindow.__stbEnvSeed=" + envPrefs().getString(profileKey, "{}") + ";\n" + facadeJs, rules);

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

    /** The portal's firmware env (RDir setenv), per profile: seeded on the next open so the portal skips its "reboot" reload. */
    private android.content.SharedPreferences envPrefs() {
        return activity.getSharedPreferences("stbemu_portal_env", android.content.Context.MODE_PRIVATE);
    }

    private void saveEnv(String profileKey, String json) {
        try {
            JSONObject msg = new JSONObject(json);
            JSONObject env = msg.optJSONObject("payload");
            if (msg.optInt("version") != 1 || !activeSessionId.equals(msg.optString("sessionId")) || env == null) return;
            String value = env.toString(); // re-serialized JSON: safe to inline into the document-start script
            if (value.length() <= 4096) envPrefs().edit().putString(profileKey, value).apply();
        } catch (JSONException e) {
            Log.w(LOG_TAG, "bad env message: " + e);
        }
    }

    public void closePortal() {
        ui.post(() -> closePortalOnUi(false));
    }

    public void abortPortalReconnect(String reason) {
        ui.post(() -> onPortalUnavailable(reason));
    }

    /** Unity gave up retrying: drop the channel waiting on the portal instead of "Reconnecting to portal…" forever. */
    public void portalReconnectFailed() {
        ui.post(() -> {
            if (!waitingForChannel || portalChannelState.portalState() == PortalChannelState.PortalState.READY) return;
            clearChannelSwitch(); // the next pick requests a new reconnect
            suppressPortalStop = true;
            tvScreen.channelFailed(player.getMediaItemCount() > 0 ? "the portal is not responding · still playing the previous channel"
                    : "the portal is not responding", playingChannelId);
        });
    }

    private void closePortalOnUi() { closePortalOnUi(false); }

    private void closePortalOnUi(boolean preserveChannelState) {
        ui.removeCallbacks(portalLoadTimeout);
        pendingViewportMessage = null;
        if (!preserveChannelState) portalVisibleRequested = false;
        if (!preserveChannelState) {
            playGeneration++;
            ui.removeCallbacks(streamDeadline);
            resolvingStreamLink = false;
            recoveryStartedAt = -1;
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
            channelCmds.clear(); // the next profile's list refills it
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
        tvScreen.reloadFailed();
        Log.w(LOG_TAG, "Portal unavailable generation=" + portalGeneration + " reason=" + reason);
        String failedSessionId = activeSessionId;
        String pendingId = portalChannelState.pendingChannelId();
        portalGeneration++;
        activeSessionId = "";
        channelSessionId = "";
        portalReply = null;
        channelRequestSent = false;
        suppressPortalStop = true;
        portalChannelState.unavailable();
        waitingForChannel = pendingId != null;
        if (pendingId != null) tvScreen.setOpeningHint("Reconnecting to portal…");
        if (player != null && (player.getMediaItemCount() > 0 || stoppedByUser)) {
            webView.stopLoading();
            updatePortalVisibility(); // hidden (portal UNAVAILABLE); also brings back tap-hidden controls
            // One toast per outage, and none over a running stream: the player keeps going (or shows its own recovery).
            boolean signedOut = "AUTH_FAILED_REPEATED".equals(reason);
            if (signedOut || player.getMediaItemCount() == 0) {
                String text = signedOut ? "MAC in use on another device · portal signed out" : "Portal disconnected · reconnects when you pick a channel";
                boolean hint = reason.startsWith("AUTH_FAILED") || reason.startsWith("LOAD_FAILED") || reason.contains("TIMEOUT");
                Toast.makeText(activity, hint ? text + "\n" + DNS_HINT : text, hint ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
            emit("portal.unavailable", failedSessionId + "|" + reason);
            if (streamPortalRecovery) {
                finishFailedStreamRecovery();
                return;
            }
            boolean retryable = "AUTH_FAILED".equals(reason) || "NETWORK".equals(reason) || "STREAM_LINK_REFUSED".equals(reason)
                    || "SERVER_UNAVAILABLE".equals(reason) || reason.contains("TIMEOUT")
                    || reason.startsWith("HTTP_5") || "LOAD_FAILED:-2".equals(reason)
                    || "LOAD_FAILED:-6".equals(reason) || "LOAD_FAILED:-8".equals(reason);
            if (pendingId != null && !retryable && !reason.startsWith("PROFILE_NETWORK")
                    && !reason.startsWith("PROFILE_SERVER_UNAVAILABLE") && !reason.startsWith("PROFILE_RATE_LIMITED:")) {
                clearChannelSwitch();
                tvScreen.channelFailed("Portal access failed - reconnect or check account settings", playingChannelId);
            }
            if (pendingId != null && retryable && !channelReconnectAttempted) {
                channelReconnectAttempted = true;
                // The lost request's timeout would fire mid-reconnect and drop the channel; openPortal re-arms one.
                ui.removeCallbacks(channelSwitchTimeout);
                if (portalChannelState.selectWhileUnavailable(pendingId)) emit("ui.portal-reconnect", pendingId);
            }
        } else {
            // AppFlow reconnects; the new session replays the pending channel (and arms its own timeout).
            if (pendingId != null) ui.removeCallbacks(channelSwitchTimeout);
            emit("portal.error", failedSessionId + "|" + reason);
        }
    }

    /** The server dropped this portal session (e.g. the same MAC signed in elsewhere): re-handshake, at most once per 5 min; manual reconnect remains available. */
    private void onPortalAuthFailed() {
        // Also while connecting: a rejected token leaves the portal behind a loader forever (no channel list), and its own
        // login UI is never shown here, so waiting only runs into the 30-35 s connect timeout. One count per page: the
        // generation bump below drops the burst of failures from the page's parallel requests.
        if (portalChannelState.portalState() == PortalChannelState.PortalState.UNAVAILABLE) return;
        long now = SystemClock.elapsedRealtime();
        if (now - authFailureWindowStart > 300_000) {
            authFailureWindowStart = now;
            authFailures = 0;
        }
        boolean retry = ++authFailures <= 1;
        Log.w(LOG_TAG, "Portal session rejected (Authorization failed.) count=" + authFailures
                + " after " + sincePortalOpen() / 1000 + " s");
        // With media the stream keeps its URL and needs no portal: reconnect lazily (channel switch, stream recovery, ⟳),
        // not in the background, where it costs bandwidth/CPU during playback and kicks a device sharing the MAC.
        onPortalUnavailable(retry ? "AUTH_FAILED" : "SESSION_RECOVERY_FAILED"); // without media: portal.error, AppFlow decides
    }

    private long sincePortalOpen() { return SystemClock.elapsedRealtime() - portalOpenedAt; }

    private void sendChannelRequest(String id) {
        if (portalReply == null || channelSessionId.isEmpty() || channelRequestSent) return;
        Log.i(LOG_TAG, "Channel request " + id + " +" + sincePortalOpen() + " ms");
        channelRequestSent = true;
        fastRequestSent = fastLinkArrived = false;
        switchingChannelId = id;
        ui.removeCallbacks(channelSwitchTimeout);
        ui.postDelayed(channelSwitchTimeout, 15000);
        portalReply.postMessage("{\"version\":1,\"sessionId\":" + JSONObject.quote(channelSessionId)
                + ",\"type\":\"channels.play\",\"payload\":{\"id\":" + JSONObject.quote(id) + "}}");
    }

    /**
     * Reconnect fast path: as soon as the new page is up, ask it for the pending channel's link by its known cmd; the
     * page waits for get_profile, calls create_link itself and plays. No channel list needed. Timeouts stay the
     * reconnect's own; "channels.fastplay.failed" falls back to sendChannelRequest once the list has loaded.
     */
    private void sendFastChannelRequest() {
        String id = portalChannelState.pendingChannelId();
        String cmd = id == null ? null : channelCmds.get(id);
        if (cmd == null || portalReply == null || channelRequestSent
                || portalChannelState.portalState() == PortalChannelState.PortalState.READY) return;
        Log.i(LOG_TAG, "Channel fast request " + id + " +" + sincePortalOpen() + " ms");
        channelRequestSent = fastRequestSent = true;
        fastLinkArrived = false;
        switchingChannelId = id;
        portalReply.postMessage("{\"version\":1,\"sessionId\":" + JSONObject.quote(activeSessionId)
                + ",\"type\":\"channels.fastplay\",\"payload\":{\"id\":" + JSONObject.quote(id)
                + ",\"cmd\":" + JSONObject.quote(cmd) + "}}");
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
                    failStream("LINK_RESOLVE_FAILED");
                    return;
                }
                ui.removeCallbacks(channelSwitchTimeout);
                clearChannelSwitch();
                suppressPortalStop = true;
                if (player.getPlayerError() != null) scheduleRetry();
                setExpandedVideo(false);
                if (!nativeUi) channelPanel.setVisibility(View.VISIBLE);
                String code = msg.optJSONObject("payload") == null ? "" : msg.optJSONObject("payload").optString("code");
                tvScreen.channelFailed("CHANNEL_NOT_FOUND".equals(code) ? "the portal does not have this channel"
                        : "the portal reported an error" + (code.isEmpty() ? "" : " (" + code + ")"), playingChannelId);
                return;
            }
            if (msg.optString("type").startsWith("channels.fastplay.")) {
                String responseId = msg.optJSONObject("payload") == null ? "" : msg.optJSONObject("payload").optString("id");
                if (!fastRequestSent || !switchingChannelId.equals(responseId)) return;
                if ("channels.fastplay.playing".equals(msg.optString("type"))) { fastLinkArrived = true; return; }
                Log.i(LOG_TAG, "Channel fast request failed; waiting for the channel list +" + sincePortalOpen() + " ms");
                channelRequestSent = fastRequestSent = false;
                if (portalChannelState.canSendPending(channelSessionId)) sendChannelRequest(portalChannelState.pendingChannelId());
                return;
            }
            if ("channels.play.accepted".equals(msg.optString("type"))) {
                String responseId = msg.optJSONObject("payload") == null ? "" : msg.optJSONObject("payload").optString("id");
                if (!switchingChannelId.equals(responseId)) return;
                return;
            }
            if (!"channels.data".equals(msg.optString("type"))) return;
            JSONObject snapshot = msg.getJSONObject("payload");
            validateCatalog(snapshot); // validate the entire snapshot before mutating either UI or commands
            channelSessionId = msg.optString("sessionId");
            if (portalChannelState.portalState() != PortalChannelState.PortalState.READY)
                Log.i(LOG_TAG, "Portal ready (channel list, " + json.length() / 1024 + " KB) +" + sincePortalOpen() + " ms");
            portalChannelState.ready(channelSessionId);
            ui.removeCallbacks(portalLoadTimeout);
            if (pendingViewportMessage != null) {
                String viewport = pendingViewportMessage;
                pendingViewportMessage = null;
                onViewportMessage(viewport);
            }
            updatePortalVisibility();
            channelPanel.setData(msg.getJSONObject("payload"));
            tvScreen.setData(msg.getJSONObject("payload"));
            JSONObject payload = msg.getJSONObject("payload");
            org.json.JSONArray channels = payload.optJSONArray("channels");
            channelCmds.clear();
            for (int i = 0; channels != null && i < channels.length(); i++) {
                JSONObject channel = channels.optJSONObject(i);
                if (channel != null && channel.has("cmd")) channelCmds.put(channel.optString("id"), channel.optString("cmd"));
            }
            channelButton.setVisibility(View.VISIBLE);
            emit("portal.ready", activeSessionId);
            if (!portalChannelState.hasPendingChannel()) suppressPortalStop = false;
            if (portalChannelState.canSendPending(channelSessionId)) sendChannelRequest(portalChannelState.pendingChannelId());
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Invalid channel message");
            tvScreen.reloadFailed();
            emit("portal.catalog-error", activeSessionId + "|INVALID_RESPONSE");
        }
    }

    private static void validateCatalog(JSONObject snapshot) throws JSONException {
        org.json.JSONArray channels = snapshot.getJSONArray("channels");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            String id = channel.getString("id");
            channel.getString("name");
            if (id.isEmpty() || !ids.add(id)) throw new JSONException("Invalid channel ID");
            if (!channel.isNull("number")) channel.getInt("number");
        }
        org.json.JSONArray genres = snapshot.getJSONArray("genres");
        for (int i = 0; i < genres.length(); i++) genres.getJSONObject(i).getString("id");
        org.json.JSONArray favorites = snapshot.getJSONArray("favIds");
        for (int i = 0; i < favorites.length(); i++) favorites.getString(i);
    }

    /** The native TV screen (the portal UI is never shown); uGUI is below the WebView and cannot be tapped there. */
    public void setOverlayVisible(boolean visible) {
        ui.post(() -> setOverlayVisibleOnUi(visible));
    }

    private void setOverlayVisibleOnUi(boolean visible) {
        overlayEnabled = visible;
        controlsHidden = false;
        overlay.setVisibility(visible && !nativeUi ? View.VISIBLE : View.GONE);
        tvScreen.setVisibility(visible && nativeUi ? View.VISIBLE : View.GONE);
        if (visible && nativeUi) tvScreen.onShown();
        else tvScreen.onHidden();
        updatePlaybackControls();
        if (!visible) {
            streamInfo.dismiss();
            channelPanel.dismiss();
        }
    }

    private void pickChannel(String id) {
        if (playbackGate) return;
        // Portal down: Unity resolves the channel first and shows its ad once the new link plays, instead of an ad
        // followed by the whole reconnect.
        emit(portalChannelState.portalState() == PortalChannelState.PortalState.READY
                ? "ui.channel-selected" : "ui.channel-selected.reconnect", id);
    }

    /** @param holdForAd Unity shows this channel's ad once its link is in: play() then parks the stream (channel.ready). */
    public void continueChannelSelection(String id, boolean holdForAd) {
        ui.post(() -> {
            heldChannelId = holdForAd ? id : "";
            pickChannelAfterAds(id);
        });
    }

    private void pickChannelAfterAds(String id) {
        playGeneration++;
        cancelRetry();
        ui.removeCallbacks(streamDeadline);
        recoveryStartedAt = -1;
        recoveryIncidents.clear();
        resolvingStreamLink = false;
        streamPortalRecovery = false;
        reloadPolicy.reset();
        explicitPlaybackStopped = false;
        if (id.equals(stoppedChannelId)) {
            String url = stoppedUrl, headers = stoppedHeaders;
            clearStoppedUrl();
            replayingStoppedUrl = true;
            playingUrl = url; // play() keeps playingChannelId for the same URL
            playingChannelId = id;
            play(url, headers);
            ui.removeCallbacks(stoppedUrlTimeout);
            ui.postDelayed(stoppedUrlTimeout, 5000);
            return;
        }
        clearStoppedUrl();
        boolean requestInFlight = channelRequestSent;
        if (!requestInFlight) channelReconnectAttempted = false;
        waitingForChannel = true;
        setExpandedVideo(false); // Channel selection returns to the portal's own presentation.
        if (portalChannelState.portalState() == PortalChannelState.PortalState.READY) {
            portalChannelState.select(id);
            if (!requestInFlight) sendChannelRequest(id);
        } else {
            tvScreen.setOpeningHint("Reconnecting to portal…");
            if (portalChannelState.selectWhileUnavailable(id)) emit("ui.portal-reconnect", id);
            else sendFastChannelRequest(); // portal still starting: no need to wait for its channel list either
        }
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

    private void setControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        overlay.setVisibility(hidden || !overlayEnabled || nativeUi ? View.GONE : View.VISIBLE);
        updatePlaybackControls();
    }

    private void clearChannelSwitch() {
        ui.removeCallbacks(channelSwitchTimeout);
        waitingForChannel = false;
        switchingChannelId = "";
        channelRequestSent = false;
        fastRequestSent = fastLinkArrived = false;
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
        if (sizeButton != null) sizeButton.setText(expanded ? "Shrink" : "Expand");
        applyVideoViewport();
    }

    private void applyVideoViewport() {
        if (nativeUi || stage == null || stage.getWidth() == 0 || stage.getHeight() == 0) return;
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
        playbackControls.setVisibility(overlayEnabled && !controlsHidden && active && !nativeUi ? View.VISIBLE : View.GONE);
        tvScreen.setPlayback(active, player.getPlayWhenReady());
        pauseButton.setEnabled(player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING);
        pauseButton.setText(player.getPlayWhenReady() ? "Pause" : "Resume");
    }

    private void cancelRetry() {
        ui.removeCallbacks(retryStream);
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

    /** A stall re-rates the link (Stream info). */
    private void onStall() {
        long now = SystemClock.elapsedRealtime();
        networkQuality.stallStarted(now);
        long bandwidth = bandwidthMeter.getBitrateEstimate(), bitrate = streamBitrate();
        NetworkQuality.Tier tier = networkQuality.tier(now, bandwidth, bitrate);
        Log.i(LOG_TAG, "Stall: tier=" + tier + " bandwidth=" + bandwidth + " bitrate=" + bitrate
                + " stalls5m=" + networkQuality.stalls(now));
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
        String label = tier == NetworkQuality.Tier.GOOD ? "Good" : tier == NetworkQuality.Tier.FAIR ? "Fair"
                : tier == NetworkQuality.Tier.WEAK ? "Weak" : "Too slow for this channel";
        return "Measured bandwidth: " + mbps(bandwidth)
                + "\nStalls in 5 min: " + networkQuality.stalls(now)
                + "\nNetwork quality: " + label;
    }

    private static String mbps(long bps) {
        return bps > 0 ? String.format(java.util.Locale.getDefault(), "%.1f Mbps", bps / 1_000_000.0) : "?";
    }

    private boolean isLive() {
        // Unknown duration alone does not make a progressive VOD a live stream.
        return player.isCurrentMediaItemLive() || !playingChannelId.isEmpty();
    }

    private boolean canRecoverStream() {
        return player != null && player.getMediaItemCount() > 0 && player.getPlayWhenReady()
                && !explicitPlaybackStopped && !decoderFailureTerminal && reloadPolicy.mode() != ReloadPolicy.Mode.NONE;
    }

    private void armBufferingTimeout() {
        ui.removeCallbacks(bufferingTimeout);
        // A stall that clears on its own is not a recovery incident; only the retry after 10 s counts.
        if (canRecoverStream() && !streamPortalRecovery && player.getPlaybackState() == Player.STATE_BUFFERING)
            ui.postDelayed(bufferingTimeout, 10000);
    }

    private void scheduleRetry() {
        if (!canRecoverStream() || retryScheduled || waitingForChannel || streamPortalRecovery) return;
        if (!beginRecovery()) return;
        if (replayingStoppedUrl) { // the kept URL has expired: fetch a fresh one through the portal at once
            fallBackFromStoppedUrl();
            return;
        }
        ui.removeCallbacks(stablePlayback);
        ui.removeCallbacks(bufferingTimeout);
        long delay = reloadPolicy.nextDelayMillis();
        if (delay < 0) {
            resolveStreamLink();
            return;
        }
        retryScheduled = true;
        ui.postDelayed(retryStream, delay);
        Log.i(LOG_TAG, "Stream error · retrying in " + (delay / 1000) + " s");
        updatePlaybackControls();
    }

    private boolean beginRecovery() {
        long now = SystemClock.elapsedRealtime();
        if (recoveryStartedAt < 0) {
            while (!recoveryIncidents.isEmpty() && now - recoveryIncidents.peekFirst() >= 300_000)
                recoveryIncidents.removeFirst();
            if (recoveryIncidents.size() >= 3) { failStream("RECOVERY_RATE_LIMIT"); return false; }
            recoveryIncidents.addLast(now);
            recoveryStartedAt = now;
        }
        long remaining = 30_000 - (now - recoveryStartedAt);
        if (remaining <= 0) { failStream("RECOVERY_TIMEOUT"); return false; }
        ui.removeCallbacks(streamDeadline);
        ui.postDelayed(streamDeadline, remaining);
        return true;
    }

    /** A CDN refusal first tries create_link in the existing session; a handshake only once that session proves stale. */
    private void resolveStreamLink() {
        if (!canRecoverStream() || resolvingStreamLink) return;
        if (!beginRecovery()) return;
        if (reloadPolicy.linkResolveUsed() && !playingChannelId.isEmpty()
                && portalChannelState.portalState() == PortalChannelState.PortalState.READY) {
            reconnectPortalForStream("STREAM_LINK_REFUSED"); // the link this session just resolved is refused too
            return;
        }
        if (!reloadPolicy.requestLinkResolve() || playingChannelId.isEmpty()
                || portalChannelState.portalState() != PortalChannelState.PortalState.READY) {
            failStream("STREAM_RECOVERY_EXHAUSTED");
            return;
        }
        cancelRetry();
        resolvingStreamLink = streamPortalRecovery = waitingForChannel = true;
        suppressPortalStop = true;
        portalChannelState.select(playingChannelId);
        sendChannelRequest(playingChannelId);
    }

    /** The session is stale (create_link hangs or its links are refused, e.g. after a long ad pause): re-handshake and replay the channel. */
    private void reconnectPortalForStream(String reason) {
        ui.removeCallbacks(streamDeadline); // the reconnect has its own timeouts
        ui.removeCallbacks(recoveryDeadline);
        resolvingStreamLink = streamPortalRecovery = false;
        channelReconnectAttempted = true; // the reconnect is emitted below, not by onPortalUnavailable
        portalChannelState.select(playingChannelId);
        onPortalUnavailable(reason);
        if (portalChannelState.selectWhileUnavailable(playingChannelId)) emit("ui.portal-reconnect", playingChannelId);
    }

    private void failStream(String code) {
        cancelRetry();
        replayingStoppedUrl = false;
        ui.removeCallbacks(stoppedUrlTimeout);
        ui.removeCallbacks(streamDeadline);
        ui.removeCallbacks(bufferingTimeout);
        ui.removeCallbacks(stablePlayback);
        ui.removeCallbacks(recoveryDeadline);
        resolvingStreamLink = streamPortalRecovery = false;
        decoderFailureTerminal = true;
        clearChannelSwitch();
        suppressPortalStop = true;
        player.pause();
        emit("error", code);
        tvScreen.channelFailed("Playback stopped (" + code + "). Select the channel to try again.", playingChannelId);
    }

    private void finishFailedStreamRecovery() {
        ui.removeCallbacks(recoveryDeadline);
        streamPortalRecovery = false;
        String pendingId = portalChannelState.pendingChannelId();
        if (pendingId != null) recoveryChannelId = pendingId;
        clearChannelSwitch();
        suppressPortalStop = true;
        cancelRetry();
        failStream("LINK_RESOLVE_FAILED");
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
        boolean visible = !nativeUi && portalVisibleRequested && !streamPortalRecovery
                && portalChannelState.canPresent(player.getMediaItemCount() > 0);
        // Native UI: the portal keeps a laid-out (INVISIBLE, not GONE) page, as its scripts read their viewport size.
        webView.setVisibility(visible ? View.VISIBLE : nativeUi ? View.INVISIBLE : View.GONE);
        if (!visible && controlsHidden) setControlsHidden(false); // the tap that restores them went to the portal
        if (visible) webView.requestFocus();
    }

    /** @param headersJson flat JSON object of request headers, e.g. {"User-Agent":"...","Referer":"..."}; may be null. */
    public void play(String url, String headersJson) {
        final long generation = playGeneration;
        Map<String, String> headers;
        try {
            headers = parseHeaders(headersJson);
        } catch (JSONException e) {
            emit("error", "BAD_HEADERS");
            return;
        }
        ui.post(() -> {
            if (generation != playGeneration) return;
            if (playbackGate || explicitPlaybackStopped) return;
            String scheme = Uri.parse(url).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                emit("error", "UNSUPPORTED_STREAM_SCHEME");
                return;
            }
            cancelRetry();
            boolean recoveringLink = resolvingStreamLink;
            if (!recoveringLink && !url.equals(playingUrl)) {
                ui.removeCallbacks(streamDeadline);
                reloadPolicy.reset();
                recoveryStartedAt = -1;
            }
            resolvingStreamLink = false;
            playingChannelId = channelRequestSent ? switchingChannelId : url.equals(playingUrl) ? playingChannelId : "";
            tvScreen.setNowPlaying(playingChannelId);
            Log.i(LOG_TAG, "Play channel=" + playingChannelId + " +" + sincePortalOpen() + " ms");
            playingUrl = url;
            playingHeaders = headersJson;
            clearStoppedUrl();
            stoppedByUser = false;
            recoveryChannelId = "";
            streamPortalRecovery = false;
            ui.removeCallbacks(recoveryDeadline);
            ui.removeCallbacks(stablePlayback);
            clearChannelSwitch();
            // Fast path: playing before the portal has started; its startup Stop/autoplay must not replace this stream.
            // channels.data lifts it (no pending channel then).
            if (portalChannelState.portalState() != PortalChannelState.PortalState.READY) suppressPortalStop = true;
            OkHttpDataSource.Factory http;
            try {
                http = new OkHttpDataSource.Factory(new StreamRequestPolicy(url, headers).client());
            } catch (IllegalArgumentException invalid) {
                failStream("INVALID_STREAM_REQUEST");
                return;
            }
            // Manifest/engine determines live offset, including long-segment HLS.
            MediaItem mediaItem = new MediaItem.Builder().setUri(url).build();
            if (!recoveringLink) hevcDecoders.reset();
            decoderFailureTerminal = false;
            emit("firstFrame", "false");
            player.setMediaSource(new DefaultMediaSourceFactory(http)
                    .setLoadErrorHandlingPolicy(new StreamLoadErrorPolicy()).createMediaSource(mediaItem));
            player.prepare();
            if (backgrounded) resumeOnStart = true; // e.g. portal recovery finished while the app was hidden
            else player.play();
            videoView.setVisibility(View.VISIBLE);
            applyVideoViewport();
            updatePlaybackControls();
            updatePortalVisibility();
            armBufferingTimeout();
            if (!heldChannelId.isEmpty() && heldChannelId.equals(playingChannelId)) {
                // Same looper turn as play(): prepare() is still loading, so nothing has been heard or shown yet. The
                // stream buffers under Unity's ad and starts when Unity lifts the gate.
                heldChannelId = "";
                applyPlaybackGate(true);
                emit("channel.ready", playingChannelId);
            }
        });
    }

    private View buildRewardPrompt() {
        rewardPrompt = new FrameLayout(activity);
        rewardPrompt.setBackgroundColor(0xBF000000); // black, alpha 0.75
        rewardPrompt.setClickable(true); // the stream keeps playing underneath but cannot be tapped
        rewardPrompt.setFocusableInTouchMode(true); // takes Back from the WebView
        rewardPrompt.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && rewardExit.isEnabled()) emit("ui.reward-exit", "");
            return true;
        });
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(PortalStyle.surface(activity, PortalStyle.SURFACE, PortalStyle.BORDER));
        card.setPadding(dp(24), dp(20), dp(24), dp(20));
        TextView title = new TextView(activity);
        title.setText("Continue watching");
        title.setTextColor(PortalStyle.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rewardText = new TextView(activity);
        rewardText.setTextColor(PortalStyle.MUTED);
        rewardText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        rewardText.setGravity(Gravity.CENTER);
        rewardText.setPadding(0, dp(10), 0, dp(20));
        card.addView(rewardText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout buttons = new LinearLayout(activity);
        rewardExit = button("Exit", v -> emit("ui.reward-exit", ""));
        rewardWatch = button("Watch ad", v -> emit("ui.reward-watch", ""));
        // Equal halves of the card width.
        buttons.addView(rewardExit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        gap.leftMargin = dp(12);
        buttons.addView(rewardWatch, gap);
        card.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rewardPrompt.addView(card, new FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        rewardPrompt.setVisibility(View.GONE);
        return rewardPrompt;
    }

    /** Over the playing stream: the gate is Unity's to set only while the ad itself is on screen. */
    public void showRewardPrompt(String text, boolean canWatch, boolean canExit) {
        ui.post(() -> {
            rewardText.setText(text);
            rewardWatch.setEnabled(canWatch);
            rewardWatch.setAlpha(canWatch ? 1f : 0.4f);
            rewardExit.setEnabled(canExit);
            rewardExit.setAlpha(canExit ? 1f : 0.4f);
            rewardPrompt.setVisibility(View.VISIBLE);
            rewardPrompt.requestFocus();
        });
    }

    public void hideRewardPrompt() {
        ui.post(() -> rewardPrompt.setVisibility(View.GONE));
    }

    // ---- profile home (Unity's AndroidProfileUi owns the data; see it for the JSON shapes) ----

    /** Renders the list and Setting info; closes an open editor (a successful save ends here). */
    public void showProfileHome(String json) {
        ui.post(() -> {
            profileEditor.dismiss();
            home.show(json);
        });
    }

    public void hideProfileHome() {
        ui.post(() -> {
            profileEditor.dismiss();
            home.hide();
        });
    }

    /** Queried by Unity; all View access stays on Android's UI thread. */
    public void requestBannerLayout() {
        ui.post(() -> {
            int mode = 0, width = 0;
            boolean modal = profileEditor.isShowing() || proDialog.isShowing();
            if (!backgrounded && !playbackGate && !modal && nativeLayer.isShown()) {
                if (home.isShown()) {
                    mode = 1;
                    width = home.getWidth();
                } else if (tvScreen.isShown() && tvScreen.canShowBanner()) {
                    mode = tvScreen.getWidth() > tvScreen.getHeight() ? 2 : 1;
                    width = tvScreen.bannerWidth();
                }
            }
            int widthDp = (int) (width / activity.getResources().getDisplayMetrics().density);
            emit("ui.banner-layout", mode + "|" + widthDp);
        });
    }

    /** The SDK reports physical pixels; reserve only the space occupied by a loaded banner. */
    public void setBannerInset(int heightPixels) {
        ui.post(() -> {
            home.setBannerInset(heightPixels);
            tvScreen.setBannerInset(heightPixels);
        });
    }

    // ---- Pro (Unity's AndroidProfileUi owns the store; payload {pro, monthlyPrice, lifetimePrice}) ----

    public void showProPanel(String json) {
        ui.post(() -> proDialog.show(json));
    }

    public void hideProPanel() {
        ui.post(() -> proDialog.dismiss());
    }

    /** Updates the home button and, if open, the sheet. */
    public void setProState(String json) {
        ui.post(() -> {
            try {
                home.setPro(new JSONObject(json).optBoolean("pro"));
            } catch (JSONException e) {
                Log.w(LOG_TAG, "bad pro payload: " + e.getMessage());
                return;
            }
            proDialog.update(json);
        });
    }

    /** Progress / result overlay for a purchase or restore: {busy, title, message}. */
    public void showProStatus(String json) {
        ui.post(() -> proDialog.showStatus(json));
    }

    /** Separate from nativeLayer, which is hidden while Unity connects the portal. */
    public void showPortalLoading(String text) {
        ui.post(() -> {
            if (portalLoading == null) {
                portalLoading = new LinearLayout(activity);
                portalLoading.setOrientation(LinearLayout.VERTICAL);
                portalLoading.setGravity(Gravity.CENTER);
                portalLoading.setBackgroundColor(PortalStyle.BACKGROUND);
                int padding = PortalStyle.dp(activity, 24);
                portalLoading.setPadding(padding, padding, padding, padding);
                portalLoading.setClickable(true);
                android.widget.ProgressBar spinner = new android.widget.ProgressBar(activity, null,
                        android.R.attr.progressBarStyleLarge);
                spinner.setIndeterminate(true);
                portalStatusSpinner = spinner;
                spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
                int size = PortalStyle.dp(activity, 48);
                portalLoading.addView(spinner, new LinearLayout.LayoutParams(size, size));
                TextView title = new TextView(activity);
                portalStatusTitle = title;
                title.setText("Loading portal…");
                title.setTextSize(22);
                title.setTextColor(PortalStyle.TEXT);
                title.setGravity(Gravity.CENTER);
                title.setPadding(0, padding, 0, PortalStyle.dp(activity, 8));
                portalLoading.addView(title);
                portalLoadingMessage = new TextView(activity);
                portalLoadingMessage.setTextSize(14);
                portalLoadingMessage.setTextColor(PortalStyle.MUTED);
                portalLoadingMessage.setGravity(Gravity.CENTER);
                portalLoading.addView(portalLoadingMessage);
                Button cancel = new Button(activity);
                portalStatusPrimary = cancel;
                cancel.setText("Cancel");
                PortalStyle.button(cancel);
                cancel.setOnClickListener(v -> emit("ui.connect-cancel", ""));
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                        PortalStyle.dp(activity, 160), PortalStyle.dp(activity, 48));
                buttonParams.topMargin = PortalStyle.dp(activity, 32);
                portalLoading.addView(cancel, buttonParams);
                portalStatusBack = new Button(activity);
                portalStatusBack.setText("Back");
                PortalStyle.button(portalStatusBack);
                portalStatusBack.setOnClickListener(v -> emit("ui.portal-back", ""));
                LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                        PortalStyle.dp(activity, 160), PortalStyle.dp(activity, 48));
                backParams.topMargin = PortalStyle.dp(activity, 12);
                portalLoading.addView(portalStatusBack, backParams);
                activity.addContentView(portalLoading, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            portalLoadingMessage.setText(text);
            portalStatusTitle.setText("Loading portal…");
            portalStatusSpinner.setVisibility(View.VISIBLE);
            portalStatusPrimary.setText("Cancel");
            portalStatusPrimary.setOnClickListener(v -> emit("ui.connect-cancel", ""));
            portalStatusBack.setVisibility(View.GONE);
            portalLoading.setVisibility(View.VISIBLE);
            portalLoading.bringToFront();
        });
    }

    public void hidePortalLoading() {
        ui.post(() -> { if (portalLoading != null) portalLoading.setVisibility(View.GONE); });
    }

    public void showPortalError(String text) {
        showPortalLoading(text);
        ui.post(() -> {
            portalStatusSpinner.setVisibility(View.GONE);
            portalStatusTitle.setText("Could not open the portal");
            portalStatusPrimary.setText("Retry");
            portalStatusPrimary.setOnClickListener(v -> emit("ui.portal-retry", ""));
            portalStatusBack.setVisibility(View.VISIBLE);
        });
    }

    /** Opens the Add/Edit popup over the (still rendered) list. */
    public void showProfileEditor(String json) {
        ui.post(() -> {
            home.setVisibility(View.VISIBLE);
            profileEditor.show(json);
        });
    }

    public void setProfileEditorErrors(String json) {
        ui.post(() -> profileEditor.setErrors(json));
    }

    public void setPlaybackGate(boolean blocked) {
        ui.post(() -> applyPlaybackGate(blocked));
    }

    private void applyPlaybackGate(boolean blocked) {
        if (playbackGate == blocked) return;
        playbackGate = blocked;
        if (blocked) {
            resumeAfterGate = player.getPlayWhenReady() || resumeOnStart;
            resumeOnStart = false;
            cancelRetry();
            ui.removeCallbacks(bufferingTimeout);
            player.setPlayWhenReady(false);
            nativeLayer.setVisibility(View.GONE);
        } else {
            nativeLayer.setVisibility(View.VISIBLE);
            if (resumeAfterGate && player.getMediaItemCount() > 0 && !explicitPlaybackStopped) {
                if (backgrounded) resumeOnStart = true;
                else {
                    if (isLive()) player.seekToDefaultPosition();
                    player.setPlayWhenReady(true);
                    if (player.getPlayerError() != null) scheduleRetry();
                }
            }
            resumeAfterGate = false;
            armBufferingTimeout();
        }
    }

    public void setPaused(boolean paused) {
        ui.post(() -> {
            if (playbackGate && !paused) return;
            if (paused) cancelRetry();
            else decoderFailureTerminal = false; // a manual resume after failStream re-arms recovery for this item
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

    private void fallBackFromStoppedUrl() {
        replayingStoppedUrl = false;
        ui.removeCallbacks(stoppedUrlTimeout);
        pickChannelAfterAds(playingChannelId);
    }

    private void clearStoppedUrl() {
        stoppedChannelId = stoppedUrl = "";
        stoppedHeaders = null;
    }

    private void stopOnUi(boolean explicit) {
        playGeneration++;
        ui.removeCallbacks(streamDeadline);
        recoveryStartedAt = -1;
        resolvingStreamLink = false;
        clearStoppedUrl();
        replayingStoppedUrl = false;
        stoppedByUser = false;
        heldChannelId = "";
        ui.removeCallbacks(stoppedUrlTimeout);
        resumeAfterGate = false;
        resumeOnStart = false;
        streamPortalRecovery = false;
        playingChannelId = "";
        tvScreen.setNowPlaying("");
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
        final long generation = playGeneration;
        ui.post(() -> {
            if (generation != playGeneration) return;
            if (playbackGate || explicitPlaybackStopped) return;
            // The fast link is announced before its Play(); anything earlier is the new page's startup autoplay.
            if (channelRequestSent && fastRequestSent && !fastLinkArrived) return;
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
            if (playbackGate) return;
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
            activity.unregisterComponentCallbacks(themeConfiguration);
            if (streamInfo != null) streamInfo.dismiss();
            if (profileEditor != null) profileEditor.dismiss();
            if (proDialog != null) proDialog.dismiss();
            if (home != null) home.hide();
            closePortalOnUi();
            logoLoader.release();
            videoView.setKeepScreenOn(false);
            player.release();
            webView.destroy();
            ViewGroup root = activity.findViewById(android.R.id.content);
            root.removeView(nativeLayer);
            if (portalLoading != null) root.removeView(portalLoading);
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
