package com.stbemu.host;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;

/** Shared native chrome (colors, surfaces, buttons) for the native screens. */
final class PortalStyle {
    static int BACKGROUND = 0xFF0B1220;
    static int SURFACE = 0xFF152238;
    static int BORDER = 0xFF2B3C55;
    static int ACCENT = 0xFF69DFCA;
    static int MUTED = 0xFFA6B6CB;
    static final int GOLD = 0xFFFFC107; // Pro diamond once owned, both themes

    static int TEXT = Color.WHITE;
    static boolean dark = true;
    private static final java.util.WeakHashMap<android.view.View, Boolean> windows = new java.util.WeakHashMap<>();

    static void trackWindow(android.view.View view) { windows.put(view, true); }

    static int dialogTheme() {
        return dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    static String theme(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", "dark");
    }

    static boolean update(Context context, android.view.View root) {
        String choice = theme(context);
        boolean next = "dark".equals(choice) || (!"light".equals(choice) &&
                (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        if (next == dark) return false;
        int[] before = {BACKGROUND, SURFACE, BORDER, ACCENT, MUTED, TEXT,
                dark ? 0xFF1D3A4A : 0xFFD7EEE8, dark ? 0xE00B1220 : 0xE0F5F7FA};
        dark = next;
        BACKGROUND = dark ? 0xFF0B1220 : 0xFFF5F7FA;
        SURFACE = dark ? 0xFF152238 : 0xFFFFFFFF;
        BORDER = dark ? 0xFF2B3C55 : 0xFFCBD5E1;
        ACCENT = dark ? 0xFF69DFCA : 0xFF007F6D;
        MUTED = dark ? 0xFFA6B6CB : 0xFF526176;
        TEXT = dark ? Color.WHITE : 0xFF172334;
        int[] after = {BACKGROUND, SURFACE, BORDER, ACCENT, MUTED, TEXT,
                dark ? 0xFF1D3A4A : 0xFFD7EEE8, dark ? 0xE00B1220 : 0xE0F5F7FA};
        if (root != null) recolor(root, before, after);
        for (android.view.View window : windows.keySet()) recolor(window, before, after);
        return true;
    }

    private static int map(int color, int[] before, int[] after) {
        for (int i = 0; i < before.length; i++) if (color == before[i]) return after[i];
        return color;
    }

    private static android.content.res.ColorStateList tint(android.content.res.ColorStateList value, int[] before, int[] after) {
        return value == null ? null : android.content.res.ColorStateList.valueOf(map(value.getDefaultColor(), before, after));
    }

    private static void recolor(android.view.View view, int[] before, int[] after) {
        Drawable background = view.getBackground();
        if (background instanceof StateListDrawable) {
            boolean selected = view instanceof android.widget.TextView &&
                    ((android.widget.TextView) view).getCurrentTextColor() == before[0];
            view.setBackground(interactive(view.getContext(), selected));
        } else if (background instanceof android.graphics.drawable.ColorDrawable) {
            ((android.graphics.drawable.ColorDrawable) background).setColor(map(((android.graphics.drawable.ColorDrawable) background).getColor(), before, after));
        } else if (background instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) background;
            if (shape.getColor() != null) shape.setColor(map(shape.getColor().getDefaultColor(), before, after));
            shape.setStroke(dp(view.getContext(), 1), BORDER);
        }
        if (view instanceof android.widget.TextView) {
            android.widget.TextView text = (android.widget.TextView) view;
            text.setTextColor(map(text.getCurrentTextColor(), before, after));
            text.setHintTextColor(map(text.getCurrentHintTextColor(), before, after));
            text.setCompoundDrawableTintList(tint(text.getCompoundDrawableTintList(), before, after));
        }
        if (view instanceof android.widget.ImageView) {
            android.widget.ImageView image = (android.widget.ImageView) view;
            image.setImageTintList(tint(image.getImageTintList(), before, after));
        }
        if (view instanceof android.widget.CompoundButton)
            ((android.widget.CompoundButton) view).setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        if (view instanceof android.widget.EditText && view.getBackgroundTintList() != null) // plain underline fields only
            view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        if (view instanceof android.widget.ProgressBar) {
            android.widget.ProgressBar progress = (android.widget.ProgressBar) view;
            progress.setIndeterminateTintList(tint(progress.getIndeterminateTintList(), before, after));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) recolor(group.getChildAt(i), before, after);
            if (view instanceof androidx.recyclerview.widget.RecyclerView) {
                androidx.recyclerview.widget.RecyclerView list = (androidx.recyclerview.widget.RecyclerView) view;
                list.setItemViewCacheSize(0);
                list.getRecycledViewPool().clear();
            }
        }
    }

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
        int highlight = dark ? (selected ? 0xFFA3F5E5 : 0xFF254D58) : (selected ? 0xFF006656 : 0xFFD7EEE8);
        states.addState(new int[]{android.R.attr.state_pressed}, surface(context, highlight, TEXT));
        states.addState(new int[]{android.R.attr.state_focused}, surface(context, highlight, TEXT));
        states.addState(new int[]{}, surface(context, selected ? ACCENT : SURFACE, selected ? ACCENT : BORDER));
        return states;
    }

    static void button(Button button) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(TEXT);
        button.setBackgroundTintList(null);
        button.setBackground(interactive(context));
        button.setMinHeight(dp(context, 40));
        button.setMinimumHeight(dp(context, 40));
        button.setMinWidth(dp(context, 40));
        button.setMinimumWidth(dp(context, 40));
        button.setPadding(dp(context, 10), 0, dp(context, 10), 0);
    }

    private PortalStyle() { }
}
