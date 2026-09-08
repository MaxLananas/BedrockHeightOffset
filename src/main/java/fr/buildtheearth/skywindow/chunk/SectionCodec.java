package fr.buildtheearth.skywindow.chunk;

/**
 * Byte-level walker over the section array of a Java {@code ClientboundLevelChunkWithLight} payload.
 *
 * <p>Payload layout (1.20.2+): exactly {@code chunkSize} consecutive sections in ascending world order -
 * there is no leading section-count field any more, Geyser's own translator reads the same sections
 * sequentially {@code (sectionY < session.getChunkCache().getChunkHeightY())}. Each section is
 * {@code short blockCount, short fluidCount, dataPalette(blocks), dataPalette(biomes)}. A data palette
 * is {@code ubyte bitsPerEntry}; 0 means a singleton palette (one varint id, no storage); otherwise an
 * optional varint-prefixed palette id list (absent for global palettes, i.e. when bitsPerEntry exceeds
 * the palette type's maximum) followed by fixed-size long-array storage (varint long count, then that
 * many 8-byte longs). This mirrors {@code MinecraftTypes.readChunkSection}/{@code writeDataPalette} of
 * mcprotocollib, which is the decoder Geyser itself feeds this payload to.</p>
 *
 * <p>Reslicing a windowed chunk therefore only needs the byte extent of each section, not the block
 * data itself: sections are moved (or synthesized as empty) as whole byte ranges, so palettes,
 * waterlogged re-encoding and block counts all keep working inside Geyser unchanged.</p>
 *
 * <p>{@link #resliceTolerant} never throws: it walks as many sections as it can parse, pads or
 * truncates to the exact section count Geyser's translator will loop over, and flags the anomaly.
 * Malformed sections degrade to air (a visual hole) instead of letting Geyser read past the end of
 * the payload into the following packet fields - which on some Geyser paths is a decode exception on
 * a packet thread, i.e. a disconnect. Robustness here is not optional.</p>
 */
public final class SectionCodec {
    /** Counts 0/0, singleton block palette id 0 (air), singleton biome palette id 0 (plains). */
    public static final byte[] EMPTY_SECTION = {0, 0, 0, 0, 0, 0, 0, 0};

    /**
     * Maximum palette bits that still imply an explicit on-wire palette id list. Above these values
     * the section uses the global block state / world biome registry directly and stores no ids.
     * Values follow mcprotocollib's {@code PaletteType} (BLOCK_STATE max 8, BIOME max 3).
     */
    private static final int BLOCK_MAX_PALETTE_BITS = 8;
    private static final int BIOME_MAX_PALETTE_BITS = 3;

    private SectionCodec() {
    }

    public static final class FormatException extends RuntimeException {
        public FormatException(String message) {
            super(message);
        }
    }

    /** Result of a tolerant re-slice; {@code data} is always a valid {@code outSections}-section payload. */
    public record Result(byte[] data, int sectionsParsed, boolean anomaly) {
    }

    /** @return the byte length of the section starting at {@code offset}; throws on malformed input. */
    public static int sectionLength(byte[] data, int offset) {
        int pos = offset + 4; // block count + fluid count
        if (offset < 0 || pos > data.length) {
            throw new FormatException("section header out of bounds");
        }
        pos = skipPalette(data, pos, BLOCK_MAX_PALETTE_BITS);
        pos = skipPalette(data, pos, BIOME_MAX_PALETTE_BITS);
        if (pos > data.length) {
            throw new FormatException("section exceeds payload bounds");
        }
        return pos - offset;
    }

