package com.stbemu.host;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Invisible host for the system document picker; hands the chosen M3U file's content:// URI to the editor dialog. */
public final class M3uPickerActivity extends Activity {
    /** Set by ProfileEditorDialog right before launch; called on the UI thread, once. */
    static java.util.function.Consumer<String> onPicked;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return; // recreated while the picker is open: its result still arrives
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, 1);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        java.util.function.Consumer<String> callback = onPicked;
        onPicked = null;
        if (uri != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); // reconnects read it again
            } catch (SecurityException ignored) { } // provider offers no persistable grant: works until the app restarts
            if (callback != null) callback.accept(uri.toString());
        }
        finish();
    }
}
