package fr.buildtheearth.skywindow.chunk;

import java.io.ByteArrayOutputStream;

/**
 * Byte-level walker over the section array of a Java {@code ClientboundLevelChunkWithLight} payload.
 *
 * <p>Payload layout (1.18+): {@code sectionCount} consecutive sections in ascending world order, each
 * one being {@code short blockCount, short fluidCount, dataPalette(blocks), dataPalette(biomes)}.
 * A data palette is {@code ubyte bitsPerEntry}; 0 means a singleton palette (one varint id, no
 * storage); otherwise an optional varint-prefixed palette id list (absent for global palettes,
 * i.e. when bitsPerEntry exceeds the palette type's maximum) followed by fixed-size long-array
 * storage (varint long count, then that many 8-byte longs). This mirrors
 * {@code MinecraftTypes.readChunkSection}/{@code writeDataPalette} of mcprotocollib.</p>
 *
 * <p>Reslicing a windowed chunk therefore only needs the byte extent of each section, not the block
 * data itself: sections are moved (or synthesized as empty) as whole byte ranges, so palettes,
 * waterlogged re-encoding and block counts all keep working inside Geyser unchanged.</p>
 *
 * <p>{@link #resliceTolerant} never throws: it walks as many sections as it can parse, pads or
 * truncates to the exact section count Geyser's translator will loop over, and flags the anomaly.
 * Malformed sections degrade to air (a visual hole) instead of letting Geyser read past the end of
 * the payload into the light data - which on some Geyser paths is a decode exception on a packet
 * thread, i.e. a disconnect. Robustness here is not optional.</p>
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
     */
    public static Result resliceTolerant(byte[] in, int inSections, int shiftSections, int outSections) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, in.length));
        int anomalyAt = -1;
        int parsed = 0;
        int pos = 0;
        int limit = Math.max(0, inSections);
        // offsets[i] and length of each source section, computed lazily while streaming the walk;
        // we index by source section number, so buffer the (start,end) pairs for the range we parse.
        int[] starts = new int[limit + 1];
        int[] ends = new int[limit + 1];
        for (int i = 0; i < limit; i++) {
            starts[i] = pos;
            int len;
            try {
                len = sectionLength(in, pos);
            } catch (FormatException e) {
                anomalyAt = i;
                break;
            }
            ends[i] = pos + len;
            pos += len;
            parsed = i + 1;
        }
        if (anomalyAt < 0 && pos != in.length && parsed == limit) {
            anomalyAt = limit; // trailing bytes after the declared sections
        }
        for (int w = 0; w < outSections; w++) {
            int src = w + shiftSections;
            if (src >= 0 && src < parsed) {
                out.write(in, starts[src], ends[src] - starts[src]);
            } else {
                out.write(EMPTY_SECTION, 0, EMPTY_SECTION.length);
            }
        }
        return new Result(out.toByteArray(), parsed, anomalyAt >= 0);
    }

    private static int skipPalette(byte[] data, int pos, int maxListBits) {
        int bitsPerEntry = readUnsignedByte(data, pos++);
        if (bitsPerEntry == 0) {
            // Singleton palette: one state id, no storage.
            skipVarint(data, pos);
            return varintLength(data, pos) + pos;
        }
        if (bitsPerEntry <= maxListBits) {
            int count = readVarint(data, pos);
            pos = pos + varintLength(data, pos);
            for (int i = 0; i < count; i++) {
                skipVarint(data, pos);
                pos += varintLength(data, pos);
            }
        }
        // Fixed-size long array storage.
        int longCount = readVarint(data, pos);
        pos += varintLength(data, pos);
        long bytes = (long) longCount * 8L;
        if (bytes < 0 || bytes > data.length - pos) {
            throw new FormatException("storage array out of bounds");
        }
        return pos + (int) bytes;
    }

    private static int readUnsignedByte(byte[] data, int pos) {
        if (pos >= data.length) {
            throw new FormatException("unexpected end of payload");
        }
        return data[pos] & 0xFF;
    }

    private static void skipVarint(byte[] data, int pos) {
        readVarint(data, pos);
    }

    /** @return the decoded value; throws {@link FormatException} on malformed input. */
    public static int readVarint(byte[] data, int pos) {
        int value = 0;
        int bytesRead = 0;
        while (true) {
            int b = readUnsignedByte(data, pos + bytesRead);
            value |= (b & 0x7F) << (7 * bytesRead);
            bytesRead++;
            if (bytesRead > 5) {
                throw new FormatException("varint too long");
            }
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    /** Number of bytes the varint at {@code pos} occupies. */
    public static int varintLength(byte[] data, int pos) {
        int len = 0;
        while ((readUnsignedByte(data, pos + len) & 0x80) != 0) {
            len++;
            if (len > 4) {
                throw new FormatException("varint too long");
            }
        }
        return len + 1;
    }
}
