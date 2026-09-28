package com.stbemu.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import java.util.Locale;
import java.util.function.Supplier;

/** Read-only playback diagnostics. Poll only while the dialog is visible, on the player looper. */
final class StreamInfoDialog {
    private final Activity activity;
    private final Handler handler;
    private final ExoPlayer player;
    private final DefaultAllocator allocator;
    private final Supplier<String> network;
    private AlertDialog dialog;
    private TextView text;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            text.setText(snapshot());
            handler.postDelayed(this, 1000);
        }
    };

    StreamInfoDialog(Activity activity, Handler handler, ExoPlayer player, DefaultAllocator allocator, Supplier<String> network) {
        this.activity = activity;
        this.handler = handler;
        this.player = player;
        this.allocator = allocator;
        this.network = network;
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
        dialog = new AlertDialog.Builder(activity).setTitle("Thông tin stream")
                .setView(scroll).setPositiveButton("Đóng", null).create();
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
        Format video = player.getVideoFormat();
        Format audio = player.getAudioFormat();
        String resolution = video != null && video.width > 0 && video.height > 0
                ? video.width + " × " + video.height : "Chưa có dữ liệu";
        long offset = player.getCurrentLiveOffset();
        String state = player.getPlayerError() != null ? "Lỗi phát"
                : player.getPlaybackState() == Player.STATE_BUFFERING ? "Đang nạp buffer"
                : player.isPlaying() ? "Đang phát"
                : player.getPlaybackState() == Player.STATE_READY ? "Tạm dừng"
                : player.getPlaybackState() == Player.STATE_ENDED ? "Đã kết thúc" : "Đã dừng";
        return "Trạng thái: " + state
                + "\nĐộ phân giải: " + resolution
                + "\nFPS nguồn: " + (video != null && video.frameRate > 0 ? number(video.frameRate) : "Không rõ")
                + "\nCodec video: " + (video != null && video.sampleMimeType != null ? video.sampleMimeType : "Không rõ")
                + "\nBitrate video: " + bitrate(video)
                + "\nBitrate audio: " + bitrate(audio)
                + "\n\nBuffer còn phát được: " + number(player.getTotalBufferedDuration() / 1000.0) + " giây"
                + "\nRAM buffer đang dùng: " + number(allocator.getTotalBytesAllocated() / 1048576.0) + " MiB"
                + "\nCache đĩa: Không bật"
                + "\nĐộ trễ live: " + (offset == C.TIME_UNSET ? "Không rõ" : number(offset / 1000.0) + " giây")
                + "\n\n" + network.get()
                + "\n\nCập nhật mỗi giây. Bitrate lấy từ thông tin nguồn, không phải tốc độ mạng đo được.";
    }

    private static String bitrate(Format format) {
        if (format == null) return "Không rõ";
        if (format.averageBitrate > 0) return number(format.averageBitrate / 1000.0) + " kbps (trung bình)";
        if (format.peakBitrate > 0) return number(format.peakBitrate / 1000.0) + " kbps (đỉnh)";
        return "Nguồn không cung cấp";
    }

    private static String number(double value) { return String.format(Locale.getDefault(), "%.1f", value); }
}
