package com.stbemu.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Native channel list over the portal: search box, tabs (All, ★, genres), smooth RecyclerView. */
final class ChannelPanel extends LinearLayout {
    interface OnPick { void pick(String channelId); }

    private final OnPick onPick;
    private final Runnable onClose;
    private final EditText search;
    private final LinearLayout tabs;
    private final HorizontalScrollView tabScroll;
    private final List<Button> genreButtons = new ArrayList<>(); // Portal order, never sorted in place.
    private String rankedQuery;
    private final Adapter adapter = new Adapter();
    private final TextView empty;
    private final TextView count;
    private ChannelFilter filter;
    private String tab = ChannelFilter.TAB_ALL;
    private SharedPreferences favorites;

    void setProfile(String key) {
        favorites = getContext().getSharedPreferences("favorites-" + key, Context.MODE_PRIVATE);
        filter = null;
        tab = ChannelFilter.TAB_ALL;
        search.setText("");
        adapter.set(new ArrayList<>());
        tabs.removeAllViews();
        genreButtons.clear();
        rankedQuery = null;
    }

    ChannelPanel(Context context, OnPick onPick, Runnable onClose) {
        super(context);
        this.onPick = onPick;
        this.onClose = onClose;
        setOrientation(VERTICAL);
        setBackgroundColor(PortalStyle.BACKGROUND);
        setPadding(PortalStyle.dp(context, 20), PortalStyle.dp(context, 16), PortalStyle.dp(context, 20), 0);
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(context);
        close.setText("Close ×");
        PortalStyle.button(close);
        close.setOnClickListener(v -> onClose.run());
        heading.addView(close);
        addView(heading);
        count = new TextView(context);
        count.setTextColor(PortalStyle.MUTED);
        count.setTextSize(13);
        count.setPadding(0, 0, 0, PortalStyle.dp(context, 12));
        addView(count);

        search = new EditText(context);
        search.setSingleLine(true);
        // No keyboard composition: Vietnamese Telex turned "espn" into "épn". Matching ignores diacritics, so plain
        // letters still find accented names ("thoi su" → "Thời sự").
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setHint("Search channels, categories or tags");
        search.setTextSize(16);
        search.setPadding(PortalStyle.dp(context, 16), PortalStyle.dp(context, 12), PortalStyle.dp(context, 16), PortalStyle.dp(context, 12));
        search.setBackground(PortalStyle.interactive(context));
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x88FFFFFF);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        addView(search, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabs = new LinearLayout(context);
        tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs.setPadding(0, PortalStyle.dp(context, 12), 0, PortalStyle.dp(context, 12));
        tabScroll.addView(tabs);
        addView(tabScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = new TextView(context);
        empty.setText("No channels found\nTry another name or pick All.");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(16, 32, 16, 32);
        empty.setTextColor(0xAAFFFFFF);
        empty.setVisibility(GONE);
        addView(empty);

        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, PortalStyle.dp(context, 16));
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onClose.run();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    void dismiss() {
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        clearFocus();
        setVisibility(GONE);
    }

    boolean hasData() {
        return filter != null;
    }

    void setData(JSONObject payload) throws JSONException {
        Map<String, String> genreNames = new HashMap<>();
        JSONArray genres = payload.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            JSONObject genre = genres.getJSONObject(i);
            genreNames.put(genre.getString("id"), genre.optString("title", ""));
        }
        List<ChannelFilter.Channel> channels = new ArrayList<>();
        JSONArray arr = payload.getJSONArray("channels");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String genreId = c.optString("genreId", "");
            StringBuilder terms = new StringBuilder(genreNames.getOrDefault(genreId, ""));
            JSONArray tags = c.optJSONArray("tags");
            for (int j = 0; tags != null && j < tags.length(); j++) terms.append(' ').append(tags.optString(j, ""));
            channels.add(new ChannelFilter.Channel(c.getString("id"), c.isNull("number") ? null : c.getInt("number"),
                    c.getString("name"), genreId, terms.toString()));
        }
        Set<String> favs = new HashSet<>();
        JSONArray favArr = payload.optJSONArray("favIds");
        for (int i = 0; favArr != null && i < favArr.length(); i++) favs.add(favArr.getString(i));
        // Local explicit choices override portal favorites, including removals, across data refreshes.
        if (favorites != null) {
            for (ChannelFilter.Channel channel : channels) {
                if (!favorites.contains(channel.id)) continue;
                if (favorites.getBoolean(channel.id, false)) favs.add(channel.id);
                else favs.remove(channel.id);
            }
        }
        filter = new ChannelFilter(channels, favs);

