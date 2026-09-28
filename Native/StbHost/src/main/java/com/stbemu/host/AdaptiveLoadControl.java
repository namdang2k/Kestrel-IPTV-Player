package com.stbemu.host;

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

/**
 * The given LoadControl, but after a stall playback resumes only once the buffer the network tier asks for is loaded
 * (see {@link NetworkQuality}). The threshold can change during playback; for live it is capped at half the target
 * live offset, as DefaultLoadControl does, so it stays reachable behind the live edge.
 */
final class AdaptiveLoadControl implements LoadControl {
    private final LoadControl base;
    private volatile long resumeAfterStallUs; // written on the UI thread, read on the playback thread

    AdaptiveLoadControl(LoadControl base, long resumeAfterStallMs) {
        this.base = base;
        setResumeAfterStallMs(resumeAfterStallMs);
    }

    void setResumeAfterStallMs(long ms) { resumeAfterStallUs = ms * 1000; }

    long resumeAfterStallMs() { return resumeAfterStallUs / 1000; }

    @Override public boolean shouldStartPlayback(Parameters parameters) {
        if (!base.shouldStartPlayback(parameters)) return false;
        if (!parameters.rebuffering) return true;
        long required = resumeAfterStallUs;
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) required = Math.min(required, parameters.targetLiveOffsetUs / 2);
        return parameters.bufferedDurationUs / Math.max(parameters.playbackSpeed, 0.1f) >= required;
    }

    @Override public void onPrepared(PlayerId playerId) { base.onPrepared(playerId); }
    @Override public void onTracksSelected(Parameters parameters, TrackGroupArray trackGroups, ExoTrackSelection[] trackSelections) {
        base.onTracksSelected(parameters, trackGroups, trackSelections);
    }
    @Override public void onStopped(PlayerId playerId) { base.onStopped(playerId); }
    @Override public void onReleased(PlayerId playerId) { base.onReleased(playerId); }
    @Override public Allocator getAllocator(PlayerId playerId) { return base.getAllocator(playerId); }
    @Override public long getBackBufferDurationUs(PlayerId playerId) { return base.getBackBufferDurationUs(playerId); }
    @Override public boolean retainBackBufferFromKeyframe(PlayerId playerId) { return base.retainBackBufferFromKeyframe(playerId); }
    @Override public boolean shouldContinueLoading(Parameters parameters) { return base.shouldContinueLoading(parameters); }
    @Override public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline, MediaSource.MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        return base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }
}
