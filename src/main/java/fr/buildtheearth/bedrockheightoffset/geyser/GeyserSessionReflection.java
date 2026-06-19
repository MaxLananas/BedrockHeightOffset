package fr.buildtheearth.bedrockheightoffset.geyser;

import io.netty.channel.Channel;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

public class GeyserSessionReflection {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private static Field  upstreamField        = null;
    private static Field  bedrockSessionField  = null;
    private static Field  bedrockDimField      = null;
    private static Field  overworldDimField    = null;
    private static Field  dimHeightField       = null;
    private static Field  dimMinYField         = null;
    private static Field  dimWarnField         = null;
    private static Field  chunkCacheField      = null;
    private static Field  chunkHeightYField    = null;
    private static Field  chunkMinYField       = null;
    private static Method getPeerMethod        = null;
    private static Method getChannelMethod     = null;

    private static boolean ready               = false;

    public static void initialize() {
        try {
            Class<?> geyserSession   = Class.forName("org.geysermc.geyser.session.GeyserSession");
            Class<?> upstreamSession = Class.forName("org.geysermc.geyser.session.UpstreamSession");
            Class<?> bedrockSession  = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockServerSession");
            Class<?> bedrockPeer     = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockPeer");
            Class<?> bedrockDim      = Class.forName("org.geysermc.geyser.level.BedrockDimension");
            Class<?> chunkCache      = Class.forName("org.geysermc.geyser.session.cache.ChunkCache");

            upstreamField = geyserSession.getDeclaredField("upstream");
            upstreamField.setAccessible(true);

            bedrockSessionField = upstreamSession.getDeclaredField("session");
            bedrockSessionField.setAccessible(true);

            bedrockDimField = geyserSession.getDeclaredField("bedrockDimension");
            bedrockDimField.setAccessible(true);

            overworldDimField = geyserSession.getDeclaredField("bedrockOverworldDimension");
            overworldDimField.setAccessible(true);

            dimHeightField = bedrockDim.getDeclaredField("height");
            dimHeightField.setAccessible(true);

            dimMinYField = bedrockDim.getDeclaredField("minY");
            dimMinYField.setAccessible(true);

            dimWarnField = bedrockDim.getDeclaredField("doUpperHeightWarn");
            dimWarnField.setAccessible(true);

            chunkCacheField = geyserSession.getDeclaredField("chunkCache");
            chunkCacheField.setAccessible(true);

            // ChunkCache stores chunkHeightY and chunkMinY
            chunkHeightYField = chunkCache.getDeclaredField("chunkHeightY");
            chunkHeightYField.setAccessible(true);

            chunkMinYField = chunkCache.getDeclaredField("chunkMinY");
            chunkMinYField.setAccessible(true);

            getPeerMethod = bedrockSession.getMethod("getPeer");
            getPeerMethod.setAccessible(true);

            getChannelMethod = bedrockPeer.getMethod("getChannel");
            getChannelMethod.setAccessible(true);

            ready = true;
            LOG.info("[BHO] GeyserSessionReflection initialized");

        } catch (Exception e) {
            LOG.severe("[BHO] GeyserSessionReflection FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Patches both the BedrockDimension height AND the ChunkCache height
     * for a specific session.
     *
     * ChunkCache.chunkHeightY is used directly in JavaLevelChunkWithLightTranslator:
     *   int chunkSize = session.getChunkCache().getChunkHeightY();
     *   DataPalette[] javaChunks = new DataPalette[chunkSize];
     *   for (int sectionY = 0; sectionY < chunkSize; sectionY++) { ... }
     *
     * If chunkHeightY is capped (e.g. at 36 for 576 blocks), sections above
     * Y=512 are never even iterated. We must set it to the real Java world height.
     *
     * terraplusminus: minY=-64, height=2016 (Y=-64 to Y=1952)
     *   chunkHeightY = 2016 / 16 = 126 sections
     *   chunkMinY    = -64 / 16  = -4  sections
     */
    public static void patchSessionDimension(UUID javaUuid) {
        if (!ready) return;
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) return;
            
            Object overworldDim = overworldDimField.get(conn);
            if (overworldDim != null) {
                int oldH = (int) dimHeightField.get(overworldDim);
                if (oldH < 2048) {
                    dimHeightField.set(overworldDim, 2048);
                    dimMinYField.set(overworldDim, -64);
                    dimWarnField.set(overworldDim, false);
                    LOG.info("[BHO] overworldDim patched | height " + oldH + "→2048 | " + javaUuid);
                }
            }

            Object currentDim = bedrockDimField.get(conn);
            if (currentDim != null && currentDim != overworldDim) {
                int oldH = (int) dimHeightField.get(currentDim);
                if (oldH < 2048) {
                    dimHeightField.set(currentDim, 2048);
                    dimMinYField.set(currentDim, -64);
                    dimWarnField.set(currentDim, false);
                    LOG.info("[BHO] bedrockDim (separate) patched | height " + oldH + "→2048 | " + javaUuid);
                }
            }

            Object chunkCache = chunkCacheField.get(conn);
            if (chunkCache != null) {
                int oldHeight = (int) chunkHeightYField.get(chunkCache);
                int oldMinY   = (int) chunkMinYField.get(chunkCache);

                // terraplusminus: minY=-64, total height=2016 → 126 sections
                // We use 128 sections (2048 blocks) to have a safe margin
                int targetHeight = 128; // sections
                int targetMinY   = -4;  // sections (= -64 blocks / 16)

                if (oldHeight != targetHeight) {
                    chunkHeightYField.set(chunkCache, targetHeight);
                    chunkMinYField.set(chunkCache, targetMinY);
                    LOG.info(String.format(
                        "[BHO] ChunkCache patched | heightY %d→%d | minY %d→%d | %s",
                        oldHeight, targetHeight, oldMinY, targetMinY, javaUuid
                    ));
                }
            }

        } catch (Exception e) {
            LOG.severe("[BHO] patchSessionDimension FAILED for " + javaUuid + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Channel getBedrockChannel(UUID javaUuid) {
        if (!ready) return null;
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) return null;

            Object upstream       = upstreamField.get(conn);
            Object bedrockSession = bedrockSessionField.get(upstream);
            Object peer           = getPeerMethod.invoke(bedrockSession);
            return (Channel) getChannelMethod.invoke(peer);

        } catch (Exception e) {
            LOG.warning("[BHO] getBedrockChannel failed: " + e.getMessage());
            return null;
        }
    }

    public static boolean isReady() { return ready; }
}