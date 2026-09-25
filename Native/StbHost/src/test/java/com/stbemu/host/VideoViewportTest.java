package com.stbemu.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoViewportTest {
    @Test public void fullscreenUsesVideoPlaneNotCssPlane() {
        assertArrayEquals(new int[]{1280, 720, 0, 0}, VideoViewport.scale(1280, 720, 1920, 1080, 0, 0));
    }
    @Test public void previewScalesSizeAndOrigin() {
        assertArrayEquals(new int[]{640, 360, 128, 72}, VideoViewport.scale(1280, 720, 960, 540, 192, 108));
    }
    @Test public void rotationRecalculatesFromOriginalCoordinates() {
        assertArrayEquals(new int[]{360, 203, 72, 41}, VideoViewport.scale(720, 405, 960, 540, 192, 108));
    }
    @Test public void malformedViewportDoesNotReplaceLastValidLayout() {
        assertNull(VideoViewport.scale(1280, 720, 0, 1080, 0, 0));
        assertNull(VideoViewport.scale(1280, 720, Double.NaN, 1080, 0, 0));
        assertNull(VideoViewport.scale(1280, 720, 1920, 1080, Double.POSITIVE_INFINITY, 0));
        assertNull(VideoViewport.scale(1280, 720, 1e20, 1080, 0, 0));
    }
}
