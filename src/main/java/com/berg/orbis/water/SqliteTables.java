package com.berg.orbis.water;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads the rows of a table in an SQLite file, which is what a GeoPackage is (Quebec's lake surveys come as one per lake):
 * the schema from sqlite_master, then the table's b-tree page by page, overflow pages included. Read only, no indexes,
 * no journal; enough for the plain tables GDAL writes.
 */
final class SqliteTables implements Closeable {
    private final RandomAccessFile file;
    private final int pageSize;
    private final int usable;

    SqliteTables(Path path) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "r");
        byte[] head = new byte[100];
        file.readFully(head);
        if (!new String(head, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3")) throw new IOException("not an SQLite file");
        int ps = ((head[16] & 0xFF) << 8) | (head[17] & 0xFF);
        this.pageSize = ps == 1 ? 65536 : ps;
        this.usable = pageSize - (head[20] & 0xFF);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    /** A table: its b-tree's root page and its column names in order. */
    record Table(String name, int rootPage, List<String> columns) {
        int column(String name) {
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).equalsIgnoreCase(name)) return i;
            return -1;
        }
    }

    /** Every table in the file. */
    List<Table> tables() throws IOException {
        List<Table> out = new ArrayList<>();
        rows(1, row -> {
            if (row.length >= 5 && "table".equals(row[0]) && row[1] instanceof String name && row[3] instanceof Long root && row[4] instanceof String sql) {
                out.add(new Table(name, (int) (long) root, columnsOf(sql)));
            }
        });
        return out;
    }

    /** Column names from a CREATE TABLE statement (constraints left out). */
    static List<String> columnsOf(String sql) {
        List<String> out = new ArrayList<>();
        int open = sql.indexOf('('), close = sql.lastIndexOf(')');
        if (open < 0 || close < open) return out;
        String body = sql.substring(open + 1, close);
        int depth = 0;
        StringBuilder part = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (char c : body.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        parts.add(part.toString());
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            String first = t.split("\\s+")[0];
            String upper = first.toUpperCase(java.util.Locale.ROOT);
            if (upper.equals("CONSTRAINT") || upper.equals("PRIMARY") || upper.equals("UNIQUE") || upper.equals("CHECK") || upper.equals("FOREIGN")) continue;
            out.add(first.replace("\"", "").replace("`", "").replace("[", "").replace("]", ""));
        }
        return out;
    }

    /**
     * Every row of the table whose b-tree starts at {@code root}, in rowid order: its values (Long, Double, String, byte[]
     * or null). An INTEGER PRIMARY KEY column reads as null (SQLite keeps it as the rowid).
     */
    void rows(int root, Consumer<Object[]> each) throws IOException {
        walk(root, each, 0);
    }

    private void walk(int page, Consumer<Object[]> each, int depth) throws IOException {
        if (depth > 40) throw new IOException("b-tree too deep");
        byte[] p = page(page);
        int h = page == 1 ? 100 : 0;
        int type = p[h] & 0xFF;
        int cells = u16(p, h + 3);
        if (type == 0x05) {
            int right = (int) u32(p, h + 8);
            for (int i = 0; i < cells; i++) {
                int at = u16(p, h + 12 + 2 * i);
                walk((int) u32(p, at), each, depth + 1);
            }
            walk(right, each, depth + 1);
        } else if (type == 0x0D) {
            for (int i = 0; i < cells; i++) {
                int at = u16(p, h + 8 + 2 * i);
                long[] v = varint(p, at);
                long size = v[0];
                long[] rowid = varint(p, (int) v[1]);
                int start = (int) rowid[1];
                each.accept(record(payload(p, start, size)));
            }
        } else {
            throw new IOException("unexpected page type " + type);
        }
    }

    /** A cell's payload, its overflow pages followed. */
    private byte[] payload(byte[] p, int start, long size) throws IOException {
        int x = usable - 35;
        if (size <= x) return java.util.Arrays.copyOfRange(p, start, start + (int) size);
        int m = ((usable - 12) * 32 / 255) - 23;
        int k = (int) (m + ((size - m) % (usable - 4)));
        int local = k <= x ? k : m;
        byte[] out = new byte[(int) size];
        System.arraycopy(p, start, out, 0, local);
        int done = local;
        long next = u32(p, start + local);
        while (done < size && next != 0) {
            byte[] o = page((int) next);
            int n = (int) Math.min(usable - 4, size - done);
            System.arraycopy(o, 4, out, done, n);
            done += n;
            next = u32(o, 0);
        }
        return out;
    }

    private static Object[] record(byte[] b) {
        long[] hs = varint(b, 0);
        int headerEnd = (int) hs[0];
        int pos = (int) hs[1];
        List<Long> types = new ArrayList<>();
        while (pos < headerEnd) {
            long[] t = varint(b, pos);
            types.add(t[0]);
            pos = (int) t[1];
        }
        Object[] out = new Object[types.size()];
        int at = headerEnd;
        for (int i = 0; i < out.length; i++) {
            long t = types.get(i);
            if (t == 0) {
                out[i] = null;
            } else if (t >= 1 && t <= 6) {
                int n = t <= 4 ? (int) t : t == 5 ? 6 : 8;
                long v = b[at];
                for (int k = 1; k < n; k++) v = (v << 8) | (b[at + k] & 0xFF);
                out[i] = v;
                at += n;
            } else if (t == 7) {
                long v = 0;
                for (int k = 0; k < 8; k++) v = (v << 8) | (b[at + k] & 0xFF);
                out[i] = Double.longBitsToDouble(v);
                at += 8;
            } else if (t == 8 || t == 9) {
                out[i] = t == 8 ? 0L : 1L;
            } else if (t >= 12) {
                int n = (int) ((t - (t % 2 == 0 ? 12 : 13)) / 2);
                byte[] v = java.util.Arrays.copyOfRange(b, at, at + n);
                out[i] = t % 2 == 0 ? v : new String(v, StandardCharsets.UTF_8);
                at += n;
            }
        }
        return out;
    }

    private byte[] page(int n) throws IOException {
        byte[] p = new byte[pageSize];
        file.seek((long) (n - 1) * pageSize);
        file.readFully(p);
        return p;
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static long u32(byte[] b, int at) {
        return ((long) (b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** SQLite's varint: {value, position after it}. */
    private static long[] varint(byte[] b, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int c = b[at + i] & 0xFF;
            v = (v << 7) | (c & 0x7F);
            if (c < 0x80) return new long[]{v, at + i + 1};
        }
        v = (v << 8) | (b[at + 8] & 0xFF);
        return new long[]{v, at + 9};
    }
}
