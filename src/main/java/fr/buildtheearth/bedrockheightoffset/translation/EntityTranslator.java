package fr.buildtheearth.bedrockheightoffset.translation;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;

import java.lang.reflect.Method;
import java.util.logging.Logger;

import static fr.buildtheearth.bedrockheightoffset.translation.ChunkTranslator.*;

/**
 * Translates entity movement packets by applying the Y offset.
 *
 * MoveEntityAbsolutePacket  — absolute teleport of any entity
 * AddEntityPacket            — entity spawn position
 * AddPlayerPacket            — player spawn position (other players)
 */
public class EntityTranslator {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");
    private final BedrockHeightOffset plugin;

    public EntityTranslator(BedrockHeightOffset plugin) {
        this.plugin = plugin;
    }

    public Object translateMoveAbsolute(Object packet, PlayerOffsetData data) {
        return applyOffsetToVec3f(packet, "position", data);
    }

    public Object translateAddEntity(Object packet, PlayerOffsetData data) {
        return applyOffsetToVec3f(packet, "position", data);
    }

    public Object translateAddPlayer(Object packet, PlayerOffsetData data) {
        return applyOffsetToVec3f(packet, "position", data);
    }

    private Object applyOffsetToVec3f(Object packet, String field, PlayerOffsetData data) {
        try {
            Object pos = getField(packet, field);
            if (pos == null) return packet;

            float x = ((Number) invokeMethod(pos, "getX")).floatValue();
            float y = ((Number) invokeMethod(pos, "getY")).floatValue();
            float z = ((Number) invokeMethod(pos, "getZ")).floatValue();

            float newY = (float) data.toBedrockY(y);
            setField(packet, field, newVector3f(x, newY, z));

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("EntityTranslator(" + field + ") error: " + e.getMessage());
        }
        return packet;
    }

    private Object newVector3f(float x, float y, float z) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3f");
        Method from  = cls.getMethod("from", float.class, float.class, float.class);
        return from.invoke(null, x, y, z);
    }
}