package com.stbemu.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import java.util.Locale;
import java.util.function.Supplier;

/** Read-only playback diagnostics. Poll only while the dialog is visible, on the player looper. */
final class StreamInfoDialog {
    private final Activity activity;
    private final Handler handler;
    private final HybridPlayer player;
    private final DefaultAllocator allocator;
    private final Supplier<String> network;
    private final Supplier<String> decoder;
    private AlertDialog dialog;
    private TextView text;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            text.setText(snapshot());
            handler.postDelayed(this, 1000);
        }
    };

    StreamInfoDialog(Activity activity, Handler handler, HybridPlayer player, DefaultAllocator allocator,
            Supplier<String> network, Supplier<String> decoder) {
        this.activity = activity;
        this.handler = handler;
        this.player = player;
        this.allocator = allocator;
        this.network = network;
        this.decoder = decoder;
    }

    void show() {
        if (dialog != null) return;
        text = new TextView(activity);
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        text.setTextSize(16);
        text.setText(snapshot());
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(text);
        dialog = new AlertDialog.Builder(activity).setTitle("Stream info")
                .setView(scroll).setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(ignored -> {
            handler.removeCallbacks(refresh);
            dialog = null;
            text = null;
        });
        dialog.show();
        handler.postDelayed(refresh, 1000);
    }

    void dismiss() {
        handler.removeCallbacks(refresh);
        if (dialog != null) dialog.dismiss();
    }

    private String snapshot() {
        String mpv = player.streamSummary();
        if (mpv != null) return mpv;
        Format video = player.getVideoFormat();
        Format audio = player.getAudioFormat();
        String resolution = video != null && video.width > 0 && video.height > 0
                ? video.width + " × " + video.height : "No data yet";
        long offset = player.getCurrentLiveOffset();
        String state = player.getPlayerError() != null ? "Playback error"
                : player.getPlaybackState() == Player.STATE_BUFFERING ? "Buffering"
                : player.isPlaying() ? "Playing"
                : player.getPlaybackState() == Player.STATE_READY ? "Paused"
                : player.getPlaybackState() == Player.STATE_ENDED ? "Ended" : "Stopped";
        return "State: " + state
                + "\nResolution: " + resolution
                + "\nSource FPS: " + (video != null && video.frameRate > 0 ? number(video.frameRate) : "Unknown")
                + "\nCodec video: " + (video != null && video.sampleMimeType != null ? video.sampleMimeType : "Unknown")
                + "\nDecoder: " + decoder.get()
                + "\nPlayback error: " + (player.getPlayerError() == null ? "None" : player.getPlayerError().getErrorCodeName())
                + "\nBitrate video: " + bitrate(video)
                + "\nBitrate audio: " + bitrate(audio)
                + "\n\nBuffered: " + number(player.getTotalBufferedDuration() / 1000.0) + " s"
                + "\nRAM buffer in use: " + number(allocator.getTotalBytesAllocated() / 1048576.0) + " MiB"
                + "\nDisk cache: off"
                + "\nLive latency: " + (offset == C.TIME_UNSET ? "Unknown" : number(offset / 1000.0) + " s")
                + "\n\n" + network.get()
                + "\n\nUpdated every second. Bitrate comes from the source, not from measured network speed.";
    }

    private static String bitrate(Format format) {
        if (format == null) return "Unknown";
        if (format.averageBitrate > 0) return number(format.averageBitrate / 1000.0) + " kbps (average)";
        if (format.peakBitrate > 0) return number(format.peakBitrate / 1000.0) + " kbps (peak)";
        return "Not provided by the source";
    }

    private static String number(double value) { return String.format(Locale.getDefault(), "%.1f", value); }
}
