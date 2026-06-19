package fr.buildtheearth.bedrockheightoffset.translation;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Translates Bedrock chunk packets by rewriting sub-chunk index bytes.
 *
 * Sub-chunk binary format (Bedrock protocol, used since 1.16+):
 *
 *   For each sub-chunk in LevelChunkPacket:
 *     byte  version          — 8 = paletted (no index), 9 = paletted (with index)
 *     byte  storageCount     — number of block storage layers (usually 1 or 2)
 *     byte  subChunkIndex    — ONLY present in version 9; signed byte
 *     [storageCount block storages follow]
 *
 *   Block storage:
 *     byte  header           — bits-per-block encoded as (bitsPerBlock << 1) | isNetworkRuntime
 *     if bitsPerBlock == 0x7F (127): singleton palette, no word data
 *       varint runtimeId
 *     else if bitsPerBlock > 0:
 *       wordCount = ceil(4096 / floor(32/bitsPerBlock)) words × 4 bytes each
 *       varint paletteSize
 *       paletteSize × varint runtimeIds
 *
 * The subChunkIndex (version 9 only) is the only field we need to rewrite.
 * It represents the sub-chunk's Y position in the dimension:
 *   index = (worldY >> 4) - (dimensionMinY >> 4)
 *
 * With our sliding window:
 *   bedrockIndex = javaIndex - offsetSections
 *
 * For version 8, Geyser doesn't include the index byte; the client infers
 * the sub-chunk position from the packet's subChunksLength and the order
 * of sub-chunks. We don't need to do anything for version 8.
 */
public class ChunkTranslator {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private final BedrockHeightOffset plugin;

    static final Map<String, Field>  FIELD_CACHE  = new ConcurrentHashMap<>();
    static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    public ChunkTranslator(BedrockHeightOffset plugin) {
        this.plugin = plugin;
    }

    public Object translateChunk(Object packet, PlayerOffsetData data) {
        int offsetSections = data.offsetSections();
        if (offsetSections == 0) return packet;

        try {
            Object buf = getField(packet, "data");
            if (buf == null) return packet;

            // Work on a retained duplicate so we don't corrupt Geyser's buffer
            Method retainedDuplicate = buf.getClass().getMethod("retainedDuplicate");
            Object dup = retainedDuplicate.invoke(buf);

            boolean modified = rewriteSubChunkIndices(dup, offsetSections);

            if (modified) {
                // Release the old buffer reference in the packet and set our modified copy
                Method release = buf.getClass().getMethod("release");
                release.invoke(buf);
                setField(packet, "data", dup);
            } else {
                Method release = dup.getClass().getMethod("release");
                release.invoke(dup);
            }

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateChunk error: " + e.getMessage());
        }
        return packet;
    }

    /**
     * Scans the raw sub-chunk ByteBuf and rewrites every version-9 sub-chunk
     * index byte by subtracting offsetSections.
     *
     * @return true if at least one index was rewritten
     */
    private boolean rewriteSubChunkIndices(Object buf, int offsetSections) throws Exception {
        Class<?> bufClass = buf.getClass();

        Method readerIndex   = bufClass.getMethod("readerIndex");
        Method readableBytes = bufClass.getMethod("readableBytes");
        Method getByte       = bufClass.getMethod("getByte", int.class);
        Method setByte       = bufClass.getMethod("setByte", int.class, int.class);

        int pos   = (int) readerIndex.invoke(buf);
        int end   = pos + (int) readableBytes.invoke(buf);
        boolean modified = false;

        while (pos < end) {
            int version = readUByte(buf, getByte, pos);

            if (version == 9) {
                // version 9: has explicit sub-chunk index
                if (pos + 2 >= end) break;

                int storageCount = readUByte(buf, getByte, pos + 1);
                int rawIndex     = readSByte(buf, getByte, pos + 2); // signed
                int newIndex     = rawIndex - offsetSections;

                setByte.invoke(buf, pos + 2, newIndex & 0xFF);
                modified = true;

                plugin.getPluginConfig().debugLog(
                    "SubChunk v9: index " + rawIndex + " → " + newIndex
                    + " (offset=" + offsetSections + " sections)"
                );

                // Advance past header (3 bytes) + storageCount block storages
                pos = skipStorages(buf, getByte, pos + 3, end, storageCount);

            } else if (version == 8) {
                // version 8: no explicit index byte, order implies position
                // We don't need to rewrite anything; skip past the storage data
                if (pos + 1 >= end) break;
                int storageCount = readUByte(buf, getByte, pos + 1);
                pos = skipStorages(buf, getByte, pos + 2, end, storageCount);

            } else if (version == 1) {
                // Legacy format: 4096 block bytes + 2048 metadata bytes
                pos += 1 + 4096 + 2048;

            } else {
                // Unknown/padding — advance one byte and attempt resync
                pos++;
            }
        }

        return modified;
    }

