package fr.buildtheearth.bedrockheightoffset.core;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre central des offsets Y par joueur.
 * Thread-safe via ConcurrentHashMap + données volatiles.
 */
public class OffsetRegistry {

    private final Map<UUID, PlayerOffsetData> registry = new ConcurrentHashMap<>();

    public PlayerOffsetData register(UUID uuid, String name, boolean isBedrock, double javaY) {
        PlayerOffsetData data = new PlayerOffsetData(uuid, name, isBedrock, javaY);
        registry.put(uuid, data);
        return data;
    }

    public void unregister(UUID uuid) {
        registry.remove(uuid);
    }

    public PlayerOffsetData get(UUID uuid) {
        return registry.get(uuid);
    }

    public boolean isRegistered(UUID uuid) {
        return registry.containsKey(uuid);
    }

    public Collection<PlayerOffsetData> all() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public int size() {
        return registry.size();
    }

    /**
     * @return Y Bedrock depuis Y Java, ou javaY si joueur non enregistré
     */
    public double toBedrockY(UUID uuid, double javaY) {
        PlayerOffsetData data = registry.get(uuid);
        return data != null ? data.toBedrockY(javaY) : javaY;
    }

    /**
     * @return Y Java depuis Y Bedrock, ou bedrockY si joueur non enregistré
     */
    public double toJavaY(UUID uuid, double bedrockY) {
        PlayerOffsetData data = registry.get(uuid);
        return data != null ? data.toJavaY(bedrockY) : bedrockY;
    }

    /**
     * @return offset actuel, ou 0 si non enregistré
     */
    public int getOffset(UUID uuid) {
        PlayerOffsetData data = registry.get(uuid);
        return data != null ? data.getOffset() : 0;
    }

    public void clear() {
        registry.clear();
    }
}