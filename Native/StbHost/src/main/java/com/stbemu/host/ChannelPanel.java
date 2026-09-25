package com.stbemu.host;

import android.content.Context;
import android.graphics.Color;
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

/** Native channel list over the portal: search box, tabs (All, ★, genres), smooth RecyclerView. */
final class ChannelPanel extends LinearLayout {
    interface OnPick { void pick(String channelId); }

    private final OnPick onPick;
    private final EditText search;
    private final LinearLayout tabs;
    private final Adapter adapter = new Adapter();
    private final TextView empty;
    private ChannelFilter filter;
    private String tab = ChannelFilter.TAB_ALL;

    ChannelPanel(Context context, OnPick onPick) {
        super(context);
        this.onPick = onPick;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF101010); // opaque: the portal and video behind made the list hard to read

        search = new EditText(context);
        search.setSingleLine(true);
        // No keyboard composition: Vietnamese Telex turned "espn" into "épn". Matching ignores diacritics, so plain
        // letters still find accented names ("thoi su" → "Thời sự").
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setHint("Tìm kênh");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x88FFFFFF);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        addView(search, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabs = new LinearLayout(context);
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.addView(tabs);
        addView(tabScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = new TextView(context);
        empty.setText("Không có kênh phù hợp");
        empty.setTextColor(0xAAFFFFFF);
        empty.setVisibility(GONE);
        addView(empty);

        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    boolean hasData() {
        return filter != null;
    }

    void setData(JSONObject payload) throws JSONException {
        List<ChannelFilter.Channel> channels = new ArrayList<>();
        JSONArray arr = payload.getJSONArray("channels");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            channels.add(new ChannelFilter.Channel(c.getString("id"), c.isNull("number") ? null : c.getInt("number"),
                    c.getString("name"), c.optString("genreId", "")));
        }
        Set<String> favs = new HashSet<>();
        JSONArray favArr = payload.optJSONArray("favIds");
        for (int i = 0; favArr != null && i < favArr.length(); i++) favs.add(favArr.getString(i));
        filter = new ChannelFilter(channels, favs);

        tabs.removeAllViews();
        addTab("Tất cả", ChannelFilter.TAB_ALL);
        addTab("★", ChannelFilter.TAB_FAV);
        JSONArray genres = payload.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            JSONObject g = genres.getJSONObject(i);
            addTab(g.getString("title"), g.getString("id"));
        }
        refresh();
    }

    private void addTab(String title, String id) {
        Button b = new Button(getContext());
        b.setText(title);
        b.setAllCaps(false);
        b.setOnClickListener(v -> { tab = id; refresh(); });
        tabs.addView(b);
    }

    private void refresh() {
        if (filter == null) return;
        List<ChannelFilter.Channel> shown = filter.apply(tab, search.getText().toString());
        adapter.set(shown);
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
            TextView row = new TextView(parent.getContext());
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, parent.getResources().getDisplayMetrics());
            row.setPadding(pad, pad, pad, pad);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ChannelFilter.Channel c = items.get(position);
            TextView row = (TextView) holder.itemView;
            row.setText((c.number == null ? "" : c.number + "  ") + c.name);
            row.setOnClickListener(v -> onPick.pick(c.id));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
