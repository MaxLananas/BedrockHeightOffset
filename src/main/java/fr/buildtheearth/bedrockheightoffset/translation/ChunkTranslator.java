package fr.buildtheearth.bedrockheightoffset.translation;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Translates Bedrock chunk/block packets by applying the Y offset.
 *
 * LevelChunkPacket contains serialized sub-chunk data.
 * Each sub-chunk has a header byte that encodes its index (Y section).
 * The sub-chunk index stored in the header = (worldY >> 4) relative to dimension minY.
 *
 * With offset applied:
 *   bedrockSubChunkIndex = javaSubChunkIndex - (offset >> 4)
 *
 * We rewrite the sub-chunk index bytes in the raw ByteBuf payload.
 * The format for each sub-chunk (version 8+):
 *   byte  version  (8 or 9)
 *   byte  storageCount
 *   byte  subChunkIndex   ← this is what we rewrite
 *   ... block palette + data ...
 *
 * For UpdateBlockPacket and BlockEntityDataPacket, we rewrite the Y
 * coordinate in the Vector3i position field.
 */
public class ChunkTranslator {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private final BedrockHeightOffset plugin;

    // Reflection cache
    private static final Map<String, Field>  fieldCache  = new ConcurrentHashMap<>();
    private static final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    public ChunkTranslator(BedrockHeightOffset plugin) {
        this.plugin = plugin;
    }

    // ── LevelChunkPacket ─────────────────────────────────────────────────────

    public Object translateChunk(Object packet, PlayerOffsetData data) {
        try {
            int offsetSections = data.getOffset() >> 4;
            if (offsetSections == 0) return packet;

            // Access the ByteBuf payload
            // LevelChunkPacket.data is a ByteBuf (io.netty.buffer.ByteBuf)
            Object buf = getField(packet, "data");
            if (buf == null) return packet;

            // We work on a copy to avoid corrupting Geyser's internal state
            Object copy = invokeCopy(buf);
            if (copy == null) return packet;

            rewriteSubChunkIndices(copy, offsetSections);

            setField(packet, "data", copy);

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateChunk error: " + e.getMessage());
        }
        return packet;
    }

    /**
     * Rewrites sub-chunk index bytes inside the raw serialized chunk ByteBuf.
     *
     * Bedrock sub-chunk format (protocol version 475+, used in 1.21.x):
     *   Each sub-chunk starts with:
     *     byte: version (8 = paletted, 9 = paletted with index, 1 = legacy)
     *   If version == 8:
     *     byte: storageCount
     *     byte: subChunkIndex   ← Y index of this sub-chunk in the dimension
     *   If version == 9:
     *     byte: storageCount
     *     byte: subChunkIndex
     *
     * We scan the buffer sequentially. For each sub-chunk, we subtract
     * (offset >> 4) from the subChunkIndex byte.
     *
     * The subChunkIndex is a SIGNED byte representing the sub-chunk's
     * position relative to the dimension's minY / 16.
     * For overworld: index -4 = Y=-64, index 0 = Y=0, index 19 = Y=304
     *
     * With offset=512 (javaY≈640, bedrockY≈128):
     *   java subchunk 40 (Y=640) → bedrock subchunk 40-32 = 8 (Y=128) ✓
     */
    private void rewriteSubChunkIndices(Object buf, int offsetSections) throws Exception {
        Class<?> bufClass = buf.getClass().getSuperclass(); // AbstractByteBuf
        while (!bufClass.getSimpleName().equals("AbstractByteBuf")
               && !bufClass.getSimpleName().equals("Object")) {
            bufClass = bufClass.getSuperclass();
        }

        // Use ByteBuf methods via reflection since we can't import cloudburstmc classes at compile time
        Method readableBytes = buf.getClass().getMethod("readableBytes");
        Method getByte       = buf.getClass().getMethod("getByte", int.class);
        Method setByte       = buf.getClass().getMethod("setByte", int.class, int.class);
        Method readerIndex   = buf.getClass().getMethod("readerIndex");

        int startIdx = (int) readerIndex.invoke(buf);
        int readable  = (int) readableBytes.invoke(buf);

        int pos = startIdx;
        int end = startIdx + readable;

        while (pos < end) {
            int version = ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;

            if (version == 8 || version == 9) {
                if (pos + 2 >= end) break;
                // storageCount at pos+1, subChunkIndex at pos+2
                int storageCount = ((Number) getByte.invoke(buf, pos + 1)).intValue() & 0xFF;
                int currentIndex = ((Number) getByte.invoke(buf, pos + 2)).intValue();

                // currentIndex is signed byte
                if (currentIndex > 127) currentIndex -= 256;

                int newIndex = currentIndex - offsetSections;
                setByte.invoke(buf, pos + 2, newIndex & 0xFF);

                plugin.getPluginConfig().debugLog(
                    "Sub-chunk index rewritten: " + currentIndex + " → " + newIndex
                );

                // Skip past this sub-chunk header; we can't easily skip the full body
                // without a full palette parser, but the indices are the only thing we need.
                // Move to next sub-chunk: we'd need to skip the full storage data.
                // Since we don't have a full parser here, we scan for the next version byte.
                // This works because version bytes (1, 8, 9) are distinctive enough.
                pos += 3;

                // Skip over storageCount block storages
                // Each block storage: 1 byte (bits per block) + palette data
                // We do a best-effort scan rather than full parse
                pos = skipBlockStorages(buf, pos, storageCount, end, getByte);

            } else if (version == 1) {
                // Legacy format: 4096 bytes of block data + 2048 bytes metadata
                pos += 1 + 4096 + 2048;
            } else {
                // Unknown/air section or padding — advance one byte and resync
                pos++;
            }
        }
    }

