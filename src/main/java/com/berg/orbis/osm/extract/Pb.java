package com.berg.orbis.osm.extract;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal protobuf wire-format reader: just enough for OSM PBF (varints,
 * zigzag sints, length-delimited sub-messages, packed repeated fields). No
 * generated classes, no dependencies.
 */
final class Pb {

    final byte[] buf;
    int pos;
    final int end;

    Pb(byte[] buf) {
        this(buf, 0, buf.length);
    }

    Pb(byte[] buf, int start, int end) {
        this.buf = buf;
        this.pos = start;
        this.end = end;
    }

    boolean hasMore() {
        return pos < end;
    }

    int readTag() {
        return (int) readVarint();
    }

    long readVarint() {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IllegalStateException("varint too long");
        }
    }

    long readSint() {
        long v = readVarint();
        return (v >>> 1) ^ -(v & 1);
    }

    int readLength() {
        int len = (int) readVarint();
        if (len < 0 || pos + len > end) throw new IllegalStateException("bad length " + len);
        return len;
    }

    Pb readMessage() {
        int len = readLength();
        Pb m = new Pb(buf, pos, pos + len);
        pos += len;
        return m;
    }

    byte[] readBytes() {
        int len = readLength();
        byte[] b = Arrays.copyOfRange(buf, pos, pos + len);
        pos += len;
        return b;
    }

    String readString() {
        int len = readLength();
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    void skip(int wireType) {
        switch (wireType) {
            case 0 -> readVarint();
            case 1 -> pos += 8;
            case 2 -> {
                // (not "pos += readLength()": the left operand would be read before the call moved pos)
                int len = readLength();
                pos += len;
            }
            case 5 -> pos += 4;
            default -> throw new IllegalStateException("unsupported wire type " + wireType);
        }
    }

    /** Packed varints (wire type 2) or a single unpacked one (wire type 0). */
    void readVarints(int wireType, LongList out) {
        if (wireType == 2) {
            int len = readLength();
            int stop = pos + len;
            while (pos < stop) out.add(readVarint());
        } else {
            out.add(readVarint());
        }
    }

    void readSints(int wireType, LongList out) {
        if (wireType == 2) {
            int len = readLength();
            int stop = pos + len;
            while (pos < stop) out.add(readSint());
        } else {
            out.add(readSint());
        }
    }

    /** Growable long array. */
    static final class LongList {
        long[] a;
        int n;

        LongList() {
            this(16);
        }

        LongList(int cap) {
            a = new long[Math.max(1, cap)];
        }

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        long get(int i) {
            return a[i];
        }

        int size() {
            return n;
        }

        void clear() {
            n = 0;
        }

        long[] toArray() {
            return Arrays.copyOf(a, n);
        }

        /** Sorted, duplicates removed. */
        long[] sortedUnique() {
            long[] s = toArray();
            Arrays.sort(s);
            int w = 0;
            for (int i = 0; i < s.length; i++) {
                if (w == 0 || s[i] != s[w - 1]) s[w++] = s[i];
            }
            return Arrays.copyOf(s, w);
        }
    }

    /** Growable int array. */
    static final class IntList {
        int[] a;
        int n;

        IntList() {
            a = new int[8];
        }

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        int get(int i) {
            return a[i];
        }

        int size() {
            return n;
        }
    }
}
