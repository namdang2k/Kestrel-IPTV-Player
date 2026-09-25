package com.stbemu.host;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;

/** Shared native chrome for the Stalker WebView. */
final class PortalStyle {
    static final int BACKGROUND = 0xFF0B1220;
    static final int SURFACE = 0xFF152238;
    static final int BORDER = 0xFF2B3C55;
    static final int ACCENT = 0xFF69DFCA;
    static final int MUTED = 0xFFA6B6CB;

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Drawable surface(Context context, int color, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 12));
        shape.setStroke(dp(context, 1), border);
        return shape;
    }

    static Drawable interactive(Context context) {
        return interactive(context, false);
    }

    static Drawable interactive(Context context, boolean selected) {
        StateListDrawable states = new StateListDrawable();
        int highlight = selected ? 0xFFA3F5E5 : 0xFF254D58;
        states.addState(new int[]{android.R.attr.state_pressed}, surface(context, highlight, Color.WHITE));
        states.addState(new int[]{android.R.attr.state_focused}, surface(context, highlight, Color.WHITE));
        states.addState(new int[]{}, surface(context, selected ? ACCENT : SURFACE, selected ? ACCENT : BORDER));
        return states;
    }

    static void button(Button button) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(null);
        button.setBackground(interactive(context));
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(dp(context, 48));
        button.setMinimumWidth(dp(context, 48));
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
    }

    private PortalStyle() { }
}
