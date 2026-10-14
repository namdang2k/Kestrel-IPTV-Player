package com.stbemu.host;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Native layer on top of the Unity view: video SurfaceView (ExoPlayer) inside the native TV screen, fed by an
 * Xtream server (XtreamClient).
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

    private static final String LOG_TAG = "StbPortal";

    private SurfaceView videoView;
    private Stage16x9 stage;
    private FrameLayout contentLayer; // App screens stay below the SDK banner attached to the Activity root.
    private FrameLayout nativeLayer; // every native view, hidden as one while Unity owns an ad or modal
    private FrameLayout rewardPrompt; // dims the still-playing stream; Unity sets its text and buttons
    private TextView rewardText;
    private LinearLayout portalLoading;
    private TextView portalLoadingMessage;
    private TextView portalStatusTitle;
    private android.widget.ProgressBar portalStatusSpinner;
    private Button portalStatusPrimary, portalStatusBack;
    private Button rewardWatch, rewardExit;
    private ExoPlayer player;
    private StreamInfoDialog streamInfo;
    private LinearLayout overlay;
    private ChannelPanel channelPanel;
    private TvScreen tvScreen;
    private HomeScreen home; // profile list + Setting tab; Unity drives it through showProfileHome/hideProfileHome
    private ProDialog proDialog; // Upgrade to Pro sheet; Unity drives it through showProPanel/setProState
    private android.app.AlertDialog upsell; // periodic "Upgrade to Pro" nudge, see showUpsell
    private ProfileEditorDialog profileEditor; // its own window: hidden/released explicitly, unlike nativeLayer children
    private LogoLoader logoLoader;
    private final boolean nativeUi = true; // native TV screen; the overlay and playback-control rows stay hidden
    private Button channelButton;
    private String channelSessionId = ""; // session whose channel list is loaded; "" until then
    private boolean controlsHidden;
    private LinearLayout playbackControls;
    private Button pauseButton, sizeButton;
    private boolean expandedVideo, overlayEnabled;
    private double viewportWidth = 1920, viewportHeight = 1080, viewportX, viewportY;
    private int portalGeneration;
    private String activeSessionId = "";
    private final java.util.concurrent.ExecutorService xtreamIo = java.util.concurrent.Executors.newCachedThreadPool();
    private XtreamClient xtream; // current profile; null while closed
    private XtreamClient.Account account; // last successful authenticate() of xtream; null until then
    private String xtreamUsername = "";
    private String playerHeadersJson = "{}";
    private final ReloadPolicy reloadPolicy = new ReloadPolicy();
    private final HevcDecoderSelector hevcDecoders = new HevcDecoderSelector();
    private boolean decoderFailureTerminal;
    private Button reloadButton;
    private boolean retryScheduled;
    private boolean streamPortalRecovery;
    private boolean resolvingStreamLink;
    private int accountChecks; // verifyAccountThenReplay calls whose result is not back yet
    private volatile long playGeneration;
    private long recoveryStartedAt = -1;
    private final PlaybackInterruption playbackInterruption = new PlaybackInterruption();
    private final java.util.ArrayDeque<Long> recoveryIncidents = new java.util.ArrayDeque<>();
    private final Runnable streamDeadline = () -> {
        if (canRecoverStream() && !this.backgrounded && !this.playbackGate) failStream("RECOVERY_TIMEOUT");
    };
    private String playingChannelId = "";
    private String playingUrl = "";
    private String playingHeaders;
    private DefaultMediaSourceFactory playingSource; // the playing URL's headers/policy, for queueLiveReconnect
    private int aheadRetries; // refused connections ahead since the last switch-over
    // ⏹ keeps the stopped channel's URL: replaying that channel tries it first and takes the normal request path only if it fails.
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
            playedStably = true;
        }
    };
    // IPTV servers close live .ts responses every 20-40 s: an end after stable playback reconnects at once, outside the
    // incident budget; an end soon after that takes scheduleRetry.
    private boolean playedStably;
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
    /** ISP DNS often blocks or slows IPTV portals; shown with auth, network and stream failures. */
    private static final String DNS_HINT = "Try again with Cloudflare DNS (1.1.1.1)";
    private String heldChannelId = ""; // its first play() waits under the gate for Unity's interstitial
    private long portalOpenedAt; // logcat timings of a (re)connect: "+N ms" since openPortal
    /** Background = no audio; foreground resumes the retained item or recovers an expired live window. */
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityStopped(@NonNull Activity a) {
            if (a != activity) return;
            backgrounded = true;
            updatePlaybackInterruption();
            if (player == null || player.getMediaItemCount() == 0 || !player.getPlayWhenReady()) return;
            resumeOnStart = true;
            cancelRetry();
            player.setPlayWhenReady(false);
        }
        @Override public void onActivityStarted(@NonNull Activity a) {
            if (a != activity) return;
            backgrounded = false;
            updatePlaybackInterruption();
            if (playbackGate || !resumeOnStart) return;
            resumeOnStart = false;
            if (player.getMediaItemCount() == 0 || explicitPlaybackStopped) return;
            resumeInterruptedPlayback();
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
    private boolean channelRequestSent, channelReconnectAttempted, explicitPlaybackStopped;
    private final PortalChannelState portalChannelState = new PortalChannelState();
    private final Runnable channelSwitchTimeout;
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
                // would reconnect the fresh session again.
                if (portalChannelState.selectWhileUnavailable(retryId) && player.getMediaItemCount() > 0)
                    emit("ui.portal-reconnect", retryId);
                return;
            }
            clearChannelSwitch();
            if (player.getPlayerError() != null) scheduleRetry();
            tvScreen.channelFailed(player.getMediaItemCount() > 0 ? "the server is not responding · still playing the previous channel"
                    : "the server is not responding", playingChannelId);
        };
        ui.post(this::attach);
    }

    private void attach() {
        ViewGroup root = activity.findViewById(android.R.id.content);

        videoView = new SurfaceView(activity);
        videoView.setZOrderMediaOverlay(true); // above Unity's SurfaceView, below window views
        videoView.setVisibility(View.GONE);

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
        String[] actions = {"Channel list", "Reconnect to server", "Home"};
        for (int i = 0; i < actions.length; i++) {
            overlay.getChildAt(i).setContentDescription(actions[i]);
            overlay.getChildAt(i).setTooltipText(actions[i]);
        }
        channelPanel = new ChannelPanel(activity, id -> {
            channelPanel.dismiss();
            tvScreen.requestFocus();
            pickChannel(id);
        }, () -> { channelPanel.dismiss(); tvScreen.requestFocus(); });
        logoLoader = new LogoLoader(ui);
        tvScreen = new TvScreen(activity, logoLoader, new TvScreen.Actions() {
            @Override public void play(String channelId) { pickChannel(channelId); }
            @Override public void home() { emit("ui.home", ""); }
            /** Live server: reload its channel list; otherwise reconnect, and the new session delivers the list. */
            @Override public void reconnectPortal() {
                if (xtream == null || portalChannelState.portalState() != PortalChannelState.PortalState.READY) { emit("ui.refresh", ""); return; }
                loadCatalog(xtream, portalGeneration, false);
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
            @Override public void requestDetails(String kind, String channelId, int requestId) {
                JSONObject payload;
                try {
                    payload = new JSONObject().put("kind", kind).put("id", channelId).put("requestId", requestId);
                } catch (JSONException e) { return; }
                XtreamClient client = xtream;
                XtreamClient.Account info = account;
                int generation = portalGeneration;
                try {
                    if (client == null || info == null) { tvScreen.setDetails(payload.put("error", true)); return; }
                    if ("info".equals(kind)) {
                        XtreamCatalog.putInfo(payload, info, xtreamUsername, java.util.TimeZone.getDefault());
                        tvScreen.setDetails(payload);
                        return;
                    }
                } catch (JSONException e) { return; }
                runIo(() -> {
                    try {
                        payload.put("programs", XtreamCatalog.programs(client.shortEpg(channelId), java.util.TimeZone.getDefault()));
                    } catch (XtreamClient.XtreamException | JSONException | RuntimeException e) {
                        if (e instanceof RuntimeException) unexpected((RuntimeException) e);
                        try { payload.put("error", true); } catch (JSONException ignored) { }
                    }
                    ui.post(() -> { if (generation == portalGeneration) tvScreen.setDetails(payload); });
                });
            }
        });
        tvScreen.setVisibility(View.GONE);
        channelPanel.dismiss();
        channelPanel.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stage = new Stage16x9(activity); // centred 16:9 box; controls below stay full-screen
        (nativeUi ? tvScreen.playerBox : stage).addView(videoView, 0,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyVideoViewport();
        });
        nativeLayer = new FrameLayout(activity);
        nativeLayer.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        nativeLayer.addView(tvScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nativeLayer.addView(overlay);
        nativeLayer.addView(playbackControls);
        nativeLayer.addView(channelPanel);
        // Keep screen-local bringToFront() calls inside this container. The ads SDK adds
        // its banner to the Activity root later, so Loading/Error cannot cover it.
        contentLayer = new FrameLayout(activity);
        contentLayer.addView(nativeLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(contentLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
                // min = max: with 5/15 s the loader stopped at 15 s until 5 s were left (~10 s, after a server burst ~45 s
                // at 50 s) without reading, and IPTV servers drop a live .ts response nobody reads. Equal thresholds only
                // pause it briefly. Startup still waits bufferForPlaybackMs; 32 MB still caps memory.
                .setBufferDurationsMs(50_000, 50_000, 1_500, 3_000)
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
            @Override public void onLoadCompleted(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo info,
                    androidx.media3.exoplayer.source.MediaLoadData data) {
                queueLiveReconnect(eventTime.windowIndex, false);
            }
            @Override public void onLoadError(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo info,
                    androidx.media3.exoplayer.source.MediaLoadData data, java.io.IOException error, boolean wasCanceled) {
                // Host only: the path holds the credentials.
                Log.i(LOG_TAG, "Load error " + describe(error) + " after " + info.loadDurationMs + " ms, " + info.bytesLoaded
                        + " B, host=" + info.uri.getHost());
                retryQueuedReconnect(eventTime.windowIndex);
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
                if (state == Player.STATE_ENDED && isLive()) {
                    if (playedStably && canRecoverStream() && !waitingForChannel && !streamPortalRecovery) {
                        playedStably = false;
                        Log.i(LOG_TAG, "Live stream ended by server; reconnecting");
                        player.stop();
                        player.seekToDefaultPosition();
                        player.prepare();
                    } else scheduleRetry();
                }
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
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || player.getCurrentMediaItemIndex() == 0) return;
                player.removeMediaItems(0, player.getCurrentMediaItemIndex()); // the closed response's item
                aheadRetries = 0;
                Log.i(LOG_TAG, "Live stream continued on the new connection");
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                dropQueuedReconnect();
                handlePlayerError(error);
            }
        });
        activity.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        emit("ready", "");
    }

    private void handlePlayerError(PlaybackException error) {
        // Paused loaders can fail while an ad owns the screen. Keep the item and defer
        // classification/recovery until foreground playback is allowed again.
        if (playbackGate || backgrounded || explicitPlaybackStopped) return;
        if (tryRecoverHevcDecoder(error)) return;
        // Fell behind the live window: spend one retry to rejoin immediately without asking the server.
        // A second one within 10 s takes the normal delayed recovery path.
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
                // 509: Xtream's connection/bandwidth limit; ask the account why instead of retrying the same server.
                if (status == 401 || status == 403 || status == 509) {
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
            // A new stream link cannot repair an incompatible decoder or bitstream.
            decoderFailureTerminal = true;
            ui.removeCallbacks(streamDeadline);
            cancelRetry();
            ui.removeCallbacks(bufferingTimeout);
            tvScreen.channelFailed("video decoder unavailable or incompatible. Try an H.264/lower-resolution channel; see Stream info", playingChannelId);
            return;
        }
        if (error instanceof ExoPlaybackException && ((ExoPlaybackException) error).type == ExoPlaybackException.TYPE_RENDERER
                && ((ExoPlaybackException) error).rendererFormatSupport != C.FORMAT_HANDLED) {
            // The device cannot decode this format (e.g. 4K HEVC 10-bit); retries and reconnects cannot help.
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

    /**
     * Xtream servers close a live .ts response every 20-40 s while 8-11 s are still buffered. Queue the same URL as the
     * next playlist item at once: ExoPlayer loads it while the buffer plays out and switches over without the
     * ENDED → re-prepare gap. HLS (a live window) reloads its own playlist and is left alone.
     */
    private void queueLiveReconnect(int windowIndex, boolean retry) {
        // A live progressive .ts also reports isCurrentMediaItemLive(); only HLS/DASH have a manifest.
        if (playingSource == null || playingChannelId.isEmpty() || player.getCurrentManifest() != null
                // The last item, current or still queued: a queued connection can close before it plays too.
                || windowIndex < player.getCurrentMediaItemIndex() || windowIndex != player.getMediaItemCount() - 1
                || !canRecoverStream()) return;
        if (retry) Log.i(LOG_TAG, "Connecting ahead, retry " + aheadRetries + ", " + player.getTotalBufferedDuration() + " ms buffered");
        else Log.i(LOG_TAG, "Server closed the live response; connecting ahead with " + player.getTotalBufferedDuration() + " ms buffered");
        player.addMediaSource(playingSource.createMediaSource(new MediaItem.Builder().setUri(playingUrl).build()));
    }

    /**
     * The queued connection was refused (407/502 while the server still counts the closed one): drop it before ExoPlayer
     * reaches it, and try again in 1 s while enough is buffered. Otherwise the ENDED path reconnects.
     */
    private void retryQueuedReconnect(int windowIndex) {
        if (windowIndex != player.getCurrentMediaItemIndex() + 1 || windowIndex != player.getMediaItemCount() - 1) return;
        player.removeMediaItem(windowIndex);
        // The server can refuse for ~10 s after closing: keep trying while the buffer still covers a connect.
        if (aheadRetries >= 10 || player.getTotalBufferedDuration() < 2000) { aheadRetries = 0; return; }
        aheadRetries++;
        MediaItem item = player.getCurrentMediaItem();
        Log.i(LOG_TAG, "Connection ahead refused; retry " + aheadRetries + " in 1 s");
        ui.postDelayed(() -> {
            // Skipped after a channel change, stop, or the item running out: those take their own path.
            if (player.getCurrentMediaItem() == item && player.getPlaybackState() == Player.STATE_READY)
                queueLiveReconnect(player.getCurrentMediaItemIndex(), true);
        }, 1000);
    }

    /** Recovery restarts the current item; a connection queued behind it would only be a second one. */
    private void dropQueuedReconnect() {
        int count = player.getMediaItemCount(), current = player.getCurrentMediaItemIndex();
        if (current + 1 < count) player.removeMediaItems(current + 1, count);
    }

    /** The innermost cause, e.g. "HTTP 502", "ProtocolException: Received HTTP_PROXY_AUTH (407)…", "SocketTimeoutException". */
    private static String describe(java.io.IOException error) {
        Throwable root = error;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException)
                return "HTTP " + ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            root = cause;
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.contains("://") ? "" : ": " + message);
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

    /**
     * Connects to the Xtream server: authenticates, then loads the live catalog in the background.
     *
     * @param url          server URL (the snapshot's serverUrl).
     * @param snapshotJson JSON object serialized by C#: sessionId, username, password, playerUserAgent, ….
     * @param userAgent    user agent of the player_api.php requests.
     */
    public void openPortal(String url, String snapshotJson, String userAgent) {
        ui.post(() -> {
            boolean preservingPlayback = player.getMediaItemCount() > 0;
            boolean reconnectingForChannel = preservingPlayback && portalChannelState.hasPendingChannel();
            closePortalOnUi(preservingPlayback || portalChannelState.hasPendingChannel()); // keep a queued channel
            portalChannelState.connecting();
            explicitPlaybackStopped = false;
            channelSessionId = "";
            channelRequestSent = false;
            if (reconnectingForChannel) {
                ui.removeCallbacks(channelSwitchTimeout);
                ui.postDelayed(channelSwitchTimeout, 30000);
            }
            final int generation = portalGeneration;
            portalOpenedAt = SystemClock.elapsedRealtime();
            Log.i(LOG_TAG, "Portal open generation=" + generation + (preservingPlayback ? " (stream kept)" : "")
                    + (reconnectingForChannel ? " (channel pending)" : ""));
            XtreamClient client;
            try {
                JSONObject snapshot = new JSONObject(snapshotJson);
                activeSessionId = snapshot.getString("sessionId");
                xtreamUsername = snapshot.getString("username");
                String playerUserAgent = snapshot.getString("playerUserAgent");
                okhttp3.Headers.of("User-Agent", userAgent, "User-Agent", playerUserAgent); // IllegalArgumentException if unusable
                playerHeadersJson = new JSONObject().put("User-Agent", playerUserAgent).toString();
                client = new XtreamClient(url, xtreamUsername, snapshot.getString("password"), userAgent);
            } catch (JSONException | IllegalArgumentException e) {
                onPortalUnavailable("CONFIGURATION_INVALID");
                return;
            }
            String favoritesKey = java.util.UUID.nameUUIDFromBytes(
                    (client.serverUrl + "\n" + xtreamUsername).getBytes(StandardCharsets.UTF_8)).toString();
            channelPanel.setProfile(favoritesKey);
            tvScreen.setProfile(favoritesKey, favoritesKey);
            xtream = client;
            account = null;
            ui.removeCallbacks(portalLoadTimeout);
            ui.postDelayed(portalLoadTimeout, 180000);
            loadCatalog(client, generation, true);
        });
    }

    /** Background: authenticate (on connect) then categories + streams. Results apply only to the same generation. */
    private void loadCatalog(XtreamClient client, int generation, boolean authenticate) {
        if (authenticate) emit("portal.phase", activeSessionId + "|AUTHENTICATING");
        runIo(() -> {
            if (authenticate) {
                XtreamClient.Account info;
                try {
                    info = client.authenticate();
                } catch (XtreamClient.XtreamException e) {
                    failXtream(generation, e.code, false);
                    return;
                } catch (RuntimeException e) {
                    failXtream(generation, unexpected(e), false);
                    return;
                }
                ui.post(() -> {
                    if (generation != portalGeneration) return;
                    account = info;
                    emit("portal.phase", activeSessionId + "|LOADING");
                    // Reconnect fast path: the URL needs no channel list, only the account.
                    if (portalChannelState.hasPendingChannel()) sendChannelRequest(portalChannelState.pendingChannelId());
                });
            }
            try {
                JSONObject data = XtreamCatalog.channelsData(client.liveCategories(), client.liveStreams());
                ui.post(() -> { if (generation == portalGeneration) onCatalog(data); });
            } catch (XtreamClient.XtreamException e) {
                failXtream(generation, e.code, true);
            } catch (JSONException e) {
                failXtream(generation, "INVALID_RESPONSE", true);
            } catch (RuntimeException e) {
                failXtream(generation, unexpected(e), true);
            }
        });
    }

    /** Skipped after release(): the executor is shut down and would reject the task. */
    private void runIo(Runnable task) {
        if (!xtreamIo.isShutdown()) xtreamIo.execute(task);
    }

    /** A bug or bad input on an xtreamIo thread must not crash the app; logs the class only (messages can hold URLs). */
    private static String unexpected(RuntimeException e) {
        Log.w(LOG_TAG, "Unexpected " + e.getClass().getSimpleName() + " talking to the server");
        return "INVALID_RESPONSE";
    }

    private void failXtream(int generation, String code, boolean catalog) {
        ui.post(() -> {
            if (generation != portalGeneration) return;
            Log.w(LOG_TAG, (catalog ? "Catalog" : "Authentication") + " failed: " + code);
            if (!catalog) { onPortalUnavailable(code); return; }
            tvScreen.reloadFailed();
            emit("portal.catalog-error", activeSessionId + "|" + code);
        });
    }

    private void onCatalog(JSONObject payload) {
        try {
            XtreamCatalog.validate(payload); // validate the entire snapshot before mutating UI
            channelPanel.setData(payload);
            tvScreen.setData(payload);
        } catch (JSONException e) {
            tvScreen.reloadFailed();
            emit("portal.catalog-error", activeSessionId + "|INVALID_RESPONSE");
            return;
        }
        channelSessionId = activeSessionId;
        if (portalChannelState.portalState() != PortalChannelState.PortalState.READY)
            Log.i(LOG_TAG, "Portal ready (" + payload.optJSONArray("channels").length() + " channels) +" + sincePortalOpen() + " ms");
        portalChannelState.ready(channelSessionId);
        ui.removeCallbacks(portalLoadTimeout);
        updatePortalVisibility();
        channelButton.setVisibility(View.VISIBLE);
        emit("portal.ready", activeSessionId);
        if (portalChannelState.canSendPending(channelSessionId)) sendChannelRequest(portalChannelState.pendingChannelId());
    }

    public void closePortal() {
        ui.post(() -> closePortalOnUi(false));
    }

    public void abortPortalReconnect(String reason) {
        ui.post(() -> onPortalUnavailable(reason));
    }

    /** Unity gave up retrying: drop the channel waiting on the server instead of "Reconnecting to server…" forever. */
    public void portalReconnectFailed() {
        ui.post(() -> {
            if (!waitingForChannel || portalChannelState.portalState() == PortalChannelState.PortalState.READY) return;
            clearChannelSwitch(); // the next pick requests a new reconnect
            tvScreen.channelFailed(player.getMediaItemCount() > 0 ? "the server is not responding · still playing the previous channel"
                    : "the server is not responding", playingChannelId);
        });
    }

    private void closePortalOnUi() { closePortalOnUi(false); }

    private void closePortalOnUi(boolean preserveChannelState) {
        ui.removeCallbacks(portalLoadTimeout);
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
        }
        portalGeneration++;
        activeSessionId = "";
        xtream = null;
        account = null;
        if (!preserveChannelState) {
            viewportWidth = 1920;
            viewportHeight = 1080;
            viewportX = viewportY = 0;
            setExpandedVideo(false);
        }
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
        channelRequestSent = false;
        portalChannelState.unavailable();
        waitingForChannel = pendingId != null;
        if (pendingId != null) tvScreen.setOpeningHint("Reconnecting to server…");
        if (player != null && (player.getMediaItemCount() > 0 || stoppedByUser)) {
            updatePortalVisibility();
            // One toast per outage, and none over a running stream: the player keeps going (or shows its own recovery).
            if (player.getMediaItemCount() == 0) {
                String text = "Server disconnected · reconnects when you pick a channel";
                boolean hint = "NETWORK".equals(reason) || reason.contains("TIMEOUT");
                Toast.makeText(activity, hint ? text + "\n" + DNS_HINT : text, hint ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
            emit("portal.unavailable", failedSessionId + "|" + reason);
            if (streamPortalRecovery) {
                finishFailedStreamRecovery();
                return;
            }
            boolean retryable = "NETWORK".equals(reason) || "STREAM_LINK_REFUSED".equals(reason)
                    || reason.contains("TIMEOUT") || reason.startsWith("HTTP_5");
            if (pendingId != null && !retryable) {
                clearChannelSwitch();
                tvScreen.channelFailed("Server access failed - reconnect or check account settings", playingChannelId);
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

    private long sincePortalOpen() { return SystemClock.elapsedRealtime() - portalOpenedAt; }

    /** Xtream links are deterministic: build the URL and play; no request round trip, so no switch timeout. */
    private void sendChannelRequest(String id) {
        if (xtream == null || account == null || channelRequestSent) return;
        Log.i(LOG_TAG, "Channel request " + id + " +" + sincePortalOpen() + " ms");
        channelRequestSent = true;
        switchingChannelId = id;
        ui.removeCallbacks(channelSwitchTimeout);
        play(xtream.streamUrl(id, account.ext), playerHeadersJson);
    }

    /** The native TV screen; uGUI is below it and cannot be tapped there. */
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
        playGeneration++; // drops a play() still posted for an earlier pick, so that request is no longer in flight
        boolean requestInFlight = channelRequestSent;
        channelRequestSent = false; // latest pick wins
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
        if (!requestInFlight) channelReconnectAttempted = false;
        waitingForChannel = true;
        setExpandedVideo(false); // Channel selection ends a manual enlargement.
        if (portalChannelState.portalState() == PortalChannelState.PortalState.READY) {
            portalChannelState.select(id);
            sendChannelRequest(id);
        } else {
            tvScreen.setOpeningHint("Reconnecting to server…");
            if (portalChannelState.selectWhileUnavailable(id)) emit("ui.portal-reconnect", id);
            else sendChannelRequest(id); // server still connecting: plays as soon as the account is known
        }
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
        channelReconnectAttempted = false;
        portalChannelState.pendingHandled();
    }

    private void setExpandedVideo(boolean expanded) {
        expandedVideo = expanded;
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
                && !playbackGate && !backgrounded
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
        if (replayingStoppedUrl) { // the kept URL failed: take the normal channel request path at once
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
        long activeNow = playbackInterruption.activeTime(now);
        if (recoveryStartedAt < 0) {
            while (!recoveryIncidents.isEmpty() && now - recoveryIncidents.peekFirst() >= 300_000)
                recoveryIncidents.removeFirst();
            if (recoveryIncidents.size() >= 3) { failStream("RECOVERY_RATE_LIMIT"); return false; }
            recoveryIncidents.addLast(now);
            recoveryStartedAt = activeNow;
        }
        long remaining = 30_000 - (activeNow - recoveryStartedAt);
        if (remaining <= 0) { failStream("RECOVERY_TIMEOUT"); return false; }
        ui.removeCallbacks(streamDeadline);
        ui.postDelayed(streamDeadline, remaining);
        return true;
    }

    /** A refused stream first asks the server for the account state and replays; a reconnect only once that proves stale. */
    private void resolveStreamLink() {
        if (!canRecoverStream() || resolvingStreamLink) return;
        if (!beginRecovery()) return;
        if (!playingChannelId.isEmpty()
                && portalChannelState.portalState() == PortalChannelState.PortalState.UNAVAILABLE) {
            reconnectPortalForStream("STREAM_LINK_REFUSED");
            return;
        }
        // The URL replayed in this incident is refused too: check the account once more, then fail or reconnect.
        boolean replayedOnce = reloadPolicy.linkResolveUsed();
        if ((!replayedOnce && !reloadPolicy.requestLinkResolve()) || playingChannelId.isEmpty()
                || portalChannelState.portalState() != PortalChannelState.PortalState.READY) {
            failStream("STREAM_RECOVERY_EXHAUSTED");
            return;
        }
        cancelRetry();
        resolvingStreamLink = streamPortalRecovery = waitingForChannel = true;
        portalChannelState.select(playingChannelId);
        verifyAccountThenReplay(playingChannelId, replayedOnce);
    }

    /** Stream refused: ask the server why before replaying the same URL (expired, too many connections, or still fine). */
    private void verifyAccountThenReplay(String id, boolean replayedOnce) {
        XtreamClient client = xtream;
        int generation = portalGeneration;
        accountChecks++;
        runIo(() -> {
            XtreamClient.Account info = null;
            String code = null;
            try {
                info = client.authenticate();
            } catch (XtreamClient.XtreamException e) {
                code = e.code;
            } catch (RuntimeException e) {
                code = unexpected(e);
            }
            XtreamClient.Account result = info;
            String action = ReloadPolicy.afterAccountCheck(replayedOnce, code,
                    info == null ? 0 : info.activeConnections, info == null ? 0 : info.maxConnections);
            ui.post(() -> {
                accountChecks--;
                if (generation != portalGeneration || !streamPortalRecovery) return;
                if (result != null) account = result;
                if (ReloadPolicy.REPLAY.equals(action)) sendChannelRequest(id);
                else if (ReloadPolicy.RECONNECT.equals(action)) reconnectPortalForStream("STREAM_LINK_REFUSED");
                else failStream(action);
            });
        });
    }

    /** The session is stale (its links are refused, e.g. after a long ad pause): reconnect and replay the channel. */
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
        Log.w(LOG_TAG, "Stream failed: " + code + " channel=" + playingChannelId);
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
        player.pause();
        emit("error", code);
        tvScreen.channelFailed(failureText(code), playingChannelId);
    }

    private static String failureText(String code) {
        if ("MAX_CONNECTIONS".equals(code)) return "Too many connections on this account. Close another player and try again.";
        if ("CHANNEL_UNAVAILABLE".equals(code)) return "This channel is not available right now.";
        if (code.startsWith("ACCOUNT_EXPIRED:")) return "The account expired on " + code.substring(16) + ".";
        if (code.startsWith("ACCOUNT_EXPIRED")) return "The account has expired.";
        if ("FORBIDDEN".equals(code)) return "The server denied access. Check the username and password.";
        if ("ACCOUNT_DISABLED".equals(code)) return "The account is disabled.";
        if ("AUTHENTICATION_REJECTED".equals(code)) return "The server rejected the username or password.";
        return "Playback stopped (" + code + "). Select the channel to try again.";
    }

    private void finishFailedStreamRecovery() {
        ui.removeCallbacks(recoveryDeadline);
        streamPortalRecovery = false;
        String pendingId = portalChannelState.pendingChannelId();
        if (pendingId != null) recoveryChannelId = pendingId;
        clearChannelSwitch();
        cancelRetry();
        failStream("LINK_RESOLVE_FAILED");
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
        button.setFocusable(false); // keep focus (and Back) on the TV screen
        button.setOnClickListener(onClick);
        return button;
    }

    private void toggleChannels() {
        if (channelPanel.getVisibility() == View.VISIBLE) {
            channelPanel.dismiss();
            tvScreen.requestFocus();
        } else {
            channelPanel.setVisibility(View.VISIBLE);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

    /** Kept for C#: the native TV screen is the only UI, so there is nothing to show or hide. */
    public void setPortalVisible(boolean visible) {
        ui.post(this::updatePortalVisibility);
    }

    private void updatePortalVisibility() {
        // native TV screen only; nothing to show
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
            if (playbackGate || explicitPlaybackStopped) {
                // Not played: the pick stays pending, so the gate lift or the next catalog can send it again.
                channelRequestSent = false;
                return;
            }
            String scheme = Uri.parse(url).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                channelRequestSent = false;
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
            playedStably = false;
            aheadRetries = 0;
            emit("firstFrame", "false");
            playingSource = new DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(new StreamLoadErrorPolicy());
            player.setMediaSource(playingSource.createMediaSource(mediaItem));
            player.prepare();
            if (backgrounded) resumeOnStart = true; // e.g. stream recovery finished while the app was hidden
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
        rewardPrompt.setFocusableInTouchMode(true); // takes Back from the TV screen
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
            if (!backgrounded && !playbackGate && !modal && portalLoading != null && portalLoading.isShown()) {
                mode = 1; // Connecting to server / Could not connect to the server
                width = portalLoading.getWidth();
            } else if (!backgrounded && !playbackGate && !modal && nativeLayer.isShown()) {
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
            if (portalLoading != null) { // keep Retry/Back above the banner
                int padding = PortalStyle.dp(activity, 24);
                portalLoading.setPadding(padding, padding, padding, padding + heightPixels);
            }
        });
    }

    // ---- Pro (Unity's AndroidProfileUi owns the store; payload {pro, monthlyPrice, lifetimePrice}) ----

    public void showProPanel(String json) {
        ui.post(() -> proDialog.show(json));
    }

    public void hideProPanel() {
        ui.post(() -> proDialog.dismiss());
    }

    /** "Upgrade to Pro" nudge over the stream; Go Pro opens the sheet. No-op while it or the sheet is open. */
    public void showUpsell() {
        ui.post(() -> {
            if ((upsell != null && upsell.isShowing()) || proDialog.isShowing()) return;
            StringBuilder features = new StringBuilder();
            for (String feature : ProDialog.FEATURES) features.append(features.length() == 0 ? "" : "\n").append("☆  ").append(feature);
            upsell = new android.app.AlertDialog.Builder(activity, PortalStyle.dialogTheme())
                    .setTitle("Upgrade to Pro")
                    .setMessage(features)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Go Pro", (d, which) -> emit("ui.pro.open", ""))
                    .show();
            if (upsell.getWindow() != null) PortalStyle.trackWindow(upsell.getWindow().getDecorView());
        });
    }

    public void hideUpsell() {
        ui.post(() -> { if (upsell != null) upsell.dismiss(); });
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
                title.setText("Connecting to server…");
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
                contentLayer.addView(portalLoading, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            portalLoadingMessage.setText(text);
            portalStatusTitle.setText("Connecting to server…");
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
            portalStatusTitle.setText("Could not connect to the server");
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
        updatePlaybackInterruption();
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
                    resumeInterruptedPlayback();
                }
            }
            resumeAfterGate = false;
            armBufferingTimeout();
            // A channel request that play() dropped under the gate (no-op while one is still in flight; an account
            // check in flight sends it itself).
            if (accountChecks == 0 && portalChannelState.canSendPending(channelSessionId)) sendChannelRequest(portalChannelState.pendingChannelId());
        }
    }

    private void updatePlaybackInterruption() {
        playbackInterruption.setPaused(playbackGate || backgrounded, SystemClock.elapsedRealtime());
        if (playbackGate || backgrounded) {
            cancelRetry();
            ui.removeCallbacks(bufferingTimeout);
            ui.removeCallbacks(streamDeadline);
            ui.removeCallbacks(stablePlayback);
        }
    }

    private void resumeInterruptedPlayback() {
        // Keep the media source, headers, position and buffered samples for a healthy stream.
        // A stale live window is handled by the error path instead of discarding the buffer on every ad.
        player.setPlayWhenReady(true);
        PlaybackException error = player.getPlayerError();
        if (error != null) handlePlayerError(error);
        else if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        else if (player.getPlaybackState() == Player.STATE_ENDED && isLive()) scheduleRetry();
        if (canRecoverStream() && recoveryStartedAt >= 0 && !player.isPlaying()) beginRecovery();
        armBufferingTimeout();
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

    public void release() {
        ui.post(() -> {
            ui.removeCallbacks(channelSwitchTimeout);
            xtreamIo.shutdownNow();
            activity.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            activity.unregisterComponentCallbacks(themeConfiguration);
            if (streamInfo != null) streamInfo.dismiss();
            if (profileEditor != null) profileEditor.dismiss();
            if (proDialog != null) proDialog.dismiss();
            if (upsell != null) upsell.dismiss();
            if (home != null) home.hide();
            closePortalOnUi();
            logoLoader.release();
            videoView.setKeepScreenOn(false);
            player.release();
            ViewGroup root = activity.findViewById(android.R.id.content);
            root.removeView(contentLayer);
        });
    }

    private void emit(String type, String data) {
        if (listener != null) listener.onEvent(type, data);
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
