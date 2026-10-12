package com.stbemu.host;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Native home over Unity: Profile / Setting tabs, the profile list and the delete confirmation.
 * C# owns the data and re-renders through {@link #show(String)}; the tab is native-only state.
 */
final class HomeScreen extends LinearLayout {
    interface Actions {
        void add();
        void edit(String profileId);
        void delete(String profileId); // only after the user confirmed
        void connect(String profileId);
        void pro(); // the Get Pro / Pro button; C# opens the sheet
    }

    private final Actions actions;
    private final Button profileTab, settingTab, proButton;
    private final View profilePanel, settingPanel;
    private final TextView empty, appInfo, appTitle;
    private final Adapter adapter = new Adapter();
    private final List<JSONObject> items = new ArrayList<>();
    private AlertDialog confirm;

    HomeScreen(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setOrientation(VERTICAL);
        setBackgroundColor(PortalStyle.BACKGROUND);
        setClickable(true); // opaque: nothing underneath receives touches
        setFocusableInTouchMode(true); // takes Back while shown
        setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        appTitle = text("", 22, PortalStyle.TEXT, true);
        titleRow.addView(appTitle, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        proButton = button("", v -> actions.pro(), true);
        proButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.stb_ic_diamond, 0, 0, 0);
        proButton.setCompoundDrawablePadding(dp(6));
        proButton.setPadding(dp(14), 0, dp(16), 0);
        titleRow.addView(proButton, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        addView(titleRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setPro(false);

        // The tab bar is its own view so it can move below the content without touching the panels.
        LinearLayout tabs = new LinearLayout(context);
        profileTab = tab("Profile", false);
        settingTab = tab("Setting", true);
        tabs.addView(profileTab, new LayoutParams(0, dp(48), 1));
        LayoutParams gap = new LayoutParams(0, dp(48), 1);
        gap.leftMargin = dp(12);
        tabs.addView(settingTab, gap);
        LayoutParams tabParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabParams.topMargin = dp(12);
        addView(tabs, tabParams);

        empty = text("No profiles yet. Tap Add profile to create one.", 15, PortalStyle.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(32), 0, dp(32));
        profilePanel = buildProfilePanel(context);
        appInfo = text("", 15, PortalStyle.MUTED, false);
        settingPanel = buildSettingPanel(context);
        addView(profilePanel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        addView(settingPanel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        selectTab(false);
        setVisibility(GONE);
    }

    /** {app:{name,version}, profiles:[{id,name,host,username}], error?}; always lands on the Profile tab. */
    void show(String json) {
        JSONObject data;
        try {
            data = new JSONObject(json);
        } catch (JSONException e) {
            Log.w("StbHome", "bad home payload: " + e.getMessage());
            return;
        }
        if (confirm != null) confirm.dismiss();
        JSONObject app = data.optJSONObject("app");
        String appName = app == null ? "" : app.optString("name", "");
        if (appName.trim().isEmpty())
            appName = getContext().getApplicationInfo().loadLabel(getContext().getPackageManager()).toString();
        appTitle.setText(appName);
        appInfo.setText(appName + "\nVersion " + (app == null ? "" : app.optString("version", "")));
        items.clear();
        JSONArray profiles = data.optJSONArray("profiles");
        if (profiles != null)
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.optJSONObject(i);
                if (p != null) items.add(p);
            }
        adapter.notifyDataSetChanged(); // ponytail: full rebind per render; DiffUtil if lists ever get long
        empty.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        String error = data.optString("error", "");
        if (!error.isEmpty()) Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
        selectTab(false);
        setVisibility(VISIBLE);
        requestFocus();
    }

    void hide() {
        if (confirm != null) confirm.dismiss();
        setVisibility(GONE);
    }

    void setBannerInset(int heightPixels) {
        setPadding(dp(16), dp(16), dp(16), dp(16) + Math.max(0, heightPixels));
    }

    /** "Get Pro" (accent) while free, "Pro" (plain) once owned. */
    void setPro(boolean pro) {
        proButton.setText(pro ? "Pro" : "Get Pro");
        proButton.setBackground(PortalStyle.interactive(getContext(), !pro));
        int color = pro ? PortalStyle.TEXT : PortalStyle.BACKGROUND;
        proButton.setTextColor(color);
        proButton.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
    }

    /** Back on Setting returns to Profile; on Profile it is left to Unity (AppFlow's exit policy). */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK || settingPanel.getVisibility() != VISIBLE) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) selectTab(false);
        return true;
    }

    private void selectTab(boolean setting) {
        profileTab.setBackground(PortalStyle.interactive(getContext(), !setting));
        profileTab.setTextColor(setting ? PortalStyle.TEXT : PortalStyle.BACKGROUND);
        settingTab.setBackground(PortalStyle.interactive(getContext(), setting));
        settingTab.setTextColor(setting ? PortalStyle.BACKGROUND : PortalStyle.TEXT);
        profilePanel.setVisibility(setting ? GONE : VISIBLE);
        settingPanel.setVisibility(setting ? VISIBLE : GONE);
    }

