package fr.buildtheearth.bedrockheightoffset.geyser;

import io.netty.channel.Channel;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;

/**
 * Reflection bridge using runtime class discovery.
 *
 * Instead of hardcoding class names that may differ between Geyser builds,
 * we navigate the object graph starting from GeyserApi.api().onlineConnections()
 * and inspect the actual runtime types to find fields dynamically.
 */
public class GeyserSessionReflection {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private static Field  upstreamField     = null;
    private static Field  bedrockSessField  = null;
    private static Field  bedrockDimField   = null;
    private static Field  overworldDimField = null;
    private static Field  dimHeightField    = null;
    private static Field  dimMinYField      = null;
    private static Field  dimWarnField      = null;
    private static Field  chunkCacheField   = null;
    private static Field  cacheHeightField  = null;
    private static Field  cacheMinYField    = null;
    private static Method getPeerMethod     = null;
    private static Method getChannelMethod  = null;

    private static boolean ready            = false;

    public static void initialize() {
        try {
            initViaClassNames();
        } catch (Exception e) {
            LOG.severe("[BHO] Reflection init FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void initViaClassNames() throws Exception {
        ClassLoader cl = GeyserApi.class.getClassLoader();

        Class<?> geyserSession = findClass(cl,
            "org.geysermc.geyser.session.GeyserSession"
        );
        if (geyserSession == null) throw new ClassNotFoundException("GeyserSession not found");

        Class<?> upstreamSession = findClass(cl,
            "org.geysermc.geyser.session.UpstreamSession"
        );
        if (upstreamSession == null) throw new ClassNotFoundException("UpstreamSession not found");

        Class<?> bedrockServerSession = findClass(cl,
            "org.cloudburstmc.protocol.bedrock.BedrockServerSession",
            "com.nukkitx.protocol.bedrock.BedrockServerSession"
        );

        Class<?> bedrockPeer = findClass(cl,
            "org.cloudburstmc.protocol.bedrock.BedrockPeer",
            "com.nukkitx.protocol.bedrock.BedrockPeer"
        );

        Class<?> bedrockDim = findClass(cl,
            "org.geysermc.geyser.level.BedrockDimension"
        );
        if (bedrockDim == null) throw new ClassNotFoundException("BedrockDimension not found");

        Class<?> chunkCache = findClass(cl,
            "org.geysermc.geyser.session.cache.ChunkCache"
        );

        LOG.info("[BHO] Classes found:");
        LOG.info("  GeyserSession: "       + geyserSession.getName());
        LOG.info("  UpstreamSession: "     + upstreamSession.getName());
        LOG.info("  BedrockDimension: "    + bedrockDim.getName());
        if (bedrockServerSession != null) LOG.info("  BedrockServerSession: " + bedrockServerSession.getName());
        if (bedrockPeer != null)          LOG.info("  BedrockPeer: "          + bedrockPeer.getName());
        if (chunkCache != null)           LOG.info("  ChunkCache: "           + chunkCache.getName());

        upstreamField = findField(geyserSession, upstreamSession,
            "upstream");
        if (upstreamField == null)
            upstreamField = findFieldByType(geyserSession, upstreamSession);

        bedrockDimField = findField(geyserSession, null,
            "bedrockDimension");

        overworldDimField = findField(geyserSession, null,
            "bedrockOverworldDimension");

        chunkCacheField = findField(geyserSession, chunkCache,
            "chunkCache");

        if (upstreamSession != null && bedrockServerSession != null) {
            bedrockSessField = findField(upstreamSession, bedrockServerSession, "session");
            if (bedrockSessField == null)
                bedrockSessField = findFieldByType(upstreamSession, bedrockServerSession);
        }

        dimHeightField = findField(bedrockDim, null, "height");
        dimMinYField   = findField(bedrockDim, null, "minY");
        dimWarnField   = findField(bedrockDim, null, "doUpperHeightWarn");

        if (chunkCache != null) {
            cacheHeightField = findField(chunkCache, null,
                "chunkHeightY", "heightY", "height");
            cacheMinYField   = findField(chunkCache, null,
                "chunkMinY", "minY");
        }

        if (bedrockServerSession != null) {
            getPeerMethod = findMethod(bedrockServerSession, "getPeer");
        }
        if (bedrockPeer != null) {
            getChannelMethod = findMethod(bedrockPeer, "getChannel");
        }

        LOG.info("[BHO] Field/method discovery results:");
        LOG.info("  upstream:           " + (upstreamField     != null ? upstreamField.getName()     : "MISSING"));
        LOG.info("  bedrockSession:     " + (bedrockSessField  != null ? bedrockSessField.getName()  : "MISSING"));
        LOG.info("  bedrockDimension:   " + (bedrockDimField   != null ? bedrockDimField.getName()   : "MISSING"));
        LOG.info("  overworldDimension: " + (overworldDimField != null ? overworldDimField.getName() : "MISSING"));
        LOG.info("  dim.height:         " + (dimHeightField    != null ? dimHeightField.getName()    : "MISSING"));
        LOG.info("  dim.minY:           " + (dimMinYField      != null ? dimMinYField.getName()      : "MISSING"));
        LOG.info("  dim.warn:           " + (dimWarnField      != null ? dimWarnField.getName()      : "MISSING"));
        LOG.info("  chunkCache:         " + (chunkCacheField   != null ? chunkCacheField.getName()   : "MISSING"));
        LOG.info("  cache.heightY:      " + (cacheHeightField  != null ? cacheHeightField.getName()  : "MISSING"));
        LOG.info("  cache.minY:         " + (cacheMinYField    != null ? cacheMinYField.getName()    : "MISSING"));
        LOG.info("  getPeer():          " + (getPeerMethod     != null ? "found"                     : "MISSING"));
        LOG.info("  getChannel():       " + (getChannelMethod  != null ? "found"                     : "MISSING"));

        if (bedrockDimField != null && overworldDimField != null && dimHeightField != null) {
            ready = true;
            LOG.info("[BHO] GeyserSessionReflection ready (dimension patch available)");
        } else {
            LOG.severe("[BHO] Critical fields missing — cannot patch dimensions!");
        }
    }

    public static void patchSessionDimension(UUID javaUuid) {
        if (!ready) {
            LOG.warning("[BHO] patchSessionDimension: reflection not ready");
            return;
        }
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) {
                LOG.warning("[BHO] patchSessionDimension: no connection for " + javaUuid);
                return;
            }

            // Patch overworldDimension
            if (overworldDimField != null) {
                Object dim = overworldDimField.get(conn);
                patchDimObject(dim, javaUuid, "overworldDim");
            }

            // Patch bedrockDimension (may be same object or different)
            if (bedrockDimField != null) {
                Object dim = bedrockDimField.get(conn);
                patchDimObject(dim, javaUuid, "bedrockDim");
            }

            if (chunkCacheField != null) {
                Object cache = chunkCacheField.get(conn);
                patchChunkCache(cache, javaUuid);
            }

        } catch (Exception e) {
            LOG.severe("[BHO] patchSessionDimension error for " + javaUuid + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void patchDimObject(Object dim, UUID uuid, String label) {
        if (dim == null || dimHeightField == null) return;
        try {
            int oldHeight = (int) dimHeightField.get(dim);
            if (oldHeight >= 2048) {
                LOG.fine("[BHO] " + label + " already height=" + oldHeight);
                return;
            }

            dimHeightField.set(dim, 2048);

            if (dimMinYField != null) {
                int oldMin = (int) dimMinYField.get(dim);
                if (oldMin > -64) dimMinYField.set(dim, -64);
            }

            if (dimWarnField != null) {
                dimWarnField.set(dim, false);
            }

            LOG.info(String.format("[BHO] %s patched | height %d→2048 | uuid=%s",
                label, oldHeight, uuid));

        } catch (Exception e) {
            LOG.warning("[BHO] patchDimObject(" + label + ") failed: " + e.getMessage());
        }
    }

    private static void patchChunkCache(Object cache, UUID uuid) {
        if (cache == null) return;
        try {
            if (cacheHeightField != null) {
                int old = (int) cacheHeightField.get(cache);
                if (old < 128) {
                    cacheHeightField.set(cache, 128);
                    LOG.info("[BHO] ChunkCache.heightY " + old + "→128 | uuid=" + uuid);
                }
            }
            if (cacheMinYField != null) {
                int old = (int) cacheMinYField.get(cache);
                if (old != -4) {
                    cacheMinYField.set(cache, -4);
                    LOG.info("[BHO] ChunkCache.minY " + old + "→-4 | uuid=" + uuid);
                }
            }
        } catch (Exception e) {
            LOG.warning("[BHO] patchChunkCache failed: " + e.getMessage());
        }
    }

    public static Channel getBedrockChannel(UUID javaUuid) {
        if (getPeerMethod == null || getChannelMethod == null) {
            LOG.warning("[BHO] getBedrockChannel: peer/channel methods not available");
            return null;
        }
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) return null;

            if (upstreamField == null || bedrockSessField == null) {
                LOG.warning("[BHO] getBedrockChannel: upstream/session fields missing");
                return null;
            }

            Object upstream  = upstreamField.get(conn);
            Object bSession  = bedrockSessField.get(upstream);
            Object peer      = getPeerMethod.invoke(bSession);
            Object channel   = getChannelMethod.invoke(peer);

            return (Channel) channel;

        } catch (Exception e) {
            LOG.warning("[BHO] getBedrockChannel failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Initializes reflection using a live connection object.
     * Call this once a Bedrock player has connected, for more accurate discovery.
     */
    public static void initFromLiveConnection(UUID javaUuid) {
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) return;

            LOG.info("[BHO] Live connection type: " + conn.getClass().getName());

            dumpFields(conn.getClass(), "GeyserSession(live)");

            Class<?> sessionClass = conn.getClass();

            if (bedrockDimField == null)
                bedrockDimField = findField(sessionClass, null, "bedrockDimension");

            if (overworldDimField == null)
                overworldDimField = findField(sessionClass, null, "bedrockOverworldDimension");

            if (upstreamField == null) {
                for (Field f : getAllFields(sessionClass)) {
                    if (f.getType().getSimpleName().toLowerCase().contains("upstream")) {
                        f.setAccessible(true);
                        upstreamField = f;
                        LOG.info("[BHO] upstream field discovered: " + f.getName()
                            + " (" + f.getType().getName() + ")");
                        break;
                    }
                }
            }

            if (upstreamField != null && bedrockSessField == null) {
                Object upstream = upstreamField.get(conn);
                if (upstream != null) {
                    LOG.info("[BHO] Upstream type: " + upstream.getClass().getName());
                    dumpFields(upstream.getClass(), "UpstreamSession(live)");

                    for (Field f : getAllFields(upstream.getClass())) {
                        Object val = null;
                        try { f.setAccessible(true); val = f.get(upstream); } catch (Exception ignored) {}
                        if (val != null && val.getClass().getSimpleName()
                            .toLowerCase().contains("session")) {
                            bedrockSessField = f;
                            LOG.info("[BHO] bedrockSession field: " + f.getName()
                                + " (" + f.getType().getName() + ")");

                            getPeerMethod = findMethod(val.getClass(), "getPeer");
                            if (getPeerMethod != null) {
                                Object peer = getPeerMethod.invoke(val);
                                if (peer != null) {
                                    LOG.info("[BHO] Peer type: " + peer.getClass().getName());
                                    getChannelMethod = findMethod(peer.getClass(), "getChannel");
                                }
                            }
                            break;
                        }
                    }
                }
            }

            if (chunkCacheField == null) {
                for (Field f : getAllFields(sessionClass)) {
                    if (f.getType().getSimpleName().equals("ChunkCache")) {
                        f.setAccessible(true);
                        chunkCacheField = f;
                        Object cache = f.get(conn);
                        if (cache != null) {
                            dumpFields(cache.getClass(), "ChunkCache(live)");
                            cacheHeightField = findField(cache.getClass(), null,
                                "chunkHeightY", "heightY", "height");
                            cacheMinYField   = findField(cache.getClass(), null,
                                "chunkMinY", "minY");
                        }
                        break;
                    }
                }
            }

            if (!ready && bedrockDimField != null && overworldDimField != null
                && dimHeightField != null) {
                ready = true;
                LOG.info("[BHO] Reflection became ready after live discovery");
            }

            LOG.info("[BHO] Post-live-discovery status: ready=" + ready);
            LOG.info("  bedrockDimField:   " + (bedrockDimField   != null ? "OK" : "MISSING"));
            LOG.info("  overworldDimField: " + (overworldDimField != null ? "OK" : "MISSING"));
            LOG.info("  upstreamField:     " + (upstreamField     != null ? "OK" : "MISSING"));
            LOG.info("  bedrockSessField:  " + (bedrockSessField  != null ? "OK" : "MISSING"));
            LOG.info("  chunkCacheField:   " + (chunkCacheField   != null ? "OK" : "MISSING"));
            LOG.info("  cacheHeightField:  " + (cacheHeightField  != null ? "OK" : "MISSING"));
            LOG.info("  getPeerMethod:     " + (getPeerMethod     != null ? "OK" : "MISSING"));
            LOG.info("  getChannelMethod:  " + (getChannelMethod  != null ? "OK" : "MISSING"));

        } catch (Exception e) {
            LOG.severe("[BHO] initFromLiveConnection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Class<?> findClass(ClassLoader cl, String... names) {
        for (String name : names) {
            try { return Class.forName(name, false, cl); }
            catch (ClassNotFoundException ignored) {}
            try { return Class.forName(name); }
            catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static Field findField(Class<?> cls, Class<?> type, String... names) {
        if (cls == null) return null;
        List<Field> all = getAllFields(cls);

        for (String name : names) {
            for (Field f : all) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }

        if (type != null) {
            for (Field f : all) {
                if (f.getType().isAssignableFrom(type) || type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    private static Field findFieldByType(Class<?> cls, Class<?> type) {
        if (cls == null || type == null) return null;
        for (Field f : getAllFields(cls)) {
            if (f.getType().isAssignableFrom(type) || type.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String... names) {
        if (cls == null) return null;
        List<Method> all = new ArrayList<>();
        Class<?> c = cls;
        while (c != null) {
            all.addAll(Arrays.asList(c.getDeclaredMethods()));
            c = c.getSuperclass();
        }
        for (String name : names) {
            for (Method m : all) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return fields;
    }

    private static void dumpFields(Class<?> cls, String label) {
        StringBuilder sb = new StringBuilder("[BHO] Fields of " + label + ":\n");
        for (Field f : getAllFields(cls)) {
            sb.append("  ").append(f.getType().getSimpleName())
              .append(" ").append(f.getName()).append("\n");
        }
        LOG.info(sb.toString());
    }

    public static boolean isReady() { return ready; }
}