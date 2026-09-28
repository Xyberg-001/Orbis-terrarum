package com.berg.orbis.osm.extract;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Streams an OpenStreetMap PBF file (the format Geofabrik and planet.osm
 * publish) block by block and hands nodes, ways and relations to a visitor.
 * Only the blocks the visitor asks for are decoded, and a reader can be
 * opened on a byte range so the importer can re-read just the ways or just
 * the nodes of a sorted extract.
 */
public final class PbfReader implements Closeable {

    /** Receives the entities of a file. Everything defaults to "not interested". */
    public interface Visitor {
        default boolean wantsNodes() {
            return false;
        }

        default boolean wantsWays() {
            return false;
        }

        default boolean wantsRelations() {
            return false;
        }

        /** Called for every data block before its entities, with the block's file offset and what it contains. */
        default void block(long offset, boolean hasNodes, boolean hasWays, boolean hasRelations) {
        }

        default void node(long id, int latE7, int lonE7) {
        }

        /** Whether tagged nodes should also be delivered through {@link #taggedNode} (costs the tag decoding). */
        default boolean wantsNodeTags() {
            return false;
        }

        default void taggedNode(long id, int latE7, int lonE7, Tags tags) {
        }

        default void way(long id, Tags tags, long[] refs) {
        }

        /** Member types: 0 node, 1 way, 2 relation. */
        default void relation(long id, Tags tags, long[] memberIds, byte[] memberTypes, String[] roles) {
        }
    }

    /** Tags of one entity, resolved lazily against the block's string table. */
    public static final class Tags {
        private final String[] table;
        private final int[] keys, vals;
        private final int n;

        Tags(String[] table, int[] keys, int[] vals, int n) {
            this.table = table;
            this.keys = keys;
            this.vals = vals;
            this.n = n;
        }

        public int size() {
            return n;
        }

        public String get(String key) {
            for (int i = 0; i < n; i++) {
                if (table[keys[i]].equals(key)) return table[vals[i]];
            }
            return null;
        }

        public boolean has(String key) {
            return get(key) != null;
        }

        public Map<String, String> toMap() {
            Map<String, String> m = new HashMap<>(n * 2 + 1);
            for (int i = 0; i < n; i++) m.put(table[keys[i]], table[vals[i]]);
            return m;
        }
    }

    private final InputStream in;
    private final long endOffset;
    private long offset;
    private long blockOffset;
    private final Inflater inflater = new Inflater();
    private final Pb.LongList l1 = new Pb.LongList(8192), l2 = new Pb.LongList(8192), l3 = new Pb.LongList(8192), l4 = new Pb.LongList(64);
    private int[] keyBuf = new int[64], valBuf = new int[64];

    /** Opens the file at byte offset {@code start}; blocks starting at or beyond {@code end} are not read (end <= 0: whole file). */
    public PbfReader(Path file, long start, long end) throws IOException {
        FileInputStream fis = new FileInputStream(file.toFile());
        if (start > 0) fis.getChannel().position(start);
        this.in = new BufferedInputStream(fis, 1 << 20);
        this.offset = start;
        this.endOffset = end;
    }

    public PbfReader(Path file) throws IOException {
        this(file, 0, 0);
    }

    /** File offset of the block most recently read. */
    public long blockOffset() {
        return blockOffset;
    }

