package com.berg.orbis.osm.extract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On-disk format of an imported extract: one gzip file per 0.1 degree cell
 * holding the ways and multipolygon relations whose bounding box touches the
 * cell, with coordinates in 1e-7 degrees. A cell is about 11 x 5.5 km at
 * 60 degrees north; a map region reads the one to four cells it overlaps.
 */
public final class ExtractTiles {

    /** Cell size in 1e-7 degrees (0.1 degree). */
    public static final int CELL_E7 = 1_000_000;
    public static final double CELL_DEG = 0.1;
    static final int MAGIC = 0x4F524258; // "ORBX"
    static final int VERSION = 1;

    public record Way(long id, Map<String, String> tags, int[] lat, int[] lon) {
    }

    public record Member(boolean inner, int[] lat, int[] lon) {
    }

    public record Relation(long id, Map<String, String> tags, List<Member> members) {
    }

    public record Node(long id, Map<String, String> tags, int lat, int lon) {
    }

    public record Cell(List<Way> ways, List<Relation> relations, List<Node> nodes) {
        public static final Cell EMPTY = new Cell(List.of(), List.of(), List.of());
    }

    private ExtractTiles() {
    }

    public static int cellIndex(int e7) {
        return Math.floorDiv(e7, CELL_E7);
    }

    public static Path cellFile(Path dir, int cellLat, int cellLon) {
        return dir.resolve("c_" + cellLat + "_" + cellLon + ".bin.gz");
    }

    public static Cell read(Path file) throws IOException {
        if (!Files.exists(file)) return Cell.EMPTY;
        List<Way> ways = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("not an Orbis extract cell: " + file);
            int version = in.readInt();
            if (version != VERSION) throw new IOException("unsupported extract cell version " + version);
            while (true) {
                int type = in.read();
                if (type <= 0) break;
                long id = in.readLong();
                Map<String, String> tags = readTags(in);
                if (type == 1) {
                    int n = in.readInt();
                    int[] lat = new int[n], lon = new int[n];
                    for (int i = 0; i < n; i++) {
                        lat[i] = in.readInt();
                        lon[i] = in.readInt();
                    }
                    ways.add(new Way(id, tags, lat, lon));
                } else if (type == 2) {
                    int m = in.readInt();
                    List<Member> members = new ArrayList<>(m);
                    for (int j = 0; j < m; j++) {
                        boolean inner = in.readBoolean();
                        int n = in.readInt();
                        int[] lat = new int[n], lon = new int[n];
                        for (int i = 0; i < n; i++) {
                            lat[i] = in.readInt();
                            lon[i] = in.readInt();
                        }
                        members.add(new Member(inner, lat, lon));
                    }
                    relations.add(new Relation(id, tags, members));
                } else if (type == 3) {
                    nodes.add(new Node(id, tags, in.readInt(), in.readInt()));
                } else {
                    throw new IOException("bad record type " + type);
                }
            }
        } catch (EOFException e) {
            // truncated tail: keep what was read
        }
        return new Cell(ways, relations, nodes);
    }

    private static Map<String, String> readTags(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        Map<String, String> tags = new HashMap<>(n * 2 + 1);
        for (int i = 0; i < n; i++) tags.put(in.readUTF(), in.readUTF());
        return tags;
    }

    /** Streams records into one cell file. */
    public static final class Writer implements Closeable {
        private final DataOutputStream out;
        private final Path tmp, file;

        public Writer(Path file) throws IOException {
            this.file = file;
            this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.getParent());
            this.out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16));
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
        }

        public void way(long id, Map<String, String> tags, int[] lat, int[] lon) throws IOException {
            out.write(1);
            out.writeLong(id);
            writeTags(tags);
            out.writeInt(lat.length);
            for (int i = 0; i < lat.length; i++) {
                out.writeInt(lat[i]);
                out.writeInt(lon[i]);
            }
        }

        public void relation(long id, Map<String, String> tags, List<Member> members) throws IOException {
            out.write(2);
            out.writeLong(id);
            writeTags(tags);
            out.writeInt(members.size());
            for (Member m : members) {
                out.writeBoolean(m.inner());
                out.writeInt(m.lat().length);
                for (int i = 0; i < m.lat().length; i++) {
                    out.writeInt(m.lat()[i]);
                    out.writeInt(m.lon()[i]);
                }
            }
        }

        public void node(long id, Map<String, String> tags, int lat, int lon) throws IOException {
            out.write(3);
            out.writeLong(id);
            writeTags(tags);
            out.writeInt(lat);
            out.writeInt(lon);
        }

        private void writeTags(Map<String, String> tags) throws IOException {
            int n = 0;
            for (Map.Entry<String, String> e : tags.entrySet()) {
                if (fits(e.getKey()) && fits(e.getValue())) n++;
            }
            out.writeShort(Math.min(n, 65535));
            int written = 0;
            for (Map.Entry<String, String> e : tags.entrySet()) {
                if (!fits(e.getKey()) || !fits(e.getValue()) || written >= 65535) continue;
                out.writeUTF(e.getKey());
                out.writeUTF(e.getValue());
                written++;
            }
        }

        private static boolean fits(String s) {
            return s != null && s.length() < 20000;
        }

        @Override
        public void close() throws IOException {
            out.write(0);
            out.close();
            // Antivirus scanners briefly hold freshly written files on Windows: retry the rename before giving up.
            IOException last = null;
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException e) {
                    last = e;
                    try {
                        Thread.sleep(200L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
            throw last;
        }
    }
}
