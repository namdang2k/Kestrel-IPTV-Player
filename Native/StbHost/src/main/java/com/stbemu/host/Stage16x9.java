package com.stbemu.host;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Largest 16:9 box that fits its parent; placed centred by the parent's gravity. Holds the video and the portal so a
 * 16:9 stream is not stretched on a 20:9 phone and the 1280x720 portal layout keeps its aspect (black bars at the sides).
 */
final class Stage16x9 extends FrameLayout {
    Stage16x9(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] size = fit(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(MeasureSpec.makeMeasureSpec(size[0], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size[1], MeasureSpec.EXACTLY));
    }

    /** {width, height} of the largest 16:9 box inside width x height. */
    static int[] fit(int width, int height) {
        return (long) width * 9 > (long) height * 16 ? new int[] {height * 16 / 9, height} : new int[] {width, width * 9 / 16};
    }
}
