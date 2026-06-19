package fr.buildtheearth.bedrockheightoffset.translation;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;

import static fr.buildtheearth.bedrockheightoffset.translation.ChunkTranslator.*;

public class EntityTranslator {

    private final BedrockHeightOffset plugin;

    public EntityTranslator(BedrockHeightOffset plugin) {
        this.plugin = plugin;
    }

    public Object translateMoveAbsolute(Object packet, PlayerOffsetData data) {
        return shiftPosition(packet, data);
    }

    public Object translateAddEntity(Object packet, PlayerOffsetData data) {
        return shiftPosition(packet, data);
    }

    public Object translateAddPlayer(Object packet, PlayerOffsetData data) {
        return shiftPosition(packet, data);
    }

    private Object shiftPosition(Object packet, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, "position");
            if (pos == null) return packet;

            float x    = fGet(invokeMethod(pos, "getX"));
            float y    = fGet(invokeMethod(pos, "getY"));
            float z    = fGet(invokeMethod(pos, "getZ"));
            float newY = (float) data.toBedrockY(y);

            setField(packet, "position", newVec3f(x, newY, z));

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("EntityTranslator: " + e.getMessage());
        }
        return packet;
    }
}