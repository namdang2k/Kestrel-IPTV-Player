package com.stbemu.host;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReconnectingTsDataSourceTest {
    private static final int VIDEO = 0x100, AUDIO = 0x101, PMT = 0x20;
    private static final long OTHER_NODE = 3600L * 90000; // another node's PTS base, 1 h apart

    @Test public void continuesAcrossAReplayingResponseFromAnotherNode() throws IOException {
        // Response 1 ends inside frame 11. The server refuses once, then replays from frame 6 with other PTS.
        Fake upstream = new Fake(response(1, 11, 0), new IOException("refused"), response(6, 15, OTHER_NODE),
                new HttpDataSource.InvalidResponseCodeException(403, null, null, Collections.emptyMap(),
                        null, new byte[0]));
        Stream out = readAll(upstream);

        // 11 was cut short, so its replayed copy is the first new frame; 15 is still open when the 403 ends it.
        assertEquals(range(1, 14), out.frames.get(VIDEO));
        assertEquals(range(1, 14), out.frames.get(AUDIO));
        for (int f = 1; f <= 14; f++) assertEquals(pts(f), (long) out.pts.get(VIDEO).get(f - 1));
        assertTrue("continuity counters run on", out.continuous);
        assertEquals("the refused open was retried", 2, upstream.opened);
    }

    @Test public void aResponseThatFollowsOnContinuesTheTimeline() throws IOException {
        Fake upstream = new Fake(response(1, 11, 0), response(11, 15, OTHER_NODE),
                new HttpDataSource.InvalidResponseCodeException(404, null, null, Collections.emptyMap(),
                        null, new byte[0]));
        Stream out = readAll(upstream);
        assertEquals(range(1, 14), out.frames.get(VIDEO));
        for (int f = 1; f <= 14; f++) assertEquals(pts(f), (long) out.pts.get(VIDEO).get(f - 1));
    }

    private static long pts(int frame) { return 90000 + frame * 3600L; }

    private static List<Integer> range(int from, int to) {
        List<Integer> r = new ArrayList<>();
        for (int i = from; i <= to; i++) r.add(i);
        return r;
    }

    /** PAT, PMT, then each frame as a two-packet video PES and a one-packet audio PES. The last frame is never closed. */
    private static byte[] response(int from, int to, long base) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] cc = {5, 9}; // a new response restarts its own counters
        write(out, packet(0, 0, true, new byte[] {0, 0, (byte) 0xb0, 13, 0, 1, (byte) 0xc1, 0, 0, 0, 1,
                (byte) (0xe0 | PMT >> 8), PMT, 0, 0, 0, 0}));
        write(out, packet(PMT, 0, true, new byte[] {0, 2, (byte) 0xb0, 23, 0, 1, (byte) 0xc1, 0, 0, (byte) 0xe1, 0,
                (byte) 0xf0, 0, 0x1b, (byte) (0xe0 | VIDEO >> 8), (byte) VIDEO, (byte) 0xf0, 0,
                0x0f, (byte) (0xe0 | AUDIO >> 8), (byte) AUDIO, (byte) 0xf0, 0, 0, 0, 0, 0}));
        for (int f = from; f <= to; f++) {
            long t = (pts(f) + base) % (1L << 33);
            write(out, packet(VIDEO, cc[0]++ & 0xf, true, pes(0xe0, t, f, 20)));
            write(out, packet(VIDEO, cc[0]++ & 0xf, false, new byte[] {(byte) f}));
            write(out, packet(AUDIO, cc[1]++ & 0xf, true, pes(0xc0, t, f, 4)));
        }
        return out.toByteArray();
    }

    /** A PES start: 5-byte PTS header, then frame-specific data (the same for a frame on every node). */
    private static byte[] pes(int streamId, long t, int frame, int dataLength) {
        byte[] p = new byte[14 + dataLength];
        p[2] = 1;
        p[3] = (byte) streamId;
        p[6] = (byte) 0x80;
        p[7] = (byte) 0x80;
        p[8] = 5;
        p[9] = (byte) (0x21 | (t >> 29 & 0x0e));
        p[10] = (byte) (t >> 22);
        p[11] = (byte) ((t >> 14 & 0xfe) | 1);
        p[12] = (byte) (t >> 7);
        p[13] = (byte) ((t << 1 & 0xfe) | 1);
        Arrays.fill(p, 14, p.length, (byte) frame);
        return p;
    }

    private static byte[] packet(int pid, int cc, boolean start, byte[] payload) {
        byte[] p = new byte[188];
        Arrays.fill(p, (byte) 0xff);
        p[0] = 0x47;
        p[1] = (byte) ((start ? 0x40 : 0) | pid >> 8);
        p[2] = (byte) pid;
        p[3] = (byte) (0x10 | cc);
        System.arraycopy(payload, 0, p, 4, payload.length);
        return p;
    }

    private static void write(ByteArrayOutputStream out, byte[] p) { out.write(p, 0, p.length); }

    /** Each ES's frame numbers and PTS in output order, and whether every PID's continuity counter ran on. */
    private static final class Stream {
        final Map<Integer, List<Integer>> frames = new HashMap<>();
        final Map<Integer, List<Long>> pts = new HashMap<>();
        boolean continuous = true;
    }

    private static Stream readAll(Fake upstream) throws IOException {
        ReconnectingTsDataSource source = new ReconnectingTsDataSource(upstream, s -> { });
        source.open(null); // the fake ignores it; DataSpec needs a real Uri
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[100];
        try {
            for (int n; (n = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT; ) bytes.write(buffer, 0, n);
            fail("a live stream does not end");
        } catch (HttpDataSource.InvalidResponseCodeException end) { // the fake's last response
        }
        byte[] d = bytes.toByteArray();
        Stream s = new Stream();
        Map<Integer, Integer> cc = new HashMap<>();
        for (int i = 0; i < d.length; i += 188) {
            int pid = (d[i + 1] & 0x1f) << 8 | d[i + 2] & 0xff, counter = d[i + 3] & 0xf;
            Integer last = cc.put(pid, counter);
            if (last != null && counter != ((last + 1) & 0xf)) s.continuous = false;
            if ((d[i + 1] & 0x40) == 0 || pid < VIDEO) continue;
            int at = i + 4 + 9;
            long t = ((long) (d[at] >> 1) & 7) << 30 | (d[at + 1] & 0xffL) << 22 | ((d[at + 2] & 0xffL) >> 1) << 15
                    | (d[at + 3] & 0xffL) << 7 | (d[at + 4] & 0xffL) >> 1;
            s.frames.computeIfAbsent(pid, k -> new ArrayList<>()).add((int) d[i + 4 + 14]);
            s.pts.computeIfAbsent(pid, k -> new ArrayList<>()).add(t);
        }
        source.close();
        return s;
    }

    /** Responses (or the error an open throws) in order, each read in network-sized pieces. */
    private static final class Fake implements DataSource {
        private final Deque<Object> responses;
        private byte[] data;
        private int position, opened;

        Fake(Object... responses) { this.responses = new ArrayDeque<>(Arrays.asList(responses)); }

        @Override public void addTransferListener(TransferListener listener) { }
        @Override public long open(DataSpec spec) throws IOException {
            Object next = responses.poll();
            if (next instanceof IOException) throw (IOException) next;
            opened++;
            data = (byte[]) next;
            position = 0;
            return C.LENGTH_UNSET;
        }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (position == data.length) return C.RESULT_END_OF_INPUT;
            int n = Math.min(Math.min(length, 333), data.length - position);
            System.arraycopy(data, position, buffer, offset, n);
            position += n;
            return n;
        }
        @Override public Uri getUri() { return null; }
        @Override public void close() { }
    }
}