    private View buildProfilePanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Profiles", 17, PortalStyle.TEXT, true), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(button("+ Add profile", v -> actions.add(), true), new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        LayoutParams headerParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(16);
        headerParams.bottomMargin = dp(8);
        panel.addView(header, headerParams);
        panel.addView(empty, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(16));
        panel.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return panel;
    }

    private View buildSettingPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(VERTICAL);
        panel.setBackground(PortalStyle.surface(context, PortalStyle.SURFACE, PortalStyle.BORDER));
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.addView(text("Theme", 17, PortalStyle.TEXT, true));
        android.widget.RadioGroup themes = new android.widget.RadioGroup(context);
        String[] values = {"system", "light", "dark"};
        String[] labels = {"System default", "Light", "Dark"};
        for (int i = 0; i < values.length; i++) {
            android.widget.RadioButton option = new android.widget.RadioButton(context);
            option.setId(View.generateViewId());
            option.setText(labels[i]);
            option.setTextColor(PortalStyle.TEXT);
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
            option.setMinHeight(dp(48));
            option.setTag(values[i]);
            themes.addView(option);
            if (values[i].equals(PortalStyle.theme(context))) option.setChecked(true);
        }
        themes.setOnCheckedChangeListener((group, checked) -> {
            View option = group.findViewById(checked);
            if (option == null) return;
            context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                    .putString("theme", (String) option.getTag()).apply();
            PortalStyle.update(context, ((android.app.Activity) context).findViewById(android.R.id.content));
            for (int i = 0; i < group.getChildCount(); i++)
                ((android.widget.RadioButton) group.getChildAt(i)).setButtonTintList(
                        android.content.res.ColorStateList.valueOf(PortalStyle.ACCENT));
        });
        panel.addView(themes);
        panel.addView(text("About", 17, PortalStyle.TEXT, true), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams infoParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoParams.topMargin = dp(8);
        panel.addView(appInfo, infoParams);
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(VERTICAL);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        wrapper.addView(panel, params);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(wrapper);
        return scroll;
    }

    private void confirmDelete(JSONObject profile) {
        if (confirm != null) return;
        String id = profile.optString("id");
        confirm = new AlertDialog.Builder(getContext(), PortalStyle.dialogTheme())
                .setTitle("Delete profile")
                .setMessage("Delete \"" + profile.optString("name") + "\"? This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> actions.delete(id))
                .create();
        confirm.setOnDismissListener(d -> { if (confirm == d) confirm = null; }); // delivered asynchronously
        confirm.show();
        if (confirm.getWindow() != null) PortalStyle.trackWindow(confirm.getWindow().getDecorView());
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(VERTICAL);
            row.setBackground(PortalStyle.interactive(context));
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            row.setLayoutParams(params);
            row.addView(text("", 17, PortalStyle.TEXT, true), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(text("", 14, PortalStyle.MUTED, false), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout buttons = new LinearLayout(context);
            buttons.addView(button("Connect", null, true), new LayoutParams(0, dp(48), 1));
            LayoutParams gap = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            gap.leftMargin = dp(8);
            buttons.addView(button("Edit", null, false), gap);
            buttons.addView(button("Delete", null, false), gap);
            LayoutParams buttonsParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonsParams.topMargin = dp(10);
            row.addView(buttons, buttonsParams);
            return new RecyclerView.ViewHolder(row) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            JSONObject p = items.get(position);
            String id = p.optString("id");
            LinearLayout row = (LinearLayout) holder.itemView;
            ((TextView) row.getChildAt(0)).setText(p.optString("name"));
            ((TextView) row.getChildAt(1)).setText(p.optString("host") + " · " + p.optString("username"));
            LinearLayout buttons = (LinearLayout) row.getChildAt(2);
            row.setOnClickListener(v -> actions.connect(id));
            buttons.getChildAt(0).setOnClickListener(v -> actions.connect(id));
            buttons.getChildAt(1).setOnClickListener(v -> actions.edit(id));
            buttons.getChildAt(2).setOnClickListener(v -> confirmDelete(p));
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private Button tab(String label, boolean setting) {
        Button tab = button(label, v -> selectTab(setting), false);
        return tab;
    }

    private Button button(String label, View.OnClickListener onClick, boolean accent) {
        Button button = new Button(getContext());
        button.setText(label);
        PortalStyle.button(button);
        button.setBackground(PortalStyle.interactive(getContext(), accent));
        if (accent) button.setTextColor(PortalStyle.BACKGROUND);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setOnClickListener(onClick);
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) text.setTypeface(null, Typeface.BOLD);
        return text;
    }

    private int dp(int value) { return PortalStyle.dp(getContext(), value); }
}