    /**
     * Skips over `count` block storage entries in the buffer.
     * Block storage format:
     *   byte: bitsPerBlock | (isRuntime << 1)
     *   if bitsPerBlock > 0:
     *     ceil(4096 / (32/bitsPerBlock)) * 4 bytes of word data
     *     varint: paletteSize
     *     paletteSize * varint entries
     */
    private int skipBlockStorages(Object buf, int pos, int count, int end, Method getByte) throws Exception {
        for (int i = 0; i < count && pos < end; i++) {
            int header    = ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
            int bitsPerBlock = header >> 1;
            pos++;

            if (bitsPerBlock == 0x7F) {
                // Singleton palette — no data, just 1 varint
                pos = skipVarint(buf, pos, end, getByte);
                continue;
            }

            if (bitsPerBlock > 0) {
                int blocksPerWord = 32 / bitsPerBlock;
                int wordCount     = (int) Math.ceil(4096.0 / blocksPerWord);
                pos += wordCount * 4;
            }

            // Read palette size varint
            int[] result  = readVarint(buf, pos, end, getByte);
            int paletteSize = result[0];
            pos = result[1];

            // Skip palette entries (each is a varint)
            for (int j = 0; j < paletteSize && pos < end; j++) {
                pos = skipVarint(buf, pos, end, getByte);
            }
        }
        return pos;
    }

    private int skipVarint(Object buf, int pos, int end, Method getByte) throws Exception {
        while (pos < end) {
            int b = ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
            pos++;
            if ((b & 0x80) == 0) break;
        }
        return pos;
    }

    private int[] readVarint(Object buf, int pos, int end, Method getByte) throws Exception {
        int value  = 0;
        int shift  = 0;
        while (pos < end) {
            int b = ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
            pos++;
            value |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) break;
        }
        return new int[]{value, pos};
    }

    // ── UpdateBlockPacket ─────────────────────────────────────────────────────

    public Object translateBlockUpdate(Object packet, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, "blockPosition");
            if (pos == null) return packet;

            int javaY    = ((Number) invokeMethod(pos, "getY")).intValue();
            int bedrockY = (int) data.toBedrockY(javaY);

            Object newPos = createVector3i(
                ((Number) invokeMethod(pos, "getX")).intValue(),
                bedrockY,
                ((Number) invokeMethod(pos, "getZ")).intValue()
            );

            setField(packet, "blockPosition", newPos);

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateBlockUpdate error: " + e.getMessage());
        }
        return packet;
    }

    // ── BlockEntityDataPacket ─────────────────────────────────────────────────

    public Object translateBlockEntity(Object packet, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, "blockPosition");
            if (pos == null) return packet;

            int javaY    = ((Number) invokeMethod(pos, "getY")).intValue();
            int bedrockY = (int) data.toBedrockY(javaY);

            Object newPos = createVector3i(
                ((Number) invokeMethod(pos, "getX")).intValue(),
                bedrockY,
                ((Number) invokeMethod(pos, "getZ")).intValue()
            );

            setField(packet, "blockPosition", newPos);

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateBlockEntity error: " + e.getMessage());
        }
        return packet;
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    static Object getField(Object obj, String name) throws Exception {
        String key = obj.getClass().getName() + "#" + name;
        Field f = fieldCache.computeIfAbsent(key, k -> {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field found = c.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return null;
        });
        return f != null ? f.get(obj) : null;
    }

    static void setField(Object obj, String name, Object value) throws Exception {
        String key = obj.getClass().getName() + "#" + name;
        Field f = fieldCache.computeIfAbsent(key, k -> {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field found = c.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return null;
        });
        if (f != null) f.set(obj, value);
    }

    static Object invokeMethod(Object obj, String name) throws Exception {
        String key = obj.getClass().getName() + "#" + name;
        Method m = methodCache.computeIfAbsent(key, k -> {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Method found = c.getDeclaredMethod(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
            return null;
        });
        return m != null ? m.invoke(obj) : null;
    }

    private Object invokeCopy(Object buf) throws Exception {
        Method copy = buf.getClass().getMethod("copy");
        return copy.invoke(buf);
    }

    private Object createVector3i(int x, int y, int z) throws Exception {
        Class<?> vec3iClass = Class.forName("org.cloudburstmc.math.vector.Vector3i");
        Method from = vec3iClass.getMethod("from", int.class, int.class, int.class);
        return from.invoke(null, x, y, z);
    }
}