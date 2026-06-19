package fr.buildtheearth.bedrockheightoffset.translation;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;

import static fr.buildtheearth.bedrockheightoffset.translation.ChunkTranslator.*;

public class PositionTranslator {

    private final BedrockHeightOffset plugin;

    public PositionTranslator(BedrockHeightOffset plugin) {
        this.plugin = plugin;
    }

    public Object translateMovePlayer(Object packet, PlayerOffsetData data) {
        return shiftVec3f(packet, "position", data, false);
    }

    public Object translateRespawn(Object packet, PlayerOffsetData data) {
        return shiftVec3f(packet, "position", data, false);
    }

    public Object translateLevelEvent(Object packet, PlayerOffsetData data) {
        return shiftVec3f(packet, "position", data, false);
    }

    public Object translateParticle(Object packet, PlayerOffsetData data) {
        return shiftVec3f(packet, "position", data, false);
    }

    public Object translateChunkPublisher(Object packet, PlayerOffsetData data) {
        try {
            Object pos   = getField(packet, "position");
            if (pos == null) return packet;
            int bedrockY = (int) data.toBedrockY(iGet(invokeMethod(pos, "getY")));
            setField(packet, "position", newVec3i(
                iGet(invokeMethod(pos, "getX")), bedrockY, iGet(invokeMethod(pos, "getZ"))
            ));
        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translateChunkPublisher: " + e.getMessage());
        }
        return packet;
    }

    /**
     * PlayerAuthInputPacket: the Bedrock client sends its own position (bedrockY).
     * We must translate it to javaY before Geyser forwards it to the Java server.
     * This is the fix for the "invisible collision / falling through" symptom.
     */
    public Object translatePlayerAuthInput(Object packet, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, "position");
            if (pos == null) return packet;

            float bedrockY = fGet(invokeMethod(pos, "getY"));
            float javaY    = (float) data.toJavaY(bedrockY);

            setField(packet, "position", newVec3f(
                fGet(invokeMethod(pos, "getX")),
                javaY,
                fGet(invokeMethod(pos, "getZ"))
            ));

            plugin.getPluginConfig().debugLog(String.format(
                "AuthInput Y: %.2f→%.2f offset=%d", bedrockY, javaY, data.getOffset()
            ));

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("translatePlayerAuthInput: " + e.getMessage());
        }
        return packet;
    }

    private Object shiftVec3f(Object packet, String field, PlayerOffsetData data, boolean inbound) {
        try {
            Object pos = getField(packet, field);
            if (pos == null) return packet;

            float x = fGet(invokeMethod(pos, "getX"));
            float y = fGet(invokeMethod(pos, "getY"));
            float z = fGet(invokeMethod(pos, "getZ"));

            float newY = inbound ? (float) data.toJavaY(y) : (float) data.toBedrockY(y);
            setField(packet, field, newVec3f(x, newY, z));

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("shiftVec3f(" + field + "): " + e.getMessage());
        }
        return packet;
    }
}