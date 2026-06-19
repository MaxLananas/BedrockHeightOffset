package fr.buildtheearth.bedrockheightoffset.geyser;

import io.netty.channel.Channel;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection bridge into Geyser internals.
 *
 * Also patches BedrockDimension.OVERWORLD static fields so Geyser
 * stops dropping chunk sections above Y=320 before they reach our
 * Netty interceptor. Without this patch, the translator in
 * JavaLevelChunkWithLightTranslator silently discards any section
 * with bedrockSectionY > maxBedrockSectionY (which is height>>4 - 1).
 *
 * We set the overworld height to 2048 (covers -64 → 1984) so all
 * sections pass through. Our interceptor then rewrites sub-chunk
 * indices to fit the client's actual [-64,320] window.
 */
public class GeyserSessionReflection {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private static Field  upstreamField       = null;
    private static Field  bedrockSessionField = null;
    private static Method getPeerMethod       = null;
    private static Method getChannelMethod    = null;
    private static boolean ready              = false;

    // Track whether we successfully patched BedrockDimension
    private static boolean dimensionPatched   = false;

    public static void initialize() {
        initReflection();
        patchBedrockDimension();
    }

    private static void initReflection() {
        try {
            Class<?> geyserSession   = Class.forName("org.geysermc.geyser.session.GeyserSession");
            Class<?> upstreamSession = Class.forName("org.geysermc.geyser.session.UpstreamSession");
            Class<?> bedrockSession  = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockServerSession");
            Class<?> bedrockPeer     = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockPeer");

            upstreamField = geyserSession.getDeclaredField("upstream");
            upstreamField.setAccessible(true);

            bedrockSessionField = upstreamSession.getDeclaredField("session");
            bedrockSessionField.setAccessible(true);

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
     * Patches the static BedrockDimension.OVERWORLD instance so Geyser's
     * chunk translator stops filtering out sections above Y=320.
     *
     * BedrockDimension has final fields: minY, height, doUpperHeightWarn, bedrockId.
     * We use reflection + Unsafe (or setAccessible on modern JDK with --add-opens)
     * to overwrite them.
     *
     * Target: height = 2048  (covers Y=-64 to Y=1984, more than enough for Y=1952)
     *         minY   = -64   (unchanged)
     *         doUpperHeightWarn = false (suppress the console warning)
     */
    private static void patchBedrockDimension() {
        try {
            Class<?> dimClass = Class.forName("org.geysermc.geyser.level.BedrockDimension");

            // Get the static OVERWORLD field
            Field overworldField = dimClass.getDeclaredField("OVERWORLD");
            overworldField.setAccessible(true);
            Object overworld = overworldField.get(null);

            // Patch: height → 2048
            setFinalField(overworld, "height", 2048);
            // Patch: doUpperHeightWarn → false  (kills the console warning)
            setFinalField(overworld, "doUpperHeightWarn", false);

            // Verify
            Field heightField = dimClass.getDeclaredField("height");
            heightField.setAccessible(true);
            int newHeight = (int) heightField.get(overworld);

            dimensionPatched = true;
            LOG.info("[BHO] BedrockDimension.OVERWORLD patched: height=" + newHeight
                + " (covers Y=-64 to Y=" + (-64 + newHeight) + ")");

        } catch (Exception e) {
            LOG.severe("[BHO] BedrockDimension patch FAILED: " + e.getMessage());
            LOG.severe("[BHO] Chunks above Y=320 will still be dropped by Geyser.");
            e.printStackTrace();
        }
    }

    /**
     * Removes the final modifier from a field and sets its value using sun.misc.Unsafe
     * to bypass Java's final field protection (works on JDK 17-21 with Paper's module setup).
     */
    private static void setFinalField(Object target, String fieldName, Object value) throws Exception {
        Class<?> cls = target.getClass();
        Field field = null;

        // Walk class hierarchy
        while (field == null && cls != null) {
            try {
                field = cls.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }

        if (field == null) throw new NoSuchFieldException(fieldName + " not found in hierarchy");
        field.setAccessible(true);

        // Use sun.misc.Unsafe for final field modification (reliable on JDK 21 + Paper)
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

        long offset = unsafe.objectFieldOffset(field);

        if (value instanceof Integer i) {
            unsafe.putInt(target, offset, i);
        } else if (value instanceof Boolean b) {
            unsafe.putBoolean(target, offset, b);
        } else {
            unsafe.putObject(target, offset, value);
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

    public static boolean isReady()           { return ready;            }
    public static boolean isDimensionPatched() { return dimensionPatched; }
}