package com.stbemu.host;

import static org.junit.Assert.assertEquals;

import android.text.InputType;

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

    @Test public void passwordToggle_flipsBothWays() {
        int hidden = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        int visible = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        assertEquals(visible, ProfileEditorDialog.toggledPasswordType(hidden));
        assertEquals(hidden, ProfileEditorDialog.toggledPasswordType(visible));
    }
}
