package fr.buildtheearth.bedrockheightoffset.geyser;

import io.netty.channel.Channel;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection bridge into Geyser internals.
 *
 * Key insight for the Y=1952 target:
 *
 * JavaLevelChunkWithLightTranslator computes per-packet:
 *   BedrockDimension dim = session.getBedrockDimension();
 *   int maxBedrockSectionY = (dim.height() >> 4) - 1;
 *   if (bedrockSectionY > maxBedrockSectionY) continue; // section dropped!
 *
 * session.getBedrockDimension() returns session.bedrockDimension, which
 * points to the same object as session.bedrockOverworldDimension (for
 * overworld players).
 *
 * Both fields point to the SAME BedrockDimension instance.
 * We mutate that instance's 'height' field directly (it's a primitive int
 * stored in a final field — accessible via setAccessible on JDK 21 with
 * Paper's module opens).
 *
 * We set height = 2048 (covers Y=-64 to Y=1984).
 * This makes maxBedrockSectionY = (2048 >> 4) - 1 = 127.
 * Java section at Y=1952 → sectionY = (1952+64)/16 = 126 ≤ 127 ✓
 *
 * We do NOT replace the object reference (no Unsafe needed).
 * We mutate the object in-place via setAccessible(true) on the field.
 * On Paper 1.21.x with JDK 21, this works because Paper adds
 * --add-opens for internal modules.
 */
public class GeyserSessionReflection {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private static Field  upstreamField       = null;
    private static Field  bedrockSessionField = null;
    private static Field  bedrockDimField     = null;  // session.bedrockDimension
    private static Field  overworldDimField   = null;  // session.bedrockOverworldDimension
    private static Field  dimHeightField      = null;  // BedrockDimension.height
    private static Field  dimMinYField        = null;  // BedrockDimension.minY
    private static Field  dimWarnField        = null;  // BedrockDimension.doUpperHeightWarn
    private static Method getPeerMethod       = null;
    private static Method getChannelMethod    = null;

    private static boolean ready              = false;

    public static void initialize() {
        try {
            Class<?> geyserSession   = Class.forName("org.geysermc.geyser.session.GeyserSession");
            Class<?> upstreamSession = Class.forName("org.geysermc.geyser.session.UpstreamSession");
            Class<?> bedrockSession  = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockServerSession");
            Class<?> bedrockPeer     = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockPeer");
            Class<?> bedrockDim      = Class.forName("org.geysermc.geyser.level.BedrockDimension");

            upstreamField = geyserSession.getDeclaredField("upstream");
            upstreamField.setAccessible(true);

            bedrockSessionField = upstreamSession.getDeclaredField("session");
            bedrockSessionField.setAccessible(true);

            bedrockDimField = geyserSession.getDeclaredField("bedrockDimension");
            bedrockDimField.setAccessible(true);

            overworldDimField = geyserSession.getDeclaredField("bedrockOverworldDimension");
            overworldDimField.setAccessible(true);

            // Mutate BedrockDimension's primitive fields directly
            dimHeightField = bedrockDim.getDeclaredField("height");
            dimHeightField.setAccessible(true);

            dimMinYField = bedrockDim.getDeclaredField("minY");
            dimMinYField.setAccessible(true);

            dimWarnField = bedrockDim.getDeclaredField("doUpperHeightWarn");
            dimWarnField.setAccessible(true);

            getPeerMethod = bedrockSession.getMethod("getPeer");
            getPeerMethod.setAccessible(true);

            getChannelMethod = bedrockPeer.getMethod("getChannel");
            getChannelMethod.setAccessible(true);

            ready = true;
            LOG.info("[BHO] GeyserSessionReflection initialized — ready to patch sessions");

        } catch (Exception e) {
            LOG.severe("[BHO] GeyserSessionReflection FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Mutates the BedrockDimension instance(s) held by the GeyserSession
     * in-place, setting height=2048 so that chunk sections up to Y=1984
     * are no longer discarded by JavaLevelChunkWithLightTranslator.
     *
     * Math:
     *   height = 2048
     *   maxBedrockSectionY = (2048 >> 4) - 1 = 127
     *   Java section for Y=1952: index = (1952 - (-64)) / 16 = 126 ≤ 127 ✓
     *   Java section for Y=1984: index = (1984 - (-64)) / 16 = 128 > 127 ✗ (just over, fine)
     *
     * We also set doUpperHeightWarn=false to suppress the console warning.
     *
     * This must be called AFTER Geyser's connect() has run (tick ≥ 2).
     * connect() assigns bedrockOverworldDimension via:
     *   this.bedrockOverworldDimension = new BedrockDimension(minY, maxY-minY, true, OVERWORLD_ID);
     * So we patch the newly created instance right after.
     */
    public static void patchSessionDimension(UUID javaUuid) {
        if (!ready) {
            LOG.warning("[BHO] patchSessionDimension called before init for " + javaUuid);
            return;
        }
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) {
                LOG.warning("[BHO] patchSessionDimension: no connection for " + javaUuid);
                return;
            }

            // Patch bedrockOverworldDimension
            Object overworldDim = overworldDimField.get(conn);
            if (overworldDim != null) {
                int oldHeight = (int) dimHeightField.get(overworldDim);
                if (oldHeight < 2048) {
                    dimHeightField.set(overworldDim, 2048);
                    dimMinYField.set(overworldDim, -64);
                    dimWarnField.set(overworldDim, false);
                    LOG.info("[BHO] overworldDim patched for " + javaUuid
                        + " | height " + oldHeight + " → 2048"
                        + " | covers Y=-64 to Y=" + (-64 + 2048));
                } else {
                    LOG.fine("[BHO] overworldDim already height=" + oldHeight + " for " + javaUuid);
                }
            }

            // Patch bedrockDimension — may be the same object or a different one
            Object currentDim = bedrockDimField.get(conn);
            if (currentDim != null && currentDim != overworldDim) {
                int oldHeight = (int) dimHeightField.get(currentDim);
                if (oldHeight < 2048) {
                    dimHeightField.set(currentDim, 2048);
                    dimMinYField.set(currentDim, -64);
                    dimWarnField.set(currentDim, false);
                    LOG.info("[BHO] bedrockDim (separate obj) patched for " + javaUuid
                        + " | height " + oldHeight + " → 2048");
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
            LOG.warning("[BHO] getBedrockChannel failed for " + javaUuid + ": " + e.getMessage());
            return null;
        }
    }

    public static boolean isReady() { return ready; }
}