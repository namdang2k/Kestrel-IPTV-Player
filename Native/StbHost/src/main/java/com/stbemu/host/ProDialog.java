package com.stbemu.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

/** "Upgrade to Pro" sheet. C# owns the store: prices and the Pro flag arrive as JSON, taps go back as actions. */
final class ProDialog {
    interface Actions {
        void buy(String plan); // "monthly" | "lifetime"
        void restore();
    }

    static final String TERMS_URL = "https://support.google.com/googleplay/answer/2479637";
    static final String PRIVACY_URL = "https://namdang2k.github.io/privacy/";
    private static final String[] FEATURES = {"No ads", "No popup suggestions", "Faster, cleaner viewing experience", "Unlock Pro features"};

    private final Activity activity;
    private final Actions actions;
    private AlertDialog dialog, status;
    private android.widget.ProgressBar statusSpinner;
    private TextView statusTitle, statusMessage;
    private Button statusOk;
    private View plans, activeNote;
    private LinearLayout monthlyCard, lifetimeCard;
    private TextView monthlyPrice, lifetimePrice, selectedLabel, trialNote;
    private Button cta;
    private boolean lifetime;

    ProDialog(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
    }

    /** {pro, monthlyPrice, lifetimePrice}; opens the sheet, or refreshes it when already open. */
    void show(String json) {
        if (dialog == null) build();
        update(json);
    }

    /** Same payload as {@link #show}; no-op unless the sheet is open. */
    void update(String json) {
        if (dialog == null) return;
        JSONObject data;
        try {
            data = new JSONObject(json);
        } catch (JSONException e) {
            Log.w("StbHome", "bad pro payload: " + e.getMessage());
            return;
        }
        boolean pro = data.optBoolean("pro");
        monthlyPrice.setText(price(data.optString("monthlyPrice", ""), "$0.99") + " / month");
        lifetimePrice.setText(price(data.optString("lifetimePrice", ""), "$4.99"));
        plans.setVisibility(pro ? View.GONE : View.VISIBLE);
        activeNote.setVisibility(pro ? View.VISIBLE : View.GONE);
        select(lifetime);
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        hideStatus();
    }

    /** {busy, title, message}: spinner while busy, otherwise the result with an OK button. Replaces an open one. */
    void showStatus(String json) {
        JSONObject data;
        try {
            data = new JSONObject(json);
        } catch (JSONException e) {
            Log.w("StbHome", "bad status payload: " + e.getMessage());
            return;
        }
        if (status == null) buildStatus();
        boolean busy = data.optBoolean("busy");
        statusSpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusOk.setVisibility(busy ? View.GONE : View.VISIBLE);
        statusTitle.setText(data.optString("title", ""));
        statusMessage.setText(data.optString("message", ""));
        statusMessage.setVisibility(statusMessage.length() == 0 ? View.GONE : View.VISIBLE);
        status.setCancelable(!busy); // Back cannot skip a purchase in flight; the store sheet has its own cancel
    }

    void hideStatus() {
        if (status != null) status.dismiss();
    }

    private void buildStatus() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(24), dp(28), dp(24), dp(20));
        statusSpinner = new android.widget.ProgressBar(activity);
        statusSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        spinnerParams.bottomMargin = dp(16);
        body.addView(statusSpinner, spinnerParams);
        statusTitle = text("", 20, PortalStyle.TEXT, true);
        statusTitle.setGravity(Gravity.CENTER);
        body.addView(statusTitle, match());
        statusMessage = text("", 15, PortalStyle.MUTED, false);
        statusMessage.setGravity(Gravity.CENTER);
        body.addView(statusMessage, match(dp(8), 0));
        statusOk = new Button(activity);
        statusOk.setText("OK");
        PortalStyle.button(statusOk);
        statusOk.setBackground(PortalStyle.interactive(activity, true));
        statusOk.setTextColor(PortalStyle.BACKGROUND);
        statusOk.setOnClickListener(v -> hideStatus());
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        okParams.topMargin = dp(20);
        body.addView(statusOk, okParams);
        status = new AlertDialog.Builder(activity, PortalStyle.dialogTheme()).setView(body).create();
        status.setCanceledOnTouchOutside(false);
        status.setOnDismissListener(d -> { if (status == d) status = null; });
        status.show();
        if (status.getWindow() != null) {
            PortalStyle.trackWindow(status.getWindow().getDecorView());
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(PortalStyle.SURFACE);
            panel.setCornerRadius(dp(20));
            panel.setStroke(dp(1), PortalStyle.BORDER);
            status.getWindow().setBackgroundDrawable(new InsetDrawable(panel, dp(24)));
            status.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** Store price, or the list price until the catalog is fetched. */
    private static String price(String value, String fallback) { return value.isEmpty() ? fallback : value; }

    private void build() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(12));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button close = new Button(activity);
        PortalStyle.button(close);
        close.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.stb_ic_close, 0, 0, 0);
        close.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(PortalStyle.TEXT));
        close.setPadding(dp(12), 0, dp(12), 0);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1));
        TextView restore = text("Restore Purchase", 16, PortalStyle.TEXT, true);
        restore.setPadding(dp(8), dp(8), dp(8), dp(8));
        restore.setOnClickListener(v -> actions.restore());
        header.addView(restore);
        body.addView(header, match());

        // Portrait: fixed heights fit a 9:16 phone without scrolling; these spacers share the slack on taller screens.
        // Landscape: they collapse to 0 and the ScrollView takes over.
        body.addView(spacer());
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(activity.getApplicationInfo().loadIcon(activity.getPackageManager()));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(icon, iconParams);
        TextView title = text("Upgrade to Pro", 26, PortalStyle.TEXT, true);
        title.setGravity(Gravity.CENTER);
        body.addView(title, match(dp(10), 0));
        TextView subtitle = text("Enjoy a smoother, uninterrupted IPTV experience.", 15, PortalStyle.MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        body.addView(subtitle, match(dp(4), 0));
        body.addView(spacer());

        for (String feature : FEATURES) {
            TextView row = text(feature, 16, PortalStyle.TEXT, false);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.stb_ic_check_circle, 0, 0, 0);
            row.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
            row.setCompoundDrawablePadding(dp(12));
            row.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(row, match(0, dp(10)));
        }
        body.addView(spacer());

        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        plans = section;
        TextView choose = text("CHOOSE YOUR PLAN", 13, PortalStyle.MUTED, true);
        choose.setLetterSpacing(0.1f);
        section.addView(choose, match(0, dp(8)));
        LinearLayout row = new LinearLayout(activity);
        monthlyPrice = text("", 22, PortalStyle.ACCENT, true);
        monthlyCard = card("Monthly", monthlyPrice, "Billed monthly", false);
        lifetimePrice = text("", 22, PortalStyle.ACCENT, true);
        lifetimeCard = card("No Ads Forever", lifetimePrice, "One-time purchase", true);
        FrameLayout lifetimeBox = new FrameLayout(activity);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardParams.topMargin = dp(12);
        lifetimeBox.addView(lifetimeCard, cardParams);
        TextView badge = text("BUY ONCE", 12, PortalStyle.BACKGROUND, true);
        badge.setLetterSpacing(0.05f);
        badge.setBackground(PortalStyle.surface(activity, PortalStyle.ACCENT, PortalStyle.ACCENT));
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        lifetimeBox.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        LinearLayout.LayoutParams monthlyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        monthlyParams.topMargin = dp(12);
        monthlyParams.rightMargin = dp(6);
        row.addView(monthlyCard, monthlyParams);
        LinearLayout.LayoutParams lifetimeParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lifetimeParams.leftMargin = dp(6);
        row.addView(lifetimeBox, lifetimeParams);
        section.addView(row, match());
        selectedLabel = text("", 15, PortalStyle.MUTED, false);
        selectedLabel.setGravity(Gravity.CENTER);
        section.addView(selectedLabel, match(dp(10), 0));
        trialNote = text("", 15, PortalStyle.TEXT, false);
        trialNote.setGravity(Gravity.CENTER);
        section.addView(trialNote, match(dp(6), dp(12)));
        cta = new Button(activity);
        PortalStyle.button(cta);
        cta.setTextSize(18);
        cta.setTypeface(null, Typeface.BOLD);
        cta.setBackground(PortalStyle.interactive(activity, true));
        cta.setTextColor(PortalStyle.BACKGROUND);
        cta.setOnClickListener(v -> actions.buy(lifetime ? "lifetime" : "monthly"));
        section.addView(cta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        body.addView(section, match());

        TextView active = text("Pro is active on this account. Thank you!", 16, PortalStyle.ACCENT, true);
        active.setGravity(Gravity.CENTER);
        activeNote = active;
        body.addView(active, match(dp(16), dp(8)));

        LinearLayout links = new LinearLayout(activity);
        links.setGravity(Gravity.CENTER);
        links.addView(link("Terms of Service", TERMS_URL));
        links.addView(text("  •  ", 14, PortalStyle.MUTED, false));
        links.addView(link("Privacy Policy", PRIVACY_URL));
        body.addView(links, match(dp(8), 0));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        scroll.setFillViewport(true);
        dialog = new AlertDialog.Builder(activity, PortalStyle.dialogTheme()).setView(scroll).create();
        dialog.setOnDismissListener(d -> { if (dialog == d) dialog = null; });
        dialog.show();
        if (dialog.getWindow() != null) { // full screen
            PortalStyle.trackWindow(dialog.getWindow().getDecorView());
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PortalStyle.BACKGROUND));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private LinearLayout card(String name, TextView price, String note, boolean isLifetime) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(16), dp(14), dp(16));
        card.addView(text(name, 18, PortalStyle.TEXT, true));
        card.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1)); // price + note sit at the bottom of both cards
        card.addView(price, match(dp(12), 0));
        card.addView(text(note, 14, PortalStyle.MUTED, false), match(dp(2), 0));
        card.setOnClickListener(v -> select(isLifetime));
        return card;
    }

    private void select(boolean isLifetime) {
        lifetime = isLifetime;
        monthlyCard.setBackground(cardBackground(!lifetime));
        lifetimeCard.setBackground(cardBackground(lifetime));
        selectedLabel.setText(lifetime ? "No Ads Forever selected" : "Monthly plan selected");
        trialNote.setText(lifetime ? "Pay once, keep it forever." : "Start with a free trial. Cancel anytime.");
        cta.setText(lifetime ? "Buy Now" : "Start Free Trial");
    }

    private GradientDrawable cardBackground(boolean selected) {
        GradientDrawable shape = (GradientDrawable) PortalStyle.surface(activity,
                selected ? (PortalStyle.dark ? 0xFF1D3A4A : 0xFFD7EEE8) : PortalStyle.SURFACE,
                selected ? PortalStyle.ACCENT : PortalStyle.BORDER);
        shape.setCornerRadius(dp(16));
        if (selected) shape.setStroke(dp(2), PortalStyle.ACCENT);
        return shape;
    }

    private TextView link(String label, String url) {
        TextView view = text(label, 14, PortalStyle.MUTED, false);
        view.setPaintFlags(view.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        view.setPadding(dp(4), dp(8), dp(4), dp(8));
        view.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(activity, "No browser found", Toast.LENGTH_SHORT).show();
            }
        });
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private View spacer() {
        View view = new View(activity);
        view.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1));
        return view;
    }

    private LinearLayout.LayoutParams match() { return match(0, 0); }

    private LinearLayout.LayoutParams match(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        params.bottomMargin = bottom;
        return params;
    }

    private int dp(int value) { return PortalStyle.dp(activity, value); }
}
