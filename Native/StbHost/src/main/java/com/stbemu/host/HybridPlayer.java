package com.stbemu.host;

import android.content.*;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import dev.jdtech.mpv.MPVLib;
import java.util.*;

/** UI-thread playback facade. Media3 starts each item; MPV is a one-way fallback for that item.
 * A stopped Media3 instance is retained for the next channel, but owns no codec or surface during MPV playback.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class HybridPlayer implements SurfaceHolder.Callback {
    private final Context context;
    private final Handler ui;
    private final ExoPlayer exo;
    private final SurfaceView view;
    private final EngineFallbackPolicy fallback = new EngineFallbackPolicy();
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private boolean hasFocus, focusResume, released;
    private Player.Listener listener;
    private MpvSession mpv;
    private MpvStreamGateway gateway;
    private boolean usingMpv, wantsPlay, loaded, firstFrame, attached, lastPlaying, live;
    private int state = Player.STATE_IDLE;
    private PlaybackException error;
    private String url;
    private Map<String, String> headers = Collections.emptyMap();
    private long startPosition;
    private float volume = 1f;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (usingMpv) pause();
        }
    };

    HybridPlayer(Context context, Handler ui, ExoPlayer exo, SurfaceView view) {
        this.context = context;
        this.ui = ui;
        this.exo = exo;
        this.view = view;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(change -> {
                    if (!usingMpv) return;
                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        hasFocus = true;
                        if (focusResume) { focusResume = false; setPlayWhenReady(true); }
                    } else {
                        boolean resume = wantsPlay && change != AudioManager.AUDIOFOCUS_LOSS;
                        setPlayWhenReady(false);
                        focusResume = resume;
                    }
                }, ui).build();
        if (android.os.Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        view.getHolder().addCallback(this);
        exo.setVideoSurfaceView(view);
        exo.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (!usingMpv && !released && listener != null) listener.onRenderedFirstFrame();
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (!usingMpv && !released && listener != null) listener.onPlaybackStateChanged(state);
            }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (!usingMpv && !released && listener != null) listener.onPlayWhenReadyChanged(ready, reason);
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (!usingMpv && !released && listener != null) listener.onIsPlayingChanged(playing);
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (!usingMpv && !released && listener != null) listener.onPlayerError(error);
            }
        });
    }

    void addListener(Player.Listener listener) { this.listener = listener; }
    boolean isMpv() { return usingMpv; }

    void setMediaSource(MediaSource source, String url, Map<String, String> headers, boolean live) {
        destroyMpv();
        usingMpv = false;
        fallback.reset();
        this.url = url;
        this.headers = new HashMap<>(headers);
        this.live = live;
        exo.setVideoSurfaceView(view);
        exo.setMediaSource(source);
    }

    boolean tryFallback(PlaybackException cause) {
        if (released || usingMpv || url == null || !fallback.claim(cause.errorCode)) return false;
        wantsPlay = exo.getPlayWhenReady();
        startPosition = live || exo.isCurrentMediaItemLive() ? 0 : exo.getCurrentPosition();
        live |= exo.isCurrentMediaItemLive();
        volume = exo.getVolume();
        usingMpv = true; // Suppress stop/error callbacks from the old engine before releasing codecs.
        exo.pause();
        exo.stop();
        exo.clearVideoSurfaceView(view);
        exo.clearMediaItems();
        startMpv();
        return true;
    }

    private void startMpv() {
        destroyMpv();
        loaded = firstFrame = lastPlaying = false;
        error = null;
        changeState(Player.STATE_BUFFERING);
        try {
            gateway = new MpvStreamGateway(url, headers);
            mpv = MpvSession.create(context);
            if (mpv == null) throw new IllegalStateException("MPV initialization failed");
            option("config", "no");
            option("terminal", "no");
            option("msg-level", "all=no");
            option("vo", "gpu");
            option("gpu-context", "android");
            option("opengl-es", "yes");
            option("hwdec", "mediacodec-copy"); // Falls back to FFmpeg software decoding if unavailable.
            option("ao", "audiotrack");
            option("idle", "yes");
            option("keep-open", "yes");
            option("cache", "yes");
            option("cache-on-disk", "no");
            option("cache-secs", "15");
            option("demuxer-max-bytes", "33554432");
            option("demuxer-max-back-bytes", "0");
            option("network-timeout", "10");
            option("pause", "yes");
            if (startPosition > 0) option("start", Double.toString(startPosition / 1000.0));
            mpv.init();
            MpvSession owner = mpv;
            mpv.addObserver(new MPVLib.EventObserver() {
                // Never call native methods from the JNI event thread (destroy joins that thread).
                @Override public void event(int id) { ui.post(() -> {
                    if (mpv != owner || released) return;
                    if (id == 8) { loaded = true; refreshMpvState(); }
                    if (id == 21 && loaded) {
                        if (!firstFrame) { firstFrame = true; if (listener != null) listener.onRenderedFirstFrame(); }
                        refreshMpvState();
                    }
                    if (id == 7 && state != Player.STATE_ENDED) {
                        if (loaded && Boolean.TRUE.equals(owner.getPropertyBoolean("eof-reached"))) changeState(Player.STATE_ENDED);
                        else failMpv();
                    }
                }); }
                @Override public void eventProperty(String name) { changed(); }
                @Override public void eventProperty(String name, long value) { changed(); }
                @Override public void eventProperty(String name, double value) { changed(); }
                @Override public void eventProperty(String name, boolean value) {
                    if (name.equals("eof-reached") && value) ui.post(() -> {
                        if (mpv == owner && !released && error == null) changeState(Player.STATE_ENDED);
                    });
                    else changed();
                }
                @Override public void eventProperty(String name, String value) { changed(); }
                private void changed() { ui.post(() -> { if (mpv == owner && !released) refreshMpvState(); }); }
            });
            mpv.observeProperty("paused-for-cache", 3);
            mpv.observeProperty("pause", 3);
            mpv.observeProperty("eof-reached", 3);
            mpv.setPropertyDouble("volume", volume * 100);
            attachSurface();
            mpv.command(new String[]{"loadfile", gateway.route(url)});
            applyPause();
        } catch (Exception | LinkageError failure) {
            destroyMpv();
            failMpv();
        }
    }

    private void option(String name, String value) {
        if (mpv.setOptionString(name, value) < 0) throw new IllegalStateException("MPV option: " + name);
    }

    private void refreshMpvState() {
        if (mpv == null || error != null || state == Player.STATE_ENDED) return;
        changeState(loaded && !Boolean.TRUE.equals(mpv.getPropertyBoolean("paused-for-cache"))
                ? Player.STATE_READY : Player.STATE_BUFFERING);
    }

    private void changeState(int next) {
        if (state != next) {
            state = next;
            if (listener != null) listener.onPlaybackStateChanged(next);
        }
        boolean playing = isPlaying();
        if (playing != lastPlaying) {
            lastPlaying = playing;
            if (listener != null) listener.onIsPlayingChanged(playing);
        }
    }

    private void failMpv() {
        if (error != null) return;
        error = new PlaybackException("MPV playback failed", null, PlaybackException.ERROR_CODE_DECODING_FAILED);
        changeState(Player.STATE_IDLE);
        if (listener != null) listener.onPlayerError(error);
    }

    private void applyPause() {
        if (mpv == null) return;
        if (wantsPlay && !hasFocus) hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (wantsPlay && !hasFocus) wantsPlay = false;
        mpv.setPropertyBoolean("pause", !wantsPlay || !attached);
    }

    private void destroyMpv() {
        MpvSession old = mpv;
        mpv = null; // Invalidates every queued callback, including END_FILE from stop/destroy.
        if (gateway != null) { gateway.close(); gateway = null; }
        if (old != null) {
            if (attached) { old.setPropertyString("vo", "null"); old.detachSurface(); }
            old.destroy();
        }
        attached = false;
        focusResume = false;
        if (hasFocus) { audio.abandonAudioFocusRequest(focus); hasFocus = false; }
    }

    private void attachSurface() {
        if (mpv == null || attached || !view.getHolder().getSurface().isValid()) return;
        mpv.attachSurface(view.getHolder().getSurface());
        attached = true;
        mpv.setPropertyString("vo", "gpu");
        applyPause();
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { attachSurface(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mpv != null) mpv.setPropertyString("android-surface-size", width + "x" + height);
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (mpv != null && attached) {
            mpv.setPropertyBoolean("pause", true);
            mpv.setPropertyString("vo", "null");
            mpv.detachSurface();
            attached = false;
            changeState(state);
        }
    }

    void prepare() { if (usingMpv) { if (mpv == null) startMpv(); } else exo.prepare(); }
    void play() { setPlayWhenReady(true); }
    void pause() { setPlayWhenReady(false); }
    void setPlayWhenReady(boolean ready) {
        if (!usingMpv) { exo.setPlayWhenReady(ready); return; }
        focusResume = false;
        wantsPlay = ready;
        applyPause();
        if (!wantsPlay && hasFocus) { audio.abandonAudioFocusRequest(focus); hasFocus = false; }
        if (listener != null) listener.onPlayWhenReadyChanged(wantsPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
        changeState(state);
    }
    boolean getPlayWhenReady() { return usingMpv ? wantsPlay : exo.getPlayWhenReady(); }
    boolean isPlaying() { return usingMpv ? wantsPlay && attached && state == Player.STATE_READY && error == null : exo.isPlaying(); }
    int getPlaybackState() { return usingMpv ? state : exo.getPlaybackState(); }
    PlaybackException getPlayerError() { return usingMpv ? error : exo.getPlayerError(); }
    int getMediaItemCount() { return usingMpv ? (url == null ? 0 : 1) : exo.getMediaItemCount(); }
    boolean isCurrentMediaItemLive() { return usingMpv ? live : exo.isCurrentMediaItemLive(); }
    Timeline getCurrentTimeline() { return usingMpv ? Timeline.EMPTY : exo.getCurrentTimeline(); }
    int getCurrentMediaItemIndex() { return usingMpv ? 0 : exo.getCurrentMediaItemIndex(); }
    long getCurrentPosition() { return usingMpv ? (long) (number("time-pos") * 1000) : exo.getCurrentPosition(); }
    long getCurrentLiveOffset() { return usingMpv ? C.TIME_UNSET : exo.getCurrentLiveOffset(); }
    long getTotalBufferedDuration() { return usingMpv ? (long) (number("demuxer-cache-duration") * 1000) : exo.getTotalBufferedDuration(); }
    private double number(String property) { Double n = mpv == null ? null : mpv.getPropertyDouble(property); return n == null ? 0 : n; }
    Format getVideoFormat() { return usingMpv ? null : exo.getVideoFormat(); }
    Format getAudioFormat() { return usingMpv ? null : exo.getAudioFormat(); }
    void seekTo(long position) {
        if (usingMpv) { startPosition = position; if (mpv != null) mpv.command(new String[]{"seek", Double.toString(position / 1000.0), "absolute"}); }
        else exo.seekTo(position);
    }
    void seekToDefaultPosition() {
        if (usingMpv) { startPosition = 0; if (mpv != null && live) startMpv(); }
        else exo.seekToDefaultPosition();
    }
    void setVolume(float value) {
        volume = Math.max(0, Math.min(1, value));
        exo.setVolume(volume);
        if (mpv != null) mpv.setPropertyDouble("volume", volume * 100);
    }
    void stop() {
        if (usingMpv) { startPosition = live ? 0 : getCurrentPosition(); destroyMpv(); changeState(Player.STATE_IDLE); }
        else exo.stop();
    }
    void clearMediaItems() {
        destroyMpv();
        url = null;
        usingMpv = false;
        exo.clearMediaItems();
        if (listener != null) listener.onPlaybackStateChanged(exo.getPlaybackState());
    }
    void release() {
        released = true;
        destroyMpv();
        view.getHolder().removeCallback(this);
        context.unregisterReceiver(noisy);
        exo.release();
    }
    String decoderSummary() {
        if (mpv == null) return "MPV (not initialized)";
        String decoder = mpv.getPropertyString("hwdec-current");
        return "MPV / " + (decoder == null || decoder.equals("no") ? "FFmpeg software" : decoder);
    }
    String streamSummary() {
        if (!usingMpv) return null;
        return "Engine: MPV\nState: " + (error != null ? "Playback error" : isPlaying() ? "Playing"
                : state == Player.STATE_BUFFERING ? "Buffering" : state == Player.STATE_ENDED ? "Ended" : "Paused")
                + "\nResolution: " + (int) number("width") + " x " + (int) number("height")
                + "\nDecoder: " + decoderSummary()
                + "\nBuffered: " + String.format(Locale.ROOT, "%.1f s", number("demuxer-cache-duration"))
                + "\nPosition: " + getCurrentPosition() / 1000 + " s\nDisk cache: off";
    }
}
