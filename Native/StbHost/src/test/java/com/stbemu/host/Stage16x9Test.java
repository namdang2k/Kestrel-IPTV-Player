package com.stbemu.host;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class Stage16x9Test {
    @Test public void widePhoneLandscape_fitsHeight() { assertArrayEquals(new int[] {1280, 720}, Stage16x9.fit(1600, 720)); }
    @Test public void portrait_fitsWidth() { assertArrayEquals(new int[] {720, 405}, Stage16x9.fit(720, 1600)); }
    @Test public void exact16x9_unchanged() { assertArrayEquals(new int[] {1920, 1080}, Stage16x9.fit(1920, 1080)); }
    @Test public void tablet4x3_fitsWidth() { assertArrayEquals(new int[] {1024, 576}, Stage16x9.fit(1024, 768)); }
}
