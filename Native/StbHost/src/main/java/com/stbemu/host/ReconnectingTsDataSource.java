package com.stbemu.host;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A live .ts response as one endless stream. IPTV servers close it every 15-40 s and start the next response from
 * their own buffer, replaying 4-18 s already played, from another node with other PTS too. This reopens the URL itself,
 * drops video frames already passed on (matched on content), audio up to the last audio passed on, and shifts the new
 * response's PTS/DTS onto the stream's: the decoder continues from the last frame, no rewind, no seek, no new item.
 */
final class ReconnectingTsDataSource implements DataSource {
    private static final int PACKET = 188;
    private static final long WRAP = 1L << 33; // PTS/DTS are 33-bit
    private static final long REFUSED_BUDGET_MS = 20_000; // servers refuse for ~10-15 s while they still count the closed one
    private static final int REMEMBERED_FRAMES = 8192; // >= the longest replay seen (~40 s) at 60 fps

    private final DataSource upstream;
    private final Consumer<String> log;
    private DataSpec spec;
    private boolean live, upstreamOpen;
    private final byte[] in = new byte[PACKET * 64];
    private int inLength;
    private byte[] out = new byte[0];
    private int outPosition;

    // Stream: what was passed on.
    private int pmtPid = -1, videoPid = -1;
    private int[] esPids;
    private final Map<Integer, Integer> continuity = new HashMap<>();
    private final Map<Integer, Long> lastPts = new HashMap<>(); // non-video PIDs, stream time
    private final LinkedHashMap<Integer, Long> passedFrames = new LinkedHashMap<Integer, Long>() { // frame hash -> stream PTS
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) { return size() > REMEMBERED_FRAMES; }
    };
    private long lastVideoDts = -1, frameDuration = 3600;

    // Response: since the last (re)connect.
    private final Map<Integer, ByteArrayOutputStream> pes = new HashMap<>();
    private final List<byte[]> pending = new ArrayList<>(); // non-video PES held until the splice shift is known
    private boolean splicing, matched;
    private long shift, responseStart; // shift: new response PTS -> stream PTS
    private int dropped, responseBytes;

    ReconnectingTsDataSource(DataSource upstream, Consumer<String> log) {
        this.upstream = upstream;
        this.log = log;
    }

    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }

    @Override public long open(DataSpec spec) throws IOException {
        this.spec = spec;
        long length = upstream.open(spec);
        upstreamOpen = true;
        live = length == C.LENGTH_UNSET; // a known length is VOD: keep it seekable and untouched
        responseStart = System.currentTimeMillis();
        return length;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!live) return upstream.read(buffer, offset, length);
        if (length == 0) return 0;
        while (outPosition == out.length) {
            int read;
            try {
                read = upstream.read(in, inLength, in.length - inLength);
            } catch (InterruptedIOException canceled) {
                throw canceled;
            } catch (IOException lost) { // e.g. the network went away mid-response
                reconnect("failed (" + lost.getClass().getSimpleName() + ")");
                continue;
            }
            if (read == C.RESULT_END_OF_INPUT) {
                reconnect("closed");
                continue;
            }
            responseBytes += read;
            inLength += read;
            ByteArrayOutputStream next = new ByteArrayOutputStream();
            int i = 0;
            for (; i + PACKET <= inLength; i += PACKET) {
                if (in[i] != 0x47) { // out of sync: look for the next packet
                    int sync = i + 1;
                    while (sync < inLength && in[sync] != 0x47) sync++;
                    i = sync - PACKET;
                    continue;
                }
                packet(Arrays.copyOfRange(in, i, i + PACKET), next);
            }
            System.arraycopy(in, i, in, 0, inLength - i);
            inLength -= i;
            out = next.toByteArray();
            outPosition = 0;
        }
        int n = Math.min(length, out.length - outPosition);
        System.arraycopy(out, outPosition, buffer, offset, n);
        outPosition += n;
        return n;
    }

    /** Opens the same URL again; refusals retry each second. The unfinished PES of the old response is dropped. */
    private void reconnect(String how) throws IOException {
        log.accept("Live response " + how + " after " + (System.currentTimeMillis() - responseStart) / 1000 + " s, "
                + responseBytes / 1024 + " KB; reconnecting");
        closeUpstream();
        long refusedSince = responseBytes == 0 ? responseStart : System.currentTimeMillis();
        boolean first = responseBytes > 0;
        while (true) {
            if (!first) sleep();
            first = false;
            try {
                upstream.open(spec);
                upstreamOpen = true;
                break;
            } catch (HttpDataSource.InvalidResponseCodeException refused) {
                // A link the server no longer accepts needs a new one (StbHost.handlePlayerError), not retries.
                int status = refused.responseCode;
                if (status == 401 || status == 403 || status == 404 || status == 509
                        || System.currentTimeMillis() - refusedSince > REFUSED_BUDGET_MS) throw refused;
                log.accept("Reconnect refused: HTTP " + status);
            } catch (InterruptedIOException canceled) {
                throw canceled;
            } catch (IOException refused) {
                if (System.currentTimeMillis() - refusedSince > REFUSED_BUDGET_MS) throw refused;
                log.accept("Reconnect refused: " + refused.getClass().getSimpleName());
            }
            closeUpstream();
        }
        responseStart = System.currentTimeMillis();
        responseBytes = inLength = 0;
        pes.clear();
        pending.clear();
        splicing = true;
        matched = false;
        dropped = 0;
    }

    private static void sleep() throws InterruptedIOException {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    private void packet(byte[] p, ByteArrayOutputStream next) throws IOException {
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        boolean start = (p[1] & 0x40) != 0;
        int payload = 4 + ((p[3] & 0x20) != 0 ? 1 + (p[4] & 0xff) : 0);
        boolean hasPayload = (p[3] & 0x10) != 0 && payload < PACKET;
        if (pid == 0x1fff) return;
        if (pid == 0 || pid == pmtPid) {
            if (start && hasPayload) {
                if (pid == 0) pmtPid = pmtPid(p, payload);
                else readPmt(p, payload);
            }
            emit(p, next);
            return;
        }
        if (!isEs(pid)) {
            emit(p, next);
            return;
        }
        if (start) {
            ByteArrayOutputStream done = pes.remove(pid);
            if (done != null) onPes(pid, done.toByteArray(), next);
            pes.put(pid, new ByteArrayOutputStream());
        }
        ByteArrayOutputStream open = pes.get(pid);
        if (open != null) open.write(p, 0, PACKET); // a response starts mid-PES: wait for the next one
    }

    /** A complete PES (it is followed by the next one): pass it on, retimed, or drop it as replayed. */
    private void onPes(int pid, byte[] packets, ByteArrayOutputStream next) throws IOException {
        int payload = 4 + ((packets[3] & 0x20) != 0 ? 1 + (packets[4] & 0xff) : 0);
        boolean timed = payload + 19 <= PACKET && packets[payload] == 0 && packets[payload + 1] == 0
                && packets[payload + 2] == 1 && (packets[payload + 7] & 0x80) != 0;
        if (!timed) {
            if (!splicing) emitAll(packets, next);
            return;
        }
        long pts = timestamp(packets, payload + 9);
        if (pid != videoPid) {
            if (splicing) pending.add(packets);
            else timedPes(pid, packets, payload, pts, next);
            return;
        }
        int es = payload + 9 + (packets[payload + 8] & 0xff);
        boolean matchable = es + 16 <= PACKET; // too little of the frame in this packet to tell frames apart
        int frame = matchable ? Arrays.hashCode(Arrays.copyOfRange(packets, es, PACKET)) : 0;
        if (splicing) {
            Long passed = matchable ? passedFrames.get(frame) : null;
            if (passed != null) { // replayed: its stream time gives the new response's shift
                shift = mod(passed - pts);
                matched = true;
                dropped++;
                return;
            }
            if (!matched && lastVideoDts >= 0) { // nothing replayed: assume the new response follows on
                long dts = (packets[payload + 7] & 0x40) != 0 ? timestamp(packets, payload + 14) : pts;
                shift = mod(lastVideoDts + frameDuration - dts);
            }
            splicing = false;
            log.accept("Live stream resumed: dropped " + dropped + " replayed frames" + (matched ? "" : ", none matched"));
            for (byte[] held : pending) {
                int heldPayload = 4 + ((held[3] & 0x20) != 0 ? 1 + (held[4] & 0xff) : 0);
                int heldPid = ((held[1] & 0x1f) << 8) | (held[2] & 0xff);
                timedPes(heldPid, held, heldPayload, timestamp(held, heldPayload + 9), next);
            }
            pending.clear();
        }
        retime(packets, payload);
        long streamPts = mod(pts + shift);
        long dts = (packets[payload + 7] & 0x40) != 0 ? timestamp(packets, payload + 14) : streamPts;
        if (lastVideoDts >= 0) {
            long step = signed(dts - lastVideoDts);
            if (step > 0 && step < 9000) frameDuration = step; // up to 0.1 s
        }
        lastVideoDts = dts;
        if (matchable) passedFrames.put(frame, streamPts);
        emitAll(packets, next);
    }

    /** Audio and the like: their PTS only grow, so anything not after the last one passed on is replayed. */
    private void timedPes(int pid, byte[] packets, int payload, long pts, ByteArrayOutputStream next) {
        long streamPts = mod(pts + shift);
        Long last = lastPts.get(pid);
        if (last != null && signed(streamPts - last) <= 0) return;
        retime(packets, payload);
        lastPts.put(pid, streamPts);
        emitAll(packets, next);
    }

    private void retime(byte[] packets, int payload) {
        if (shift == 0) return;
        int flags = packets[payload + 7] & 0xc0;
        write(packets, payload + 9, mod(timestamp(packets, payload + 9) + shift));
        if (flags == 0xc0) write(packets, payload + 14, mod(timestamp(packets, payload + 14) + shift));
    }

    private void emitAll(byte[] packets, ByteArrayOutputStream next) {
        for (int i = 0; i + PACKET <= packets.length; i += PACKET) emit(Arrays.copyOfRange(packets, i, i + PACKET), next);
    }

    /** Continuity counters follow on, or TsExtractor takes the first new packet for a duplicate. */
    private void emit(byte[] p, ByteArrayOutputStream next) {
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        Integer last = continuity.get(pid);
        int counter = p[3] & 0xf;
        if (last != null) counter = (p[3] & 0x10) != 0 ? (last + 1) & 0xf : last;
        continuity.put(pid, counter);
        p[3] = (byte) ((p[3] & 0xf0) | counter);
        next.write(p, 0, PACKET);
    }

    private boolean isEs(int pid) {
        if (esPids == null) return false;
        for (int es : esPids) if (es == pid) return true;
        return false;
    }

    private static int pmtPid(byte[] p, int payload) {
        int t = payload + 1 + (p[payload] & 0xff);
        return t + 11 < PACKET ? ((p[t + 10] & 0x1f) << 8) | (p[t + 11] & 0xff) : -1;
    }

    /** A new node can number its streams differently; the extractor keeps the first PMT, so that needs a restart. */
    private void readPmt(byte[] p, int payload) throws IOException {
        int t = payload + 1 + (p[payload] & 0xff);
        if (t + 12 >= PACKET) return;
        int end = Math.min(PACKET, t + 3 + (((p[t + 1] & 0xf) << 8) | (p[t + 2] & 0xff)) - 4);
        List<Integer> pids = new ArrayList<>();
        int video = -1;
        for (int j = t + 12 + (((p[t + 10] & 0xf) << 8) | (p[t + 11] & 0xff)); j + 4 < end;
                j += 5 + (((p[j + 3] & 0xf) << 8) | (p[j + 4] & 0xff))) {
            int pid = ((p[j + 1] & 0x1f) << 8) | (p[j + 2] & 0xff), type = p[j] & 0xff;
            pids.add(pid);
            if (video < 0 && (type == 0x1b || type == 0x24 || type == 0x02)) video = pid;
        }
        int[] es = pids.stream().mapToInt(Integer::intValue).toArray();
        if (esPids == null) {
            esPids = es;
            videoPid = video;
        } else if (!Arrays.equals(esPids, es)) {
            throw new IOException("Live stream changed its streams on reconnect");
        }
    }

    private static long timestamp(byte[] p, int at) {
        return ((long) (p[at] >> 1) & 7) << 30 | (p[at + 1] & 0xffL) << 22 | ((p[at + 2] & 0xffL) >> 1) << 15
                | (p[at + 3] & 0xffL) << 7 | (p[at + 4] & 0xffL) >> 1;
    }

    private static void write(byte[] p, int at, long t) {
        p[at] = (byte) ((p[at] & 0xf0) | (t >> 29 & 0x0e) | 1);
        p[at + 1] = (byte) (t >> 22);
        p[at + 2] = (byte) ((t >> 14 & 0xfe) | 1);
        p[at + 3] = (byte) (t >> 7);
        p[at + 4] = (byte) ((t << 1 & 0xfe) | 1);
    }

    private static long mod(long t) { return ((t % WRAP) + WRAP) % WRAP; }

    private static long signed(long difference) {
        long d = mod(difference);
        return d >= WRAP / 2 ? d - WRAP : d;
    }

    private void closeUpstream() throws IOException {
        if (!upstreamOpen) return;
        upstreamOpen = false;
        upstream.close();
    }

    @Nullable @Override public Uri getUri() { return upstream.getUri(); }

    @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }

    @Override public void close() throws IOException { closeUpstream(); }
}
