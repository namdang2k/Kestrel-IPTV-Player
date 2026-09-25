package com.stbemu.host;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class ChannelFilterTest {
    private final ChannelFilter filter = new ChannelFilter(Arrays.asList(
            new ChannelFilter.Channel("1", 1, "ESPN HD", "g2"),
            new ChannelFilter.Channel("12", 12, "Thời sự 24/7", "g1"),
            new ChannelFilter.Channel("13", 13, "Đà Nẵng TV", "g1")),
            new HashSet<>(Collections.singletonList("13")));

    private static List<String> ids(List<ChannelFilter.Channel> list) {
        return list.stream().map(c -> c.id).collect(Collectors.toList());
    }

    @Test public void allTab_emptyQuery_everything() { assertEquals(Arrays.asList("1", "12", "13"), ids(filter.apply(ChannelFilter.TAB_ALL, ""))); }
    @Test public void blankQuery_isEmptyQuery() { assertEquals(3, filter.apply(ChannelFilter.TAB_ALL, "   ").size()); }
    @Test public void ignoresCaseAndDiacritics() { assertEquals(Arrays.asList("12"), ids(filter.apply(ChannelFilter.TAB_ALL, "THOI SU"))); }
    @Test public void dStroke() { assertEquals(Arrays.asList("13"), ids(filter.apply(ChannelFilter.TAB_ALL, "da nang"))); }
    @Test public void favTab() { assertEquals(Arrays.asList("13"), ids(filter.apply(ChannelFilter.TAB_FAV, ""))); }
    @Test public void genreTab() { assertEquals(Arrays.asList("12", "13"), ids(filter.apply("g1", ""))); }
    @Test public void genreAndQuery_intersect() { assertEquals(Arrays.asList("13"), ids(filter.apply("g1", "nang"))); }
    @Test public void noMatch_empty() { assertEquals(0, filter.apply(ChannelFilter.TAB_ALL, "zzz").size()); }
    @Test public void matchesNumber() { assertEquals(Arrays.asList("12"), ids(filter.apply(ChannelFilter.TAB_ALL, "12"))); }
}