    /**
     * Rebuilds a payload of {@code outSections} sections where {@code out[w] = in[w + shiftSections]},
     * tolerantly: unparsable or missing source sections become canonical air, sections beyond
     * {@code inSections} are ignored, and trailing junk in the source is not propagated. Callers can
     * detect the anomaly from the flag and report it, never crash.
     *
     * <p>Perf shape: one pass to establish section extents, one exact-size allocation, one pass of
     * bulk copies - no incremental buffer growth, which matters because chunk bursts at spawn put
     * ~200 KB payloads through here per chunk.</p>
     */
    public static Result resliceTolerant(byte[] in, int inSections, int shiftSections, int outSections) {
        int limit = Math.max(0, inSections);
        // lengths[i] is the byte size of source section i; starts[i] its offset (0 = unparsed stops the walk).
        int[] lengths = new int[limit];
        int[] starts = new int[limit];
        int pos = 0;
        int parsed = 0;
        boolean anomaly = false;
        for (int i = 0; i < limit; i++) {
            int len;
            try {
                len = sectionLength(in, pos);
            } catch (FormatException e) {
                anomaly = true;
                break;
            }
            starts[i] = pos;
            lengths[i] = len;
            pos += len;
            parsed = i + 1;
        }
        if (!anomaly && pos != in.length) {
            anomaly = true; // trailing bytes after the declared sections
        }
        // Exact output size, then straight copies at computed offsets.
        int outLen = 0;
        for (int w = 0; w < outSections; w++) {
            int src = w + shiftSections;
            outLen += (src >= 0 && src < parsed) ? lengths[src] : EMPTY_SECTION.length;
        }
        byte[] out = new byte[Math.max(0, outLen)];
        int outPos = 0;
        for (int w = 0; w < outSections; w++) {
            int src = w + shiftSections;
            if (src >= 0 && src < parsed) {
                int len = lengths[src];
                System.arraycopy(in, starts[src], out, outPos, len);
                outPos += len;
            } else {
                System.arraycopy(EMPTY_SECTION, 0, out, outPos, EMPTY_SECTION.length);
                outPos += EMPTY_SECTION.length;
            }
        }
        return new Result(out, parsed, anomaly);
    }

    private static int skipPalette(byte[] data, int pos, int maxListBits) {
        int bitsPerEntry = readUnsignedByte(data, pos++);
        if (bitsPerEntry == 0) {
            // Singleton palette: one state id, no storage. One scan.
            return skipVarint(data, pos);
        }
        if (bitsPerEntry <= maxListBits) {
            long count = readVarintScan(data, pos);
            pos = (int) (count >>> 32);
            int paletteEntries = (int) count;
            for (int i = 0; i < paletteEntries; i++) {
                pos = skipVarint(data, pos);
            }
        }
        // Fixed-size long array storage.
        long longs = readVarintScan(data, pos);
        pos = (int) (longs >>> 32);
        long bytes = (long) ((int) longs) * 8L;
        if (bytes < 0 || bytes > data.length - pos) {
            throw new FormatException("storage array out of bounds");
        }
        return pos + (int) bytes;
    }

    private static int readUnsignedByte(byte[] data, int pos) {
        if (pos >= data.length || pos < 0) {
            throw new FormatException("unexpected end of payload");
        }
        return data[pos] & 0xFF;
    }

    /** @return the byte index just past the varint at {@code pos} (one scan, value discarded). */
    private static int skipVarint(byte[] data, int pos) {
        int start = pos;
        while ((readUnsignedByte(data, pos) & 0x80) != 0) {
            pos++;
            if (pos - start > 4) {
                throw new FormatException("varint too long");
            }
        }
        return pos + 1;
    }

    /**
     * Single-scan varint: {@code (newPos << 32) | value}. The decode+rescan pair an earlier version
     * used doubled the byte visits for every palette entry of every section of every chunked windowed.
     */
    private static long readVarintScan(byte[] data, int pos) {
        int value = 0;
        int read = 0;
        while (true) {
            int b = readUnsignedByte(data, pos + read);
            value |= (b & 0x7F) << (7 * read);
            read++;
            if (read > 5) {
                throw new FormatException("varint too long");
            }
            if ((b & 0x80) == 0) {
                return ((long) (pos + read) << 32) | (value & 0xFFFFFFFFL);
            }
        }
    }

    /** @return the decoded value; throws {@link FormatException} on malformed input. */
    public static int readVarint(byte[] data, int pos) {
        return (int) readVarintScan(data, pos);
    }

    /** Number of bytes the varint at {@code pos} occupies. */
    public static int varintLength(byte[] data, int pos) {
        return skipVarint(data, pos) - pos;
    }
}
