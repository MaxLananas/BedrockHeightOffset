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
 * Target chain:
 *   GeyserApi.connectionByUuid(uuid)          → GeyserConnection (= GeyserSession)
 *   GeyserSession.upstream                     → UpstreamSession
 *   UpstreamSession.session                    → BedrockServerSession
 *   BedrockServerSession.getPeer().getChannel()→ Netty Channel
 *
 * We inject a ChannelHandler into that Netty pipeline to intercept
 * all outbound BedrockPackets before they reach the client.
 */
public class GeyserSessionReflection {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private static Field  upstreamField       = null;
    private static Field  bedrockSessionField = null;
    private static Method getPeerMethod       = null;
    private static Method getChannelMethod    = null;
    private static boolean ready              = false;

    public static void initialize() {
        try {
            Class<?> geyserSessionClass    = Class.forName("org.geysermc.geyser.session.GeyserSession");
            Class<?> upstreamSessionClass  = Class.forName("org.geysermc.geyser.session.UpstreamSession");
            Class<?> bedrockServerSession  = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockServerSession");
            Class<?> bedrockPeerClass      = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockPeer");

            upstreamField = geyserSessionClass.getDeclaredField("upstream");
            upstreamField.setAccessible(true);

            bedrockSessionField = upstreamSessionClass.getDeclaredField("session");
            bedrockSessionField.setAccessible(true);

            getPeerMethod = bedrockServerSession.getMethod("getPeer");
            getPeerMethod.setAccessible(true);

            getChannelMethod = bedrockPeerClass.getMethod("getChannel");
            getChannelMethod.setAccessible(true);

            ready = true;
            LOG.info("[BHO] GeyserSessionReflection initialized successfully");

        } catch (Exception e) {
            LOG.severe("[BHO] GeyserSessionReflection FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Returns the Netty Channel used by Geyser to communicate with the Bedrock client.
     * This is the raw channel BEFORE any Bedrock encoding, so we can intercept packets.
     */
    public static Channel getBedrockChannel(UUID javaUuid) {
        if (!ready) return null;
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) return null;

            Object upstream       = upstreamField.get(conn);
            Object bedrockSession = bedrockSessionField.get(upstream);
            Object peer           = getPeerMethod.invoke(bedrockSession);
            Object channel        = getChannelMethod.invoke(peer);

            return (Channel) channel;

        } catch (Exception e) {
            LOG.warning("[BHO] getBedrockChannel failed for " + javaUuid + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Sends a BedrockPacket directly through the upstream UpstreamSession.sendPacket(),
     * bypassing Geyser's translation layer entirely.
     */
    public static void sendDirectPacket(UUID javaUuid, Object bedrockPacket) {
        if (!ready) return;
        try {
            GeyserConnection conn = GeyserApi.api().connectionByUuid(javaUuid);
            if (conn == null) return;

            Object upstream = upstreamField.get(conn);
            // Call UpstreamSession.sendPacket(BedrockPacket)
            Class<?> upstreamClass = upstream.getClass();
            for (Method m : upstreamClass.getMethods()) {
                if (m.getName().equals("sendPacket") && m.getParameterCount() == 1) {
                    m.invoke(upstream, bedrockPacket);
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warning("[BHO] sendDirectPacket failed: " + e.getMessage());
        }
    }

    public static boolean isReady() { return ready; }
}