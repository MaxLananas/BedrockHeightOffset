package fr.buildtheearth.bedrockheightoffset.translation;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;

import java.lang.reflect.Method;
import java.util.logging.Logger;

import static fr.buildtheearth.bedrockheightoffset.translation.ChunkTranslator.*;

/**
 * Translates position-related Bedrock packets.
 *
 * Outbound (Geyser → Client):
 *   MovePlayerPacket.position.y         -= offset
 *   RespawnPacket.position.y            -= offset
 *   LevelEventPacket.position.y         -= offset  (sounds, particles attached to world)
 *   SpawnParticleEffectPacket.position.y -= offset
 *   NetworkChunkPublisherUpdatePacket.position.y -= offset
 *
 * Inbound (Client → Geyser):
 *   PlayerAuthInputPacket.position.y    += offset  (fixes invisible collision)
 */
public class PositionTranslator {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");
    private final BedrockHeightOffset plugin;

    public PositionTranslator(BedrockHeightOffset plugin) {
        this.plugin = plugin;
    }

    // ── Outbound ──────────────────────────────────────────────────────────────

    public Object translateMovePlayer(Object packet, PlayerOffsetData data) {
        return translateVec3fField(packet, "position", data, false);
    }

    public Object translateRespawn(Object packet, PlayerOffsetData data) {
        return translateVec3fField(packet, "position", data, false);
    }

    public Object translateLevelEvent(Object packet, PlayerOffsetData data) {
        return translateVec3fField(packet, "position", data, false);
    }

    public Object translateParticle(Object packet, PlayerOffsetData data) {
        return translateVec3fField(packet, "position", data, false);
    }

    public Object translateChunkPublisher(Object packet, PlayerOffsetData data) {
        // NetworkChunkPublisherUpdatePacket has a Vector3i position
        try {
            Object pos = getField(packet, "position");
            if (pos == null) return packet;

            int javaY    = ((Number) invokeMethod(pos, "getY")).intValue();
            int bedrockY = (int) data.toBedrockY(javaY);

            Object newPos = newVector3i(
                ((Number) invokeMethod(pos, "getX")).intValue(),
                bedrockY,
                ((Number) invokeMethod(pos, "getZ")).intValue()
            );
            setField(packet, "position", newPos);

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateChunkPublisher error: " + e.getMessage());
        }
        return packet;
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    /**
     * PlayerAuthInputPacket arrives from the Bedrock client with bedrockY.
     * We translate it back to javaY before Geyser reads it and forwards to the Java server.
     * This is the fix for the "invisible collision" symptom.
     */
    public Object translatePlayerAuthInput(Object packet, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, "position");
            if (pos == null) return packet;

            float bedrockY = ((Number) invokeMethod(pos, "getY")).floatValue();
            float javaY    = (float) data.toJavaY(bedrockY);

            float x = ((Number) invokeMethod(pos, "getX")).floatValue();
            float z = ((Number) invokeMethod(pos, "getZ")).floatValue();

            Object newPos = newVector3f(x, javaY, z);
            setField(packet, "position", newPos);

            plugin.getPluginConfig().debugLog(String.format(
                "AuthInput Y: %.2f (bedrock) → %.2f (java) | offset=%d",
                bedrockY, javaY, data.getOffset()
            ));

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translatePlayerAuthInput error: " + e.getMessage());
        }
        return packet;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generic helper: reads a Vector3f field, applies Y offset, writes back.
     * inbound=true adds offset, inbound=false subtracts offset.
     */
    private Object translateVec3fField(Object packet, String fieldName,
                                        PlayerOffsetData data, boolean inbound) {
        try {
            Object pos = getField(packet, fieldName);
            if (pos == null) return packet;

            float x       = ((Number) invokeMethod(pos, "getX")).floatValue();
            float currentY = ((Number) invokeMethod(pos, "getY")).floatValue();
            float z       = ((Number) invokeMethod(pos, "getZ")).floatValue();

            float newY = inbound
                ? (float) data.toJavaY(currentY)
                : (float) data.toBedrockY(currentY);

            setField(packet, fieldName, newVector3f(x, newY, z));

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog(
                "translateVec3fField(" + fieldName + ") error: " + e.getMessage()
            );
        }
        return packet;
    }

    private Object newVector3f(float x, float y, float z) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3f");
        Method from  = cls.getMethod("from", float.class, float.class, float.class);
        return from.invoke(null, x, y, z);
    }

    private Object newVector3i(int x, int y, int z) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3i");
        Method from  = cls.getMethod("from", int.class, int.class, int.class);
        return from.invoke(null, x, y, z);
    }
}