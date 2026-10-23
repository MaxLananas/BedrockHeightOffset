import io.github.maxlananas.hauteur.chunk.SectionCodec;
import java.io.ByteArrayOutputStream;
import java.util.Random;

/** Synthetic benchmark of SectionCodec.resliceTolerant on realistic 24-section overworld payloads. */
public final class ReliceBench {
    static void varint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    /** One section: counts, block palette (bits, explicit id list, storage), biome palette. */
    static void section(ByteArrayOutputStream o, Random r, boolean dense) {
        o.write(0); o.write(16);          // blockCount (short, big endian)
        o.write(0); o.write(0);           // fluidCount
        int blockBits = dense ? 8 : 4;    // <= 8 means an explicit palette id list
        int paletteSize = dense ? 40 : 12;
        o.write(blockBits);
        varint(o, paletteSize);
        for (int i = 0; i < paletteSize; i++) varint(o, 1 + r.nextInt(3000));
        int longs = (256 * blockBits + 63) / 64;
        varint(o, longs);
        for (int i = 0; i < longs * 8; i++) o.write(r.nextInt(256));
        // biome palette: 4x4x4 cells; the list-bits limit is 3, so 3-bit palettes carry an id list
        if (dense) {
            o.write(3); varint(o, 4); for (int i = 0; i < 4; i++) varint(o, 1 + r.nextInt(60));
            varint(o, 3); for (int i = 0; i < 24; i++) o.write(r.nextInt(256));
        } else {
            o.write(0); varint(o, 1);     // singleton biome
        }
    }

    static byte[] payload(Random r, int sections, boolean dense) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int s = 0; s < sections; s++) section(o, r, dense);
        return o.toByteArray();
    }

    public static void main(String[] args) {
        int sections = 24;
        int shift = 8;
        int iterations = Integer.parseInt(args.length > 0 ? args[0] : "4000");
        for (boolean dense : new boolean[] {false, true}) {
            byte[] in = payload(new Random(42), sections, dense);
            // sanity: the codec must see exactly the sections we built, with no anomaly
            SectionCodec.Result check = SectionCodec.resliceTolerant(in, sections, shift, sections);
            if (check.anomaly() || check.sectionsParsed() != sections) {
                throw new IllegalStateException("generator and codec disagree: " + check);
            }
            long sink = 0;
            for (int i = 0; i < iterations / 4; i++) sink += SectionCodec.resliceTolerant(in, sections, shift, sections).data().length;
            long t0 = System.nanoTime();
            for (int i = 0; i < iterations; i++) sink += SectionCodec.resliceTolerant(in, sections, shift, sections).data().length;
            long dt = System.nanoTime() - t0;
            double usPerChunk = dt / 1000.0 / iterations;
            System.out.printf("%-6s payload=%6d B  per-chunk=%8.2f us  (%.0f MB/s in)  full-cache(2048)=%7.1f ms%n",
                dense ? "dense" : "sparse", in.length, usPerChunk,
                in.length / (usPerChunk), usPerChunk * 2048 / 1000.0);
            if (sink == 42) System.out.println();
        }
    }
}