    /**
     * Skips over `count` block storage blobs in the buffer.
     *
     * Block storage layout:
     *   byte: header = (bitsPerBlock << 1) | isNetworkRuntime
     *   if bitsPerBlock == 0x7F: varint runtimeId  (singleton palette)
     *   else if bitsPerBlock == 0: varint paletteSize=1, varint runtimeId
     *   else:
     *     wordCount = ceil(4096.0 / floor(32.0 / bitsPerBlock)) * 4 bytes
     *     varint paletteSize
     *     paletteSize * varint runtimeIds
     *
     * @return position after all storages
     */
    private int skipStorages(Object buf, Method getByte, int pos, int end, int count) throws Exception {
        for (int i = 0; i < count && pos < end; i++) {
            int header       = readUByte(buf, getByte, pos);
            int bitsPerBlock = header >> 1;
            pos++;

            if (bitsPerBlock == 0x7F) {
                // Singleton: just one varint runtime ID
                pos = skipVarint(buf, getByte, pos, end);
                continue;
            }

            if (bitsPerBlock == 0) {
                // Zero-bits: palette of size 1, one varint
                int[] r = readVarint(buf, getByte, pos, end);
                pos = r[1]; // skip paletteSize (should be 1)
                pos = skipVarint(buf, getByte, pos, end); // skip the single entry
                continue;
            }

            // Compute word data size
            int blocksPerWord = 32 / bitsPerBlock;
            int wordCount     = (int) Math.ceil(4096.0 / blocksPerWord);
            pos += wordCount * 4;

            // Read palette
            int[] r = readVarint(buf, getByte, pos, end);
            int paletteSize = r[0];
            pos = r[1];

            for (int j = 0; j < paletteSize && pos < end; j++) {
                pos = skipVarint(buf, getByte, pos, end);
            }
        }
        return pos;
    }

    public Object translateBlockUpdate(Object packet, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, "blockPosition");
            if (pos == null) return packet;

            int javaY    = iGet(invokeMethod(pos, "getY"));
            int bedrockY = (int) data.toBedrockY(javaY);

            setField(packet, "blockPosition", newVec3i(
                iGet(invokeMethod(pos, "getX")), bedrockY, iGet(invokeMethod(pos, "getZ"))
            ));
        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateBlockUpdate: " + e.getMessage());
        }
        return packet;
    }

    public Object translateBlockEntity(Object packet, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, "blockPosition");
            if (pos == null) return packet;

            int javaY    = iGet(invokeMethod(pos, "getY"));
            int bedrockY = (int) data.toBedrockY(javaY);

            setField(packet, "blockPosition", newVec3i(
                iGet(invokeMethod(pos, "getX")), bedrockY, iGet(invokeMethod(pos, "getZ"))
            ));
        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateBlockEntity: " + e.getMessage());
        }
        return packet;
    }

    private static int readUByte(Object buf, Method getByte, int pos) throws Exception {
        return ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
    }

    private static int readSByte(Object buf, Method getByte, int pos) throws Exception {
        int v = ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
        return v > 127 ? v - 256 : v;
    }

    private static int skipVarint(Object buf, Method getByte, int pos, int end) throws Exception {
        while (pos < end) {
            int b = ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
            pos++;
            if ((b & 0x80) == 0) break;
        }
        return pos;
    }

    private static int[] readVarint(Object buf, Method getByte, int pos, int end) throws Exception {
        int value = 0, shift = 0;
        while (pos < end) {
            int b = ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
            pos++;
            value |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) break;
        }
        return new int[]{value, pos};
    }

    static Object getField(Object obj, String name) throws Exception {
        String key = obj.getClass().getName() + "#" + name;
        Field f = FIELD_CACHE.computeIfAbsent(key, k -> {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field found = c.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
            return null;
        });
        return f != null ? f.get(obj) : null;
    }

    static void setField(Object obj, String name, Object value) throws Exception {
        String key = obj.getClass().getName() + "#" + name;
        Field f = FIELD_CACHE.computeIfAbsent(key, k -> {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field found = c.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
            return null;
        });
        if (f != null) f.set(obj, value);
    }

    static Object invokeMethod(Object obj, String name) throws Exception {
        String key = obj.getClass().getName() + "#" + name + "()";
        Method m = METHOD_CACHE.computeIfAbsent(key, k -> {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Method found = c.getDeclaredMethod(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            }
            return null;
        });
        return m != null ? m.invoke(obj) : null;
    }

    static int iGet(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    static float fGet(Object o) {
        return o instanceof Number n ? n.floatValue() : 0f;
    }

    static Object newVec3i(int x, int y, int z) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3i");
        return cls.getMethod("from", int.class, int.class, int.class).invoke(null, x, y, z);
    }

    static Object newVec3f(float x, float y, float z) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3f");
        return cls.getMethod("from", float.class, float.class, float.class).invoke(null, x, y, z);
    }
}