package com.stbemu.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TvScreenLogoTest {
    @Test public void absoluteLogoIgnoresBase() {
        assertEquals("http://l/a.png", TvScreen.logoUrl("", "http://l/a.png"));
        assertEquals("https://l/a.png", TvScreen.logoUrl("http://base/", "https://l/a.png"));
    }
    @Test public void relativeLogoNeedsBase() {
        assertEquals("http://base/a.png", TvScreen.logoUrl("http://base/", "a.png"));
        assertNull(TvScreen.logoUrl("", "a.png"));
    }
    @Test public void emptyOrMissingLogo() {
        assertNull(TvScreen.logoUrl("http://base/", ""));
        assertNull(TvScreen.logoUrl("http://base/", null));
    }
}
