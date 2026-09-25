package com.stbemu.host;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Search + tab filter for the native channel list. Names are normalised once; each keystroke is a linear scan. */
public final class ChannelFilter {
    public static final String TAB_ALL = "";
    public static final String TAB_FAV = "*";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    public static final class Channel {
        public final String id, name, genreId;
        public final Integer number;
        final String key; // normalised "number name" for matching

        public Channel(String id, Integer number, String name, String genreId) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.genreId = genreId;
            this.key = normalize((number == null ? "" : number + " ") + name);
        }
    }

    private final List<Channel> channels;
    private final Set<String> favIds;

    public ChannelFilter(List<Channel> channels, Set<String> favIds) {
        this.channels = channels;
        this.favIds = favIds;
    }

    public List<Channel> apply(String tab, String query) {
        String q = normalize(query == null ? "" : query.trim());
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels) {
            boolean inTab = TAB_ALL.equals(tab) || (TAB_FAV.equals(tab) ? favIds.contains(c.id) : tab.equals(c.genreId));
            if (inTab && (q.isEmpty() || c.key.contains(q))) out.add(c);
        }
        return out;
    }

    static String normalize(String s) {
        String noMarks = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
