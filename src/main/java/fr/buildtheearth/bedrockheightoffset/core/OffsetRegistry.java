package fr.buildtheearth.bedrockheightoffset.core;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    public double toBedrockY(UUID uuid, double javaY) {
        PlayerOffsetData data = registry.get(uuid);
        return data != null ? data.toBedrockY(javaY) : javaY;
    }

    public double toJavaY(UUID uuid, double bedrockY) {
        PlayerOffsetData data = registry.get(uuid);
        return data != null ? data.toJavaY(bedrockY) : bedrockY;
    }

    public int getOffset(UUID uuid) {
        PlayerOffsetData data = registry.get(uuid);
        return data != null ? data.getOffset() : 0;
    }

    public void clear() {
        registry.clear();
    }
}