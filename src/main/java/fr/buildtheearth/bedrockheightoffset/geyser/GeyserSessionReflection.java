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
 * Two responsibilities:
 *
 * 1. Retrieve the Netty Channel for a Bedrock session so we can inject
 *    our packet interceptor into the pipeline.
 *
 * 2. Per-session BedrockDimension patch: after Geyser's connect() runs,
 *    we replace session.bedrockOverworldDimension with a taller instance
 *    (height=2048) so that JavaLevelChunkWithLightTranslator stops
 *    discarding sections above Y=320.
 *
 *    Why per-session and not static?
 *    Patching the static BedrockDimension.OVERWORLD field breaks all
 *    subsequent sessions because Geyser reuses that object for its own
 *    internal calculations. Patching only the GeyserSession instance
 *    field is surgical and safe.
 */
public class GeyserSessionReflection {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    // ── Reflection cache ─────────────────────────────────────────────────────

    private static Field  upstreamField            = null;
    private static Field  bedrockSessionField      = null;
    private static Field  bedrockOverworldDimField = null;
    private static Field  bedrockDimField          = null;
    private static Method getPeerMethod            = null;
    private static Method getChannelMethod         = null;

    private static boolean ready                   = false;

    // ── Init ─────────────────────────────────────────────────────────────────

    public static void initialize() {
        try {
            Class<?> geyserSession   = Class.forName("org.geysermc.geyser.session.GeyserSession");
            Class<?> upstreamSession = Class.forName("org.geysermc.geyser.session.UpstreamSession");
            Class<?> bedrockSession  = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockServerSession");
            Class<?> bedrockPeer     = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockPeer");

            upstreamField = geyserSession.getDeclaredField("upstream");
            upstreamField.setAccessible(true);

            bedrockSessionField = upstreamSession.getDeclaredField("session");
            bedrockSessionField.setAccessible(true);

            // These two fields hold the dimension objects inside GeyserSession
            bedrockOverworldDimField = geyserSession.getDeclaredField("bedrockOverworldDimension");
            bedrockOverworldDimField.setAccessible(true);

            bedrockDimField = geyserSession.getDeclaredField("bedrockDimension");
            bedrockDimField.setAccessible(true);

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

    // ── Per-session dimension patch ───────────────────────────────────────────

    /**
     * Replaces the GeyserSession's bedrockOverworldDimension with a new
     * BedrockDimension instance that has height=2048 instead of 384.
     *
     * This makes JavaLevelChunkWithLightTranslator's bounds check pass for
     * all sections from Y=-64 up to Y=1984, so none are silently dropped
     * before reaching our Netty interceptor.
     *
     * Must be called AFTER Geyser's connect() has run (i.e. after the
     * player has spawned), because connect() sets bedrockOverworldDimension.
     */
    public static void patchSessionDimension(UUID javaUuid) {
        if (!ready) return;
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) return;

            // Build a new BedrockDimension(minY=-64, height=2048, doUpperHeightWarn=false, id=0)
            Class<?> dimClass = Class.forName("org.geysermc.geyser.level.BedrockDimension");
            Object tallDim = dimClass
                .getDeclaredConstructor(int.class, int.class, boolean.class, int.class)
                .newInstance(-64, 2048, false, 0);

            // Read the current overworld dim to check if it's already patched
            Object current = bedrockOverworldDimField.get(conn);
            Field heightF  = dimClass.getDeclaredField("height");
            heightF.setAccessible(true);
            int currentHeight = (int) heightF.get(current);

            if (currentHeight >= 2048) {
                LOG.fine("[BHO] Session dimension already patched for " + javaUuid);
                return;
            }

            // Replace overworld dimension on the session
            bedrockOverworldDimField.set(conn, tallDim);

            // Also replace bedrockDimension if it currently points to the overworld
            Object currentDim = bedrockDimField.get(conn);
            if (currentDim == current) {
                bedrockDimField.set(conn, tallDim);
            }

            LOG.info("[BHO] Session dimension patched for " + javaUuid
                + " | height: " + currentHeight + " → 2048"
                + " (covers Y=-64 to Y=" + (-64 + 2048) + ")");

        } catch (Exception e) {
            LOG.warning("[BHO] patchSessionDimension failed for " + javaUuid + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Channel access ────────────────────────────────────────────────────────

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