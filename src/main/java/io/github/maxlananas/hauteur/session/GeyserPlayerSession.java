package io.github.maxlananas.hauteur.session;

import io.github.maxlananas.hauteur.pipeline.PlayerSession;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.session.GeyserSession;

/** {@link PlayerSession} over a live Geyser session. Every call delegates; nothing is cached here. */
public final class GeyserPlayerSession implements PlayerSession {
    private final GeyserSession session;

    public GeyserPlayerSession(GeyserSession session) {
        this.session = java.util.Objects.requireNonNull(session);
    }

    @Override
    public String bedrockUsername() {
        return session.bedrockUsername();
    }

    @Override
    public boolean isSpawned() {
        return session.isSpawned();
    }

    @Override
    public boolean hasPlayerEntity() {
        return session.getPlayerEntity() != null;
    }

    @Override
    public Vector3f playerPosition() {
        return session.getPlayerEntity().position();
    }

    @Override
    public float playerYaw() {
        return session.getPlayerEntity().getYaw();
    }

    @Override
    public float playerPitch() {
        return session.getPlayerEntity().getPitch();
    }

    @Override
    public void sendMessage(String message) {
        session.sendMessage(message);
    }

    @Override
    public int blockAt(int x, int y, int z) {
        // Through the shifted world manager: the argument is a client-frame Y, the read is real.
        return session.getGeyser().getWorldManager().getBlockAt(session, x, y, z);
    }
}