    /** Reads one block; false at the end of the file or range. */
    public boolean readBlock(Visitor visitor) throws IOException {
        if (endOffset > 0 && offset >= endOffset) return false;
        byte[] lenBytes = new byte[4];
        int got = in.readNBytes(lenBytes, 0, 4);
        if (got == 0) return false;
        if (got < 4) throw new EOFException("truncated blob header length");
        int headerLen = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16) | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);
        byte[] header = readFully(headerLen);
        String type = null;
        int dataSize = -1;
        Pb h = new Pb(header);
        while (h.hasMore()) {
            int tag = h.readTag();
            switch (tag >>> 3) {
                case 1 -> type = h.readString();
                case 3 -> dataSize = (int) h.readVarint();
                default -> h.skip(tag & 7);
            }
        }
        if (dataSize < 0) throw new IOException("blob header without datasize");
        byte[] blob = readFully(dataSize);
        blockOffset = offset;
        offset += 4 + headerLen + dataSize;
        if (!"OSMData".equals(type)) return true;
        byte[] data = inflate(blob);
        decodeBlock(data, visitor);
        return true;
    }

    private byte[] readFully(int len) throws IOException {
        byte[] b = new byte[len];
        int got = in.readNBytes(b, 0, len);
        if (got < len) throw new EOFException("truncated file");
        return b;
    }

    private byte[] inflate(byte[] blob) throws IOException {
        Pb b = new Pb(blob);
        byte[] raw = null, zlib = null;
        int rawSize = -1;
        while (b.hasMore()) {
            int tag = b.readTag();
            switch (tag >>> 3) {
                case 1 -> raw = b.readBytes();
                case 2 -> rawSize = (int) b.readVarint();
                case 3 -> zlib = b.readBytes();
                default -> b.skip(tag & 7);
            }
        }
        if (raw != null) return raw;
        if (zlib == null) throw new IOException("blob uses an unsupported compression (only zlib and raw are read)");
        byte[] out = new byte[rawSize > 0 ? rawSize : zlib.length * 4];
        inflater.reset();
        inflater.setInput(zlib);
        int n = 0;
        try {
            while (!inflater.finished()) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                int c = inflater.inflate(out, n, out.length - n);
                if (c == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                n += c;
            }
        } catch (DataFormatException e) {
            throw new IOException("corrupt zlib block", e);
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private void decodeBlock(byte[] data, Visitor visitor) {
        Pb pb = new Pb(data);
        String[] table = new String[0];
        List<Pb> groups = new ArrayList<>(4);
        long granularity = 100, latOffset = 0, lonOffset = 0;
        while (pb.hasMore()) {
            int tag = pb.readTag();
            switch (tag >>> 3) {
                case 1 -> table = readStringTable(pb.readMessage());
                case 2 -> groups.add(pb.readMessage());
                case 17 -> granularity = pb.readVarint();
                case 19 -> latOffset = pb.readVarint();
                case 20 -> lonOffset = pb.readVarint();
                default -> pb.skip(tag & 7);
            }
        }
        boolean hasNodes = false, hasWays = false, hasRelations = false;
        for (Pb g : groups) {
            Pb probe = new Pb(g.buf, g.pos, g.end);
            while (probe.hasMore()) {
                int tag = probe.readTag();
                switch (tag >>> 3) {
                    case 1, 2 -> hasNodes = true;
                    case 3 -> hasWays = true;
                    case 4 -> hasRelations = true;
                    default -> { }
                }
                probe.skip(tag & 7);
            }
        }
        visitor.block(blockOffset, hasNodes, hasWays, hasRelations);
        currentTable = table;
        for (Pb g : groups) {
            while (g.hasMore()) {
                int tag = g.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> {
                        Pb node = g.readMessage();
                        if (visitor.wantsNodes()) decodeNode(node, granularity, latOffset, lonOffset, visitor);
                    }
                    case 2 -> {
                        Pb dense = g.readMessage();
                        if (visitor.wantsNodes()) decodeDense(dense, granularity, latOffset, lonOffset, visitor);
                    }
                    case 3 -> {
                        Pb way = g.readMessage();
                        if (visitor.wantsWays()) decodeWay(way, table, visitor);
                    }
                    case 4 -> {
                        Pb rel = g.readMessage();
                        if (visitor.wantsRelations()) decodeRelation(rel, table, visitor);
                    }
                    default -> g.skip(tag & 7);
                }
            }
        }
    }

    private static String[] readStringTable(Pb st) {
        List<String> strings = new ArrayList<>(1024);
        while (st.hasMore()) {
            int tag = st.readTag();
            if ((tag >>> 3) == 1) strings.add(st.readString());
            else st.skip(tag & 7);
        }
        return strings.toArray(new String[0]);
    }

    private static int toE7(long offset, long granularity, long v) {
        // nanodegrees -> 1e-7 degrees
        long nano = offset + granularity * v;
        return (int) Math.floorDiv(nano + 50, 100L);
    }

    private void decodeNode(Pb n, long granularity, long latOffset, long lonOffset, Visitor visitor) {
        long id = 0, lat = 0, lon = 0;
        l1.clear();
        l2.clear();
        while (n.hasMore()) {
            int tag = n.readTag();
            switch (tag >>> 3) {
                case 1 -> id = n.readSint();
                case 2 -> n.readVarints(tag & 7, l1);
                case 3 -> n.readVarints(tag & 7, l2);
                case 8 -> lat = n.readSint();
                case 9 -> lon = n.readSint();
                default -> n.skip(tag & 7);
            }
        }
        int la = toE7(latOffset, granularity, lat), lo = toE7(lonOffset, granularity, lon);
        visitor.node(id, la, lo);
        if (visitor.wantsNodeTags() && l1.size() > 0) visitor.taggedNode(id, la, lo, tags(currentTable, l1, l2));
    }

    private String[] currentTable = new String[0];
    private final Pb.LongList kv = new Pb.LongList(8192);

    private void decodeDense(Pb d, long granularity, long latOffset, long lonOffset, Visitor visitor) {
        l1.clear();
        l2.clear();
        l3.clear();
        kv.clear();
        boolean tags = visitor.wantsNodeTags();
        while (d.hasMore()) {
            int tag = d.readTag();
            switch (tag >>> 3) {
                case 1 -> d.readSints(tag & 7, l1);
                case 8 -> d.readSints(tag & 7, l2);
                case 9 -> d.readSints(tag & 7, l3);
                case 10 -> {
                    if (tags) d.readVarints(tag & 7, kv);
                    else d.skip(tag & 7);
                }
                default -> d.skip(tag & 7);
            }
        }
        int n = Math.min(l1.size(), Math.min(l2.size(), l3.size()));
        long id = 0, lat = 0, lon = 0;
        int kvPos = 0;
        Pb.LongList keys = tags ? new Pb.LongList(8) : null, vals = tags ? new Pb.LongList(8) : null;
        for (int i = 0; i < n; i++) {
            id += l1.get(i);
            lat += l2.get(i);
            lon += l3.get(i);
            int la = toE7(latOffset, granularity, lat), lo = toE7(lonOffset, granularity, lon);
            visitor.node(id, la, lo);
            if (tags && kvPos < kv.size()) {
                // keys_vals: key,value string ids per node, each node's list terminated by 0
                keys.clear();
                vals.clear();
                while (kvPos < kv.size()) {
                    int k = (int) kv.get(kvPos++);
                    if (k == 0) break;
                    if (kvPos >= kv.size()) break;
                    keys.add(k);
                    vals.add(kv.get(kvPos++));
                }
                if (keys.size() > 0) visitor.taggedNode(id, la, lo, tags(currentTable, keys, vals));
            }
        }
    }

    private void decodeWay(Pb w, String[] table, Visitor visitor) {
        long id = 0;
        l1.clear();
        l2.clear();
        l3.clear();
        while (w.hasMore()) {
            int tag = w.readTag();
            switch (tag >>> 3) {
                case 1 -> id = w.readVarint();
                case 2 -> w.readVarints(tag & 7, l1);
                case 3 -> w.readVarints(tag & 7, l2);
                case 8 -> w.readSints(tag & 7, l3);
                default -> w.skip(tag & 7);
            }
        }
        long[] refs = new long[l3.size()];
        long acc = 0;
        for (int i = 0; i < refs.length; i++) {
            acc += l3.get(i);
            refs[i] = acc;
        }
        visitor.way(id, tags(table, l1, l2), refs);
    }

    private void decodeRelation(Pb r, String[] table, Visitor visitor) {
        long id = 0;
        l1.clear();
        l2.clear();
        l3.clear();
        l4.clear();
        Pb.LongList roles = new Pb.LongList(16);
        while (r.hasMore()) {
            int tag = r.readTag();
            switch (tag >>> 3) {
                case 1 -> id = r.readVarint();
                case 2 -> r.readVarints(tag & 7, l1);
                case 3 -> r.readVarints(tag & 7, l2);
                case 8 -> r.readVarints(tag & 7, roles);
                case 9 -> r.readSints(tag & 7, l3);
                case 10 -> r.readVarints(tag & 7, l4);
                default -> r.skip(tag & 7);
            }
        }
        int n = l3.size();
        long[] memberIds = new long[n];
        byte[] types = new byte[n];
        String[] roleNames = new String[n];
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc += l3.get(i);
            memberIds[i] = acc;
            types[i] = (byte) (i < l4.size() ? l4.get(i) : 1);
            int sid = i < roles.size() ? (int) roles.get(i) : 0;
            roleNames[i] = sid >= 0 && sid < table.length ? table[sid] : "";
        }
        visitor.relation(id, tags(table, l1, l2), memberIds, types, roleNames);
    }

    private Tags tags(String[] table, Pb.LongList keys, Pb.LongList vals) {
        int n = Math.min(keys.size(), vals.size());
        if (keyBuf.length < n) {
            keyBuf = new int[n * 2];
            valBuf = new int[n * 2];
        }
        int[] k = new int[n], v = new int[n];
        for (int i = 0; i < n; i++) {
            k[i] = (int) keys.get(i);
            v[i] = (int) vals.get(i);
            if (k[i] < 0 || k[i] >= table.length || v[i] < 0 || v[i] >= table.length) {
                k[i] = 0;
                v[i] = 0;
            }
        }
        return new Tags(table, k, v, n);
    }

    @Override
    public void close() throws IOException {
        inflater.end();
        in.close();
    }

    /** UTF-8 helper for the writer side. */
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
