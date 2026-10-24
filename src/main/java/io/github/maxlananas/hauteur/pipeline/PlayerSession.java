package io.github.maxlananas.hauteur.pipeline;

import org.cloudburstmc.math.vector.Vector3f;

/**
 * The slice of a Geyser session that the pipeline uses. Production code adapts a {@code GeyserSession}
 * ({@code session.GeyserPlayerSession}); tests substitute a fake, so the pipeline can run without a Geyser
 * runtime.
 */
public interface PlayerSession {
    String bedrockUsername();

    boolean isSpawned();

    /** Whether the player's entity exists yet. Position accessors are only valid when this is true. */
    boolean hasPlayerEntity();

    /** Position of the player entity in the frame the client currently sees. Only valid with an entity. */
    Vector3f playerPosition();

    float playerYaw();

    float playerPitch();

    void sendMessage(String message);

    /** Block state id at a position in the client frame, as Geyser's world manager reports it. */
    int blockAt(int x, int y, int z);
}
