package com.stbemu.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Same vectors as Assets/App/Tests/EditMode/ProfileTests.cs: the popup must fill fields exactly like C# would. */
public class ProfileEditorDialogTest {
    private static final String DEVICE_ID = "667094E7E8FF0347F6EC27A8E8115BE76E2AA9CD5C8F13C6FA00BCEDFAC02B41";

    @Test public void deviceIdFromMac_matchesCSharp() {
        assertEquals(DEVICE_ID, ProfileEditorDialog.deviceIdFromMac("00:1A:79:12:34:56"));
        assertEquals(DEVICE_ID, ProfileEditorDialog.deviceIdFromMac("001a79123456"));
    }

    @Test public void normalizeMac_matchesCSharp() {
        assertEquals("00:1A:79:12:34:56", ProfileEditorDialog.normalizeMac("00-1a-79-12-34-56"));
        assertEquals("00:1A:79:AB:CD:EF", ProfileEditorDialog.normalizeMac(" 00:1A:79:AB:CD:EF "));
        assertNull(ProfileEditorDialog.normalizeMac("00:1A:79:12:34"));
        assertNull(ProfileEditorDialog.normalizeMac("00:1A:79:12:34:5G"));
        assertNull(ProfileEditorDialog.normalizeMac(""));
    }

    @Test public void generateMac_isCanonicalWithPrefix() {
        String mac = ProfileEditorDialog.generateMac();
        assertTrue(mac, mac.matches("^00:1A:79(:[0-9A-F]{2}){3}$"));
        assertEquals(mac, ProfileEditorDialog.normalizeMac(mac));
    }
}
