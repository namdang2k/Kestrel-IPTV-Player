package com.stbemu.host;

/** MAG video coordinates use 1920x1080, independently of the portal's CSS resolution. */
final class VideoViewport {
    static int[] scale(int stageWidth, int stageHeight, double width, double height, double x, double y) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(x) || !Double.isFinite(y)
                || width <= 0 || height <= 0 || width > 7680 || height > 4320
                || Math.abs(x) > 7680 || Math.abs(y) > 4320) return null;
        return new int[] {Math.max(1, (int) Math.round(width * stageWidth / 1920)),
                Math.max(1, (int) Math.round(height * stageHeight / 1080)),
                (int) Math.round(x * stageWidth / 1920), (int) Math.round(y * stageHeight / 1080)};
    }
}
