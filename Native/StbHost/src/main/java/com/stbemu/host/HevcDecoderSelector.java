package com.stbemu.host;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import java.util.ArrayList;
import java.util.List;

/** Keep platform preference order, including platform software codecs, but skip a failed HEVC decoder. */
final class HevcDecoderSelector implements MediaCodecSelector {
    private final DecoderRetryBudget budget = new DecoderRetryBudget();

    void reset() { budget.reset(); }
    int attempts() { return budget.attempts(); }

    @Override public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean secure, boolean tunneling)
            throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
        if (!MimeTypes.VIDEO_H265.equals(mimeType)) return codecs;
        List<MediaCodecInfo> allowed = new ArrayList<>();
        for (MediaCodecInfo codec : codecs) if (budget.allows(codec.name)) allowed.add(codec);
        return allowed;
    }

    boolean excludeAndHasAlternative(android.content.Context context, Format format, String failedDecoder)
            throws MediaCodecUtil.DecoderQueryException {
        // Never change DRM/security requirements as a recovery strategy.
        if (format == null || !MimeTypes.VIDEO_H265.equals(format.sampleMimeType) || format.drmInitData != null
                || !budget.exclude(failedDecoder)) return false;
        for (MediaCodecInfo candidate : getDecoderInfos(format.sampleMimeType, false, false)) {
            if (candidate.isFormatSupported(context, format)) return true;
        }
        return false;
    }
}
