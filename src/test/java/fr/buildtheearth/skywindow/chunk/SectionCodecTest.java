package fr.buildtheearth.skywindow.chunk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SectionCodecTest {

    static byte[] singletonSection(int blockId, int biomeId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {0, 0, 0, 0}, 0, 4); // block count, fluid count
        out.write(0); // bits per entry = 0 -> singleton
        out.write(blockId & 0x7F); // varint (only valid for ids < 128 in this helper)
        out.write(0);
        out.write(biomeId & 0x7F);
        return out.toByteArray();
    }

    static byte[] listPaletteSection(int[] paletteIds, int[] storage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {8, 0, 0, 0}, 0, 4); // block count 8
        out.write(4); // bits per entry (<= 4 for blocks => list palette)
        out.write(paletteIds.length); // palette length (helper: <128)
        for (int id : paletteIds) {
            out.write(id & 0x7F);
        }
        out.write(storage.length); // storage long count (helper: <128)
        for (int unused : storage) {
            out.write(new byte[8], 0, 8);
        }
        // biome palette: singleton plains
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    static byte[] globalPaletteSection(int longCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {0, 1, 0, 0}, 0, 4); // fluid count 256?? value not parsed, just preserved
        out.write(9); // block bits > max palette bits (8) => global palette, no id list
        writeVarInt(out, longCount);
        for (int i = 0; i < longCount; i++) {
            out.write(new byte[8], 0, 8);
        }
        out.write(0); // biome singleton
        out.write(0);
        return out.toByteArray();
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    @Test
    void emptySectionIsWellFormedAndDecodesToLength() {
        assertEquals(8, SectionCodec.sectionLength(SectionCodec.EMPTY_SECTION, 0));
    }

    @Test
    void singletonAndListSectionsHaveExactLengths() {
        byte[] s = singletonSection(0, 0);
        assertEquals(s.length, SectionCodec.sectionLength(s, 0));
        byte[] l = listPaletteSection(new int[] {0, 12}, new int[3]);
        assertEquals(l.length, SectionCodec.sectionLength(l, 0));
        byte[] g = globalPaletteSection(256); // full block storage
        assertEquals(g.length, SectionCodec.sectionLength(g, 0));
    }

    @Test
    void resliceMovesSectionsAndPadsEmpties() {
        byte[] a = singletonSection(5, 0);
        byte[] b = singletonSection(6, 0);
        byte[] c = singletonSection(7, 0);
        byte[] payload = concat(a, b, c);

        byte[] shifted = SectionCodec.resliceTolerant(payload, 3, 2, 5).data();
        // Production convention (offset lifts real terrain down into the window): out[w] = in[w + 2],
        // so slot 0 shows source section 2 (c) and every higher slot walks off the source: empties.
        int pos = 0;
        assertArrayEquals(c, Arrays.copyOfRange(shifted, pos, pos + c.length));
        pos += c.length;
        for (int i = 1; i < 5; i++) {
            assertArrayEquals(SectionCodec.EMPTY_SECTION, Arrays.copyOfRange(shifted, pos, pos + 8));
            pos += 8;
        }
        assertEquals(shifted.length, pos);
    }

    @Test
    void resliceDropsContentThatLeavesTheWindow() {
        byte[] payload = concat(singletonSection(5, 0), singletonSection(6, 0));
        byte[] shifted = SectionCodec.resliceTolerant(payload, 2, -1, 2).data();
        // out[0] = in[-1] dropped -> empty; out[1] = in[0] = the first original section
        assertArrayEquals(SectionCodec.EMPTY_SECTION, Arrays.copyOfRange(shifted, 0, 8));
        assertArrayEquals(singletonSection(5, 0), Arrays.copyOfRange(shifted, 8, 16));
    }

    @Test
    void truncatedSectionLengthThrows() {
        byte[] s = listPaletteSection(new int[] {0, 12}, new int[3]);
        byte[] cut = Arrays.copyOf(s, s.length - 4);
        assertThrows(SectionCodec.FormatException.class, () -> SectionCodec.sectionLength(cut, 0));
    }

    @Test
    void hugeStorageCountThrows() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {0, 0, 0, 0}, 0, 4);
        out.write(9); // global block palette
        writeVarInt(out, 1 << 24); // absurd long count
        assertThrows(SectionCodec.FormatException.class,
            () -> SectionCodec.sectionLength(out.toByteArray(), 0));
    }

    @Test
    void tolerantResliceNeverThrowsAndAlwaysMatchesExpectedCount() {
        byte[] two = concat(singletonSection(1, 0), singletonSection(2, 0));

        // short payload: pads the rest with canonical air, flags anomaly, output still walks clean
        SectionCodec.Result shortRes = SectionCodec.resliceTolerant(Arrays.copyOf(two, two.length - 3), 4, 1, 4);
        assertTrue(shortRes.anomaly());
        assertEquals(4, countSections(shortRes.data(), 4));

        // long payload (trailing junk): declared sections honored, anomaly flagged, no junk emitted
        SectionCodec.Result longRes = SectionCodec.resliceTolerant(concat(two, new byte[] {7, 7, 7}), 2, 0, 2);
        assertTrue(longRes.anomaly());
        assertArrayEquals(two, longRes.data());

        // clean payload: no anomaly
        SectionCodec.Result ok = SectionCodec.resliceTolerant(two, 2, 0, 2);
        assertFalse(ok.anomaly());
        assertEquals(2, ok.sectionsParsed());
    }

    private static int countSections(byte[] payload, int cap) {
        int pos = 0;
        int n = 0;
        while (pos < payload.length && n < cap) {
            pos += SectionCodec.sectionLength(payload, pos);
            n++;
        }
        assertEquals(payload.length, pos);
        return n;
    }

    @Test
    void trailingBytesFlaggedButContentKept() {
        byte[] a = singletonSection(0, 0);
        byte[] b = singletonSection(4, 0);
        byte[] dirty = Arrays.copyOf(concat(a, b), a.length + b.length + 3);
        SectionCodec.Result res = SectionCodec.resliceTolerant(dirty, 2, 1, 3);
        assertTrue(res.anomaly());
        // out[0] = in[1] = b; out[1..2] empty; the trailing junk is NEVER emitted downstream
        byte[] expect = concat(b, SectionCodec.EMPTY_SECTION, SectionCodec.EMPTY_SECTION);
        assertArrayEquals(expect, res.data());
    }

    @Test
    void reslicePreservesBytesOfMovedSections() {
        Random rnd = new Random(42);
        byte[] payload = concat(globalPaletteSection(256), singletonSection(3, 0), listPaletteSection(new int[] {0, 1}, new int[16]));
        byte[] out = SectionCodec.resliceTolerant(payload, 3, 1, 4).data();
        // out[w] = in[w + 1]: slot 0 gets source section 1 (the singleton), slot 1 gets source 2 (the
        // list section), slots 2..3 pad with empty; the 256-long global section leaves the window.
        byte[] moved1 = singletonSection(3, 0);
        byte[] moved2 = listPaletteSection(new int[] {0, 1}, new int[16]);
        assertArrayEquals(moved1, Arrays.copyOfRange(out, 0, moved1.length));
        assertArrayEquals(moved2, Arrays.copyOfRange(out, moved1.length, moved1.length + moved2.length));
        assertArrayEquals(SectionCodec.EMPTY_SECTION,
            Arrays.copyOfRange(out, moved1.length + moved2.length, moved1.length + moved2.length + 8));
    }
}