        tabs.removeAllViews();
        genreButtons.clear();
        rankedQuery = null;
        addTab("All", ChannelFilter.TAB_ALL);
        addTab("★ Favorites", ChannelFilter.TAB_FAV);
        for (int i = 0; genres != null && i < genres.length(); i++) {
            JSONObject g = genres.getJSONObject(i);
            genreButtons.add(addTab(g.getString("title"), g.getString("id")));
        }
        refresh();
    }

    private Button addTab(String title, String id) {
        Button b = new Button(getContext());
        b.setText(title);
        PortalStyle.button(b);
        b.setTag(id);
        b.setOnClickListener(v -> { tab = id; refresh(); });
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, PortalStyle.dp(getContext(), 48));
        params.rightMargin = PortalStyle.dp(getContext(), 8);
        tabs.addView(b, params);
        return b;
    }

    private void rankGenreTabs(String query) {
        String normalized = ChannelFilter.normalize(query.trim());
        if (normalized.equals(rankedQuery)) return;
        rankedQuery = normalized;
        List<Button> ordered = new ArrayList<>(genreButtons);
        // Stable sort preserves portal order within both matching and nonmatching groups.
        if (!normalized.isEmpty()) ordered.sort((a, b) -> Boolean.compare(
                ChannelFilter.normalize(b.getText().toString()).contains(normalized),
                ChannelFilter.normalize(a.getText().toString()).contains(normalized)));
        for (int i = 0; i < ordered.size(); i++) {
            Button button = ordered.get(i);
            int position = i + 2; // All and Favorites always occupy the first two positions.
            if (tabs.getChildAt(position) == button) continue;
            tabs.removeView(button);
            tabs.addView(button, position);
        }
        tabScroll.post(() -> tabScroll.scrollTo(0, 0));
    }

    private void refresh() {
        if (filter == null) return;
        rankGenreTabs(search.getText().toString());
        List<ChannelFilter.Channel> shown = filter.apply(tab, search.getText().toString());
        adapter.set(shown);
        count.setText(shown.size() + " channels · Pick a channel to watch");
        for (int i = 0; i < tabs.getChildCount(); i++) {
            Button button = (Button) tabs.getChildAt(i);
            boolean selected = tab.equals(button.getTag());
            button.setSelected(selected);
            button.setTextColor(selected ? PortalStyle.BACKGROUND : Color.WHITE);
            button.setBackground(PortalStyle.interactive(getContext(), selected));
        }
        empty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<ChannelFilter.Channel> items = new ArrayList<>();

        void set(List<ChannelFilter.Channel> next) {
            items = next;
            notifyDataSetChanged(); // ponytail: full rebind per keystroke; DiffUtil if this ever stutters
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout container = new LinearLayout(parent.getContext());
            container.setGravity(Gravity.CENTER_VERTICAL);
            TextView row = new TextView(parent.getContext());
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(PortalStyle.dp(getContext(), 60));
            row.setBackground(PortalStyle.interactive(getContext()));
            row.setFocusable(true);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, parent.getResources().getDisplayMetrics());
            row.setPadding(pad, pad, pad, pad);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = PortalStyle.dp(getContext(), 6);
            container.setLayoutParams(params);
            container.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button star = new Button(parent.getContext());
            PortalStyle.button(star);
            star.setTextSize(24);
            container.addView(star, new LinearLayout.LayoutParams(PortalStyle.dp(getContext(), 56), PortalStyle.dp(getContext(), 56)));
            return new RecyclerView.ViewHolder(container) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ChannelFilter.Channel c = items.get(position);
            LinearLayout container = (LinearLayout) holder.itemView;
            TextView row = (TextView) container.getChildAt(0);
            row.setText((c.number == null ? "" : c.number + "  ") + c.name);
            row.setOnClickListener(v -> onPick.pick(c.id));
            Button star = (Button) container.getChildAt(1);
            boolean favorite = filter.isFavorite(c.id);
            star.setText(favorite ? "★" : "☆");
            star.setSelected(favorite);
            star.setTextColor(favorite ? PortalStyle.ACCENT : PortalStyle.MUTED);
            star.setContentDescription((favorite ? "Remove favorite: " : "Add favorite: ") + c.name);
            star.setOnClickListener(v -> {
                boolean selected = filter.toggleFavorite(c.id);
                if (favorites != null) favorites.edit().putBoolean(c.id, selected).apply();
                refresh();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
