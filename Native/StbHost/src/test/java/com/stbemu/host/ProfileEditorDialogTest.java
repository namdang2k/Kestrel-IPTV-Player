package com.stbemu.host;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Same rule as Assets/App/UI/ProfileForm.cs: every field is trimmed except the password. */
public class ProfileEditorDialogTest {
    @Test public void formValue_trimsFields() {
        assertEquals("http://h:8080", ProfileEditorDialog.formValue("serverUrl", "  http://h:8080 "));
        assertEquals("bob", ProfileEditorDialog.formValue("username", " bob "));
    }

    @Test public void formValue_keepsPasswordWhitespace() {
        assertEquals(" p w ", ProfileEditorDialog.formValue("password", " p w "));
    }
}
