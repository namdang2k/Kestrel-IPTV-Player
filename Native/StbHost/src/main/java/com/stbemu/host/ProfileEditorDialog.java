package com.stbemu.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Add/Edit profile popup. Holds the draft text only; C# validates and saves, then either re-renders the home
 * (which dismisses this) or sends errors back. Save never auto-dismisses.
 */
final class ProfileEditorDialog {
    interface Actions {
        void save(JSONObject form);
        void cancel();
    }

    private static final String[] KEYS = {"name", "serverUrl", "username", "password", "userAgent", "playerUserAgent"};
    private static final String[] LABELS = {"Name", "Server URL (http://host:port)", "Username", "Password",
            "User-Agent (empty = MAG250)", "Player User-Agent (empty = Lavf53.32.100)"};

    private final Activity activity;
    private final Actions actions;
    private AlertDialog dialog;
    boolean isShowing() { return dialog != null && dialog.isShowing(); }
    private final EditText[] fields = new EditText[KEYS.length];
    private TextView errors;
    private ScrollView scroll;
    private Button save, cancel;
    private boolean saving;

    ProfileEditorDialog(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
    }

    /** {title, name, serverUrl, username, password, userAgent, playerUserAgent} */
    void show(String json) {
        JSONObject form;
        try {
            form = new JSONObject(json);
        } catch (JSONException e) {
            Log.w("StbHome", "bad editor payload: " + e.getMessage());
            return;
        }
        dismiss();
        saving = false;
        Context context = activity;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        errors = new TextView(context);
        errors.setTextColor(0xFFFF6B6B);
        errors.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        errors.setVisibility(View.GONE);
        body.addView(errors, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < KEYS.length; i++) {
            fields[i] = field(form.optString(KEYS[i], ""), KEYS[i]);
            if (KEYS[i].equals("userAgent")) body.addView(label("Advanced"));
            body.addView(label(LABELS[i]));
            if (KEYS[i].equals("password")) {
                EditText password = fields[i];
                body.addView(withButton(password, "Show", v -> {
                    boolean hidden = (password.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                    password.setInputType(InputType.TYPE_CLASS_TEXT | (hidden
                            ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                    ((Button) v).setText(hidden ? "Hide" : "Show");
                    password.setSelection(password.length());
                }));
            } else {
                body.addView(fields[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        scroll = new ScrollView(context);
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout footer = new LinearLayout(context);
        footer.setPadding(dp(20), dp(8), dp(20), dp(12));
        cancel = button("Cancel");
        cancel.setOnClickListener(v -> { if (dialog != null) dialog.cancel(); });
        save = button("Save");
        save.setBackground(PortalStyle.interactive(context, true));
        save.setTextColor(PortalStyle.BACKGROUND);
        save.setOnClickListener(v -> submit());
        footer.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, dp(48), 1);
        gap.leftMargin = dp(12);
        footer.addView(save, gap);
        content.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog = new AlertDialog.Builder(activity, PortalStyle.dialogTheme())
                .setTitle(form.optString("title", "Profile")).setView(content).create();
        dialog.setCanceledOnTouchOutside(false); // a stray tap never loses the draft
        dialog.setOnCancelListener(d -> actions.cancel()); // Cancel button and Back; dismiss() from C# is silent
        dialog.setOnKeyListener((d, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK && saving); // Back waits for the save result
        dialog.setOnDismissListener(d -> {
            hideKeyboard();
            if (dialog == d) dialog = null; // delivered asynchronously: a replacement may already be open
        });
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); // keyboard shrinks the dialog; footer stays reachable
        dialog.show();
        if (dialog.getWindow() != null) {
            PortalStyle.trackWindow(dialog.getWindow().getDecorView());
            android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
            panel.setColor(PortalStyle.SURFACE);
            panel.setCornerRadius(dp(20));
            panel.setStroke(dp(1), PortalStyle.BORDER);
            panel.setPadding(dp(1), dp(1), dp(1), dp(1));
            dialog.getWindow().setBackgroundDrawable(panel);
        }
    }

    /** ["message", ...]: shown at the top of the form; Save/Cancel re-enabled. */
    void setErrors(String json) {
        if (dialog == null) return;
        StringBuilder text = new StringBuilder();
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                if (i > 0) text.append('\n');
                text.append(list.optString(i));
            }
        } catch (JSONException e) {
            text.append(json);
        }
        showErrors(text.toString());
        setSaving(false);
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    private void submit() {
        if (saving) return;
        JSONObject form = new JSONObject();
        try {
            for (int i = 0; i < KEYS.length; i++) form.put(KEYS[i], formValue(KEYS[i], fields[i].getText().toString()));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        errors.setVisibility(View.GONE);
        setSaving(true);
        actions.save(form);
    }

    private void setSaving(boolean value) {
        saving = value;
        save.setEnabled(!value);
        save.setAlpha(value ? 0.4f : 1f);
        cancel.setEnabled(!value);
        cancel.setAlpha(value ? 0.4f : 1f);
    }

    private void showErrors(String text) {
        errors.setText(text);
        errors.setVisibility(View.VISIBLE);
        scroll.smoothScrollTo(0, 0);
    }

    /** Passwords are sent exactly as typed; every other field is trimmed. */
    static String formValue(String key, String raw) {
        return "password".equals(key) ? raw : raw.trim();
    }

    private View withButton(EditText field, String label, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button button = button(label);
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.leftMargin = dp(8);
        row.addView(button, params);
        return row;
    }

    private EditText field(String value, String key) {
        EditText field = new EditText(activity);
        field.setText(value);
        field.setSingleLine();
        field.setTextColor(PortalStyle.TEXT);
        field.setHintTextColor(PortalStyle.MUTED);
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (key.equals("serverUrl")) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        else if (key.equals("name")) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        else if (key.equals("password")) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); // no autocorrect
        return field;
    }

    private TextView label(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(PortalStyle.MUTED);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setPadding(0, dp(10), 0, 0);
        return label;
    }

    private Button button(String label) {
        Button button = new Button(activity);
        button.setText(label);
        PortalStyle.button(button);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        return button;
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = activity.getCurrentFocus();
        if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private int dp(int value) { return PortalStyle.dp(activity, value); }
}
