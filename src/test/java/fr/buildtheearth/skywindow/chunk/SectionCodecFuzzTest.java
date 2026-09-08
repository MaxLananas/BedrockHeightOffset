package fr.buildtheearth.skywindow.chunk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk path must never throw, whatever bytes arrive: a parse failure mid-flight would take a
 * Bedrock client's connection down. These tests hit the tolerant re-slicer with pure-random and
 * structured-but-truncated payloads and assert the invariant: a well-formed output of exactly the
 * requested section count, an anomaly flag instead of an exception, and byte-identical preservation
 * of every section that did parse.
 */
class SectionCodecFuzzTest {

    /** Walk {@code out} with the strict section parser; any garbage makes this throw and fail the test. */
    private static void assertWellFormed(byte[] out, int expectedSections) {
        int pos = 0;
        int count = 0;
        while (pos < out.length) {
            int len = SectionCodec.sectionLength(out, pos);
            assertTrue(len > 0, "section parser must advance");
            pos += len;
            count++;
        }
        assertEquals(out.length, pos, "sections must tile the payload exactly");
        assertEquals(expectedSections, count);
    }

    @Test
    void randomBytesNeverThrow() {
        Random rnd = new Random(1234);
        for (int trial = 0; trial < 500; trial++) {
            byte[] junk = new byte[rnd.nextInt(1, 2048)];
            rnd.nextBytes(junk);
            int inSections = rnd.nextInt(1, 26);
            int outSections = rnd.nextInt(1, 26);
            int shift = rnd.nextInt(-30, 30);
            SectionCodec.Result r =
                assertDoesNotThrow(() -> SectionCodec.resliceTolerant(junk, inSections, shift, outSections));
            assertWellFormed(r.data(), outSections);
        }
    }

    @Test
    void truncatedStructuredSectionPadsWithAir() {
        byte[] good = SectionCodecTest.singletonSection(5, 0);
        byte[] payload = new byte[good.length + 6];
        System.arraycopy(good, 0, payload, 0, good.length);
        // half-written second section: block count 0, fluid count 0, then bits-per-entry 9 with no data
        payload[good.length + 4] = 9;

        SectionCodec.Result r = SectionCodec.resliceTolerant(payload, 2, 0, 2);
        assertTrue(r.anomaly(), "truncated tail must flag anomaly");
        assertWellFormed(r.data(), 2);

        // the section that parsed survives byte-identical; the broken tail slot is canonical air
        byte[] preserved = Arrays.copyOfRange(r.data(), 0, good.length);
        assertArrayEquals(good, preserved);
        assertArrayEquals(SectionCodec.EMPTY_SECTION,
            Arrays.copyOfRange(r.data(), good.length, good.length + SectionCodec.EMPTY_SECTION.length));
    }

    @Test
    void deepShiftOutsideSourceYieldsAllAir() {
        byte[] one = SectionCodecTest.singletonSection(5, 0);
        SectionCodec.Result r = SectionCodec.resliceTolerant(one, 1, 100, 16);
        // walking off the source is the normal world-edge case, not a payload anomaly:
        assertFalse(r.anomaly(), "shift beyond the source must pad air silently");
        assertEquals(1, r.sectionsParsed());
        byte[] expected = new byte[16 * SectionCodec.EMPTY_SECTION.length];
        for (int i = 0; i < 16; i++) {
            System.arraycopy(SectionCodec.EMPTY_SECTION, 0, expected,
                i * SectionCodec.EMPTY_SECTION.length, SectionCodec.EMPTY_SECTION.length);
        }
        assertArrayEquals(expected, r.data());
    }

    @Test
    void validMultiSectionPayloadShiftsLosslessly() {
        Random rnd = new Random(99);
        int sections = 24;
        byte[][] src = new byte[sections][];
        for (int i = 0; i < sections; i++) {
            src[i] = SectionCodecTest.singletonSection(1 + rnd.nextInt(120), rnd.nextInt(64));
        }
        byte[] payload = SectionCodecTest.concat(src);
        SectionCodec.Result r = SectionCodec.resliceTolerant(payload, sections, 4, sections);
        assertFalse(r.anomaly());

        int outPos = 0;
        for (int w = 0; w < sections; w++) {
            int len = SectionCodec.sectionLength(r.data(), outPos);
            if (w + 4 < sections) {
                assertArrayEquals(src[w + 4],
                    Arrays.copyOfRange(r.data(), outPos, outPos + len), "section " + w);
            } else {
                assertEquals(SectionCodec.EMPTY_SECTION.length, len);
                assertArrayEquals(SectionCodec.EMPTY_SECTION,
                    Arrays.copyOfRange(r.data(), outPos, outPos + len), "tail " + w);
            }
            outPos += len;
        }
        assertEquals(r.data().length, outPos);
    }

    @Test
    void zeroInputSectionsStillProducesRequestedOutput() {
        SectionCodec.Result r = SectionCodec.resliceTolerant(new byte[0], 0, 0, 3);
        assertFalse(r.anomaly()); // declared zero sections, zero bytes: consistent, just empty input
        assertWellFormed(r.data(), 3);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) {
            expected.writeBytes(SectionCodec.EMPTY_SECTION);
        }
        assertArrayEquals(expected.toByteArray(), r.data());
    }
}
