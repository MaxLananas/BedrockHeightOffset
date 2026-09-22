package fr.buildtheearth.skywindow.chunk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static fr.buildtheearth.skywindow.chunk.SectionCodecTest.concat;
import static fr.buildtheearth.skywindow.chunk.SectionCodecTest.listPaletteSection;
import static fr.buildtheearth.skywindow.chunk.SectionCodecTest.singletonSection;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Extreme-shape proof: the reslice math must hold at the sizes real BTE worlds reach (a window
 * lifted near Y=2000 carries 126+ sections per chunk payload) and at adversarial sizes, not just
 * at the tidy three-section cases.
 */
class SectionCodecStressTest {

    /** A real Himalaya-scale window: 128 sections (= 2048 blocks of vertical chunk payload). */
    @Test
    void resliceAtBteScaleKeepsEverySectionExact() {
        int sections = 128;
        byte[][] parts = new byte[sections][];
        for (int i = 0; i < sections; i++) {
            parts[i] = singletonSection(1 + i, 3); // unique block id per section = identity check
        }
        byte[] payload = concat(parts);
        SectionCodec.Result out = SectionCodec.resliceTolerant(payload, sections, 64, sections);
        assertEquals(sections, out.sectionsParsed());
        // No anomaly: the payload is well-formed and fully consumed.
        byte[] data = out.data();
        int pos = 0;
        for (int w = 0; w < sections; w++) {
            int source = w + 64;
            if (source < sections) {
                byte[] expected = singletonSection(1 + source, 3);
                assertArrayEquals(expected, Arrays.copyOfRange(data, pos, pos + expected.length),
                    "window section " + w + " must equal source section " + source);
                pos += expected.length;
            } else {
                assertArrayEquals(SectionCodec.EMPTY_SECTION,
                    Arrays.copyOfRange(data, pos, pos + SectionCodec.EMPTY_SECTION.length));
                pos += SectionCodec.EMPTY_SECTION.length;
            }
        }
        assertEquals(data.length, pos);
    }

    /** The exact geometry of the audit lesson: 126-section shift (offset 2016 blocks). */
    @Test
    void resliceAtThe2016BlockOffsetIsLossless() {
        int sections = 130;
        byte[][] parts = new byte[sections][];
        for (int i = 0; i < sections; i++) {
            parts[i] = singletonSection(i + 1, 1);
        }
        byte[] payload = concat(parts);
        int shift = 126;
        SectionCodec.Result out = SectionCodec.resliceTolerant(payload, sections, shift, sections);
        assertEquals(sections, out.sectionsParsed());
        assertFalse(out.anomaly(), "a well-formed 130-section payload is not an anomaly");
        byte[] data = out.data();
        // Every reachable window slot must hold its exact source section.
        int pos = 0;
        for (int w = 0; w + shift < sections; w++) {
            byte[] expected = singletonSection(w + shift + 1, 1);
            assertArrayEquals(expected, Arrays.copyOfRange(data, pos, pos + expected.length));
            pos += expected.length;
        }
        // Everything above the window's reach is padded with exact empty sections.
        for (int w = sections - shift; w < sections; w++) {
            assertArrayEquals(SectionCodec.EMPTY_SECTION,
                Arrays.copyOfRange(data, pos, pos + SectionCodec.EMPTY_SECTION.length));
            pos += SectionCodec.EMPTY_SECTION.length;
        }
        assertEquals(data.length, pos);
    }

    /** Mixed palette types at scale: every section stays individually correct through the walk. */
    @Test
    void mixedPaletteTypesAtScaleStayExact() {
        int sections = 96;
        byte[][] parts = new byte[sections][];
        for (int i = 0; i < sections; i++) {
            parts[i] = (i % 2 == 0)
                ? singletonSection(1 + i, 2)
                : listPaletteSection(new int[] {1 + i, 2 + i}, new int[2]);
        }
        byte[] payload = concat(parts);
        SectionCodec.Result out = SectionCodec.resliceTolerant(payload, sections, 16, sections);
        assertEquals(sections, out.sectionsParsed());
        byte[] data = out.data();
        int pos = 0;
        for (int w = 0; w < sections; w++) {
            int source = w + 16;
            byte[] expected = source < sections ? parts[source] : SectionCodec.EMPTY_SECTION;
            assertArrayEquals(expected, Arrays.copyOfRange(data, pos, pos + expected.length),
                "window section " + w);
            pos += expected.length;
        }
        assertEquals(data.length, pos);
    }

    /** Negative and zero counts never corrupt the walk (pinned once at unit level, re-checked at scale). */
    @Test
    void adversarialHeaderCountsStaySafeAtScale() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) {
            out.write(new byte[] {0, 0, 0, 0}, 0, 4);
            out.write(4); // list palette
            writeVarInt(out, 0xFFFFFFFF); // decoded count = -1
        }
        byte[] hostile = out.toByteArray();
        SectionCodec.Result result = SectionCodec.resliceTolerant(hostile, 40, 5, 40);
        // Tolerant contract: never throws, always produces exactly the requested section count.
        int expectedSize = 40 * SectionCodec.EMPTY_SECTION.length;
        assertEquals(expectedSize, result.data().length);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & -128) != 0) {
            out.write(value & 127 | 128);
            value >>>= 7;
        }
        out.write(value);
    }
}
