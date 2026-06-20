package fr.buildtheearth.bedrockheightoffset.translation;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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
            if (buf == null) {
                plugin.getPluginConfig().debugLog("translateChunk: data field is null");
                return packet;
            }

            Method readableBytes = buf.getClass().getMethod("readableBytes");
            int size = (int) readableBytes.invoke(buf);

            plugin.getPluginConfig().debugLog(String.format(
                "translateChunk: bufSize=%d offsetSections=%d", size, offsetSections
            ));

            Method copy = buf.getClass().getMethod("copy");
            Object newBuf = copy.invoke(buf);

            int rewritten = rewriteSubChunkIndices(newBuf, offsetSections);

            plugin.getPluginConfig().debugLog(
                "translateChunk: rewrote " + rewritten + " sub-chunk indices"
            );

            if (rewritten > 0) {
                Method release = buf.getClass().getMethod("release");
                release.invoke(buf);
                setField(packet, "data", newBuf);
            } else {
                Method release = newBuf.getClass().getMethod("release");
                release.invoke(newBuf);
            }

        } catch (Exception e) {
            LOG.warning("[BHO] translateChunk error: " + e.getMessage());
            if (plugin.getPluginConfig().isDebug()) e.printStackTrace();
        }
        return packet;
    }

    /**
     * Scans the serialized sub-chunk ByteBuf and rewrites version-9
     * sub-chunk index bytes.
     *
     * Returns the count of indices rewritten.
     */
    private int rewriteSubChunkIndices(Object buf, int offsetSections) throws Exception {
        Method readerIndex   = buf.getClass().getMethod("readerIndex");
        Method readableBytes = buf.getClass().getMethod("readableBytes");
        Method getByte       = buf.getClass().getMethod("getByte", int.class);
        Method setByte       = buf.getClass().getMethod("setByte", int.class, int.class);

        int start    = (int) readerIndex.invoke(buf);
        int end      = start + (int) readableBytes.invoke(buf);
        int pos      = start;
        int rewritten = 0;

        while (pos < end) {
            int version = ub(buf, getByte, pos);

            if (version == 9) {
                if (pos + 2 >= end) break;

                int storageCount = ub(buf, getByte, pos + 1);
                int rawIndex     = sb(buf, getByte, pos + 2);
                int newIndex     = rawIndex - offsetSections;

                setByte.invoke(buf, pos + 2, newIndex & 0xFF);
                rewritten++;

                plugin.getPluginConfig().debugLog(String.format(
                    "  v9 sub-chunk: storages=%d idx %d→%d", storageCount, rawIndex, newIndex
                ));

                pos = skipStorages(buf, getByte, pos + 3, end, storageCount);

            } else if (version == 8) {
                if (pos + 1 >= end) break;
                int storageCount = ub(buf, getByte, pos + 1);
                pos = skipStorages(buf, getByte, pos + 2, end, storageCount);

            } else if (version == 1) {
                pos += 1 + 4096 + 2048;
            } else {
                pos++;
            }
        }
        return rewritten;
    }

    private int skipStorages(Object buf, Method getByte, int pos, int end, int count)
        throws Exception {

        for (int i = 0; i < count && pos < end; i++) {
            int header       = ub(buf, getByte, pos);
            int bitsPerBlock = header >> 1;
            pos++;

            if (bitsPerBlock == 0x7F) {
                pos = skipVarint(buf, getByte, pos, end);
                continue;
            }
            if (bitsPerBlock == 0) {
                int[] r = readVarint(buf, getByte, pos, end);
                pos = r[1];
                pos = skipVarint(buf, getByte, pos, end);
                continue;
            }

            int blocksPerWord = 32 / bitsPerBlock;
            int wordCount     = (int) Math.ceil(4096.0 / blocksPerWord);
            pos += wordCount * 4;

            int[] r         = readVarint(buf, getByte, pos, end);
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
            Object pos   = getField(packet, "blockPosition");
            if (pos == null) return packet;
            int bedrockY = (int) data.toBedrockY(iGet(invokeMethod(pos, "getY")));
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
            Object pos   = getField(packet, "blockPosition");
            if (pos == null) return packet;
            int bedrockY = (int) data.toBedrockY(iGet(invokeMethod(pos, "getY")));
            setField(packet, "blockPosition", newVec3i(
                iGet(invokeMethod(pos, "getX")), bedrockY, iGet(invokeMethod(pos, "getZ"))
            ));
        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateBlockEntity: " + e.getMessage());
        }
        return packet;
    }

    private static int ub(Object buf, Method getByte, int pos) throws Exception {
        return ((Number) getByte.invoke(buf, pos)).intValue() & 0xFF;
    }

    private static int sb(Object buf, Method getByte, int pos) throws Exception {
        int v = ub(buf, getByte, pos);
        return v > 127 ? v - 256 : v;
    }

    private static int skipVarint(Object buf, Method getByte, int pos, int end) throws Exception {
        while (pos < end) {
            if ((ub(buf, getByte, pos++) & 0x80) == 0) break;
        }
        return pos;
    }

    private static int[] readVarint(Object buf, Method getByte, int pos, int end) throws Exception {
        int value = 0, shift = 0;
        while (pos < end) {
            int b = ub(buf, getByte, pos++);
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
                try { Field x = c.getDeclaredField(name); x.setAccessible(true); return x; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
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
                try { Field x = c.getDeclaredField(name); x.setAccessible(true); return x; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
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
                try { Method x = c.getDeclaredMethod(name); x.setAccessible(true); return x; }
                catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            }
            return null;
        });
        return m != null ? m.invoke(obj) : null;
    }

    static int   iGet(Object o) { return o instanceof Number n ? n.intValue()  : 0;  }
    static float fGet(Object o) { return o instanceof Number n ? n.floatValue() : 0f; }

    static Object newVec3i(int x, int y, int z) throws Exception {
        Class<?> c = Class.forName("org.cloudburstmc.math.vector.Vector3i");
        return c.getMethod("from", int.class, int.class, int.class).invoke(null, x, y, z);
    }

    static Object newVec3f(float x, float y, float z) throws Exception {
        Class<?> c = Class.forName("org.cloudburstmc.math.vector.Vector3f");
        return c.getMethod("from", float.class, float.class, float.class).invoke(null, x, y, z);
    }
}