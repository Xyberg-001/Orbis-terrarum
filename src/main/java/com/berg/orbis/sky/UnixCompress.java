package com.berg.orbis.sky;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Unpacks a file of the Unix "compress" program (.Z: LZW with codes of 9 to 16 bits, least significant bit first), as
 * the classic ncompress does, including its habit of skipping to the end of a group of eight codes whenever the code
 * width changes or the table is cleared.
 */
final class UnixCompress {
    private UnixCompress() {
    }

    static byte[] decompress(byte[] in, int expectedSize) throws IOException {
        if (in.length < 3 || (in[0] & 0xff) != 0x1f || (in[1] & 0xff) != 0x9d) throw new IOException("not a .Z file");
        int maxBits = in[2] & 0x1f;
        boolean blockMode = (in[2] & 0x80) != 0;
        if (maxBits < 9 || maxBits > 16) throw new IOException(".Z with " + maxBits + " bits");
        int maxMaxCode = 1 << maxBits;
        int[] prefix = new int[maxMaxCode];
        byte[] suffix = new byte[maxMaxCode];
        for (int c = 0; c < 256; c++) suffix[c] = (byte) c;
        byte[] stack = new byte[maxMaxCode];
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, expectedSize));

        long totalBits = (long) in.length * 8;
        long pos = 24, groupStart = 24; // bit positions in the file; groups count from the end of the 3-byte header
        int nBits = 9, maxCode = (1 << nBits) - 1, mask = (1 << nBits) - 1;
        int free = blockMode ? 257 : 256;
        int oldCode = -1, finChar = 0;
        while (true) {
            if (free > maxCode) { // (at the widest, maxCode is the table's size, which free never passes)
                // a wider code from here, after the rest of this group
                pos = align(pos, groupStart, nBits);
                groupStart = pos;
                nBits++;
                maxCode = nBits == maxBits ? maxMaxCode : (1 << nBits) - 1;
                mask = (1 << nBits) - 1;
            }
            if (pos + nBits > totalBits) break;
            int code = read(in, pos, nBits) & mask;
            pos += nBits;
            if (oldCode == -1) {
                if (code >= 256) throw new IOException("bad first code");
                finChar = oldCode = code;
                out.write(code);
                continue;
            }
            if (code == 256 && blockMode) {
                free = 256; // the next entry made is 257
                pos = align(pos, groupStart, nBits);
                groupStart = pos;
                nBits = 9;
                maxCode = (1 << nBits) - 1;
                mask = (1 << nBits) - 1;
                // ncompress reads the next code as an old code with no entry made after a clear
                if (pos + nBits > totalBits) break;
                code = read(in, pos, nBits) & mask;
                pos += nBits;
                finChar = oldCode = code;
                out.write(code);
                free = 257;
                continue;
            }
            int inCode = code, sp = stack.length;
            if (code >= free) {
                if (code > free) throw new IOException("corrupt .Z (code " + code + " > " + free + ")");
                stack[--sp] = (byte) finChar;
                code = oldCode;
            }
            while (code >= 256) {
                stack[--sp] = suffix[code];
                code = prefix[code];
            }
            finChar = suffix[code] & 0xff;
            stack[--sp] = (byte) finChar;
            out.write(stack, sp, stack.length - sp);
            if (free < maxMaxCode) {
                prefix[free] = oldCode;
                suffix[free] = (byte) finChar;
                free++;
            }
            oldCode = inCode;
        }
        return out.toByteArray();
    }

    /** The bit position rounded up to the end of its group of eight codes (nBits bytes) counted from the group start. */
    private static long align(long pos, long groupStart, int nBits) {
        long group = nBits * 8L, rel = pos - groupStart;
        return groupStart + (rel + group - 1) / group * group;
    }

    private static int read(byte[] in, long pos, int n) {
        int byteIdx = (int) (pos >>> 3), shift = (int) (pos & 7);
        long v = 0;
        for (int i = 0; i < 4 && byteIdx + i < in.length; i++) v |= (long) (in[byteIdx + i] & 0xff) << (8 * i);
        return (int) (v >>> shift) & ((1 << n) - 1);
    }
}
