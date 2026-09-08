package fr.buildtheearth.skywindow.world;

import fr.buildtheearth.skywindow.SkyWindowCore;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.erosion.util.BlockPositionIterator;
import org.geysermc.geyser.level.WorldManager;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.setting.Difficulty;

import java.util.List;
import java.util.function.Consumer;

/**
 * Wraps the platform's {@link WorldManager} so that every direct, session-aware world read Geyser
 * performs (collision corrections, container re-opening, decorated pots) resolves through the
 * calling session's current window offset. This is the fix for the root cause of the corrective
 * movement ("rubber-banding") that motivated this rewrite: without it, on Geyser-Spigot the
 * {@code CollisionManager} samples the REAL world at window coordinates while a window is active and
 * conjures phantom collision boxes into the client, which then fights its own physics.
 *
 * <p>The reads that reach this class carry window-space Y; the delegate's world is real-space, so
 * reads add the offset and writes never happen through here (block placement goes through the server,
 * which already receives translated coordinates at the packet layer). The final overloads of
 * {@code blockAt}/{@code getBlockAt}/{@code getBlockAtAsync} in the base class route through the
 * abstract single-coordinate method, so overriding that one method (plus the batch iterator and the
 * pot lookup, which take coordinates directly) covers the entire read surface.</p>
 */
public final class ShiftedWorldManager extends WorldManager {
    private final WorldManager delegate;
    private final SkyWindowCore core;

    public ShiftedWorldManager(WorldManager delegate, SkyWindowCore core) {
        this.delegate = delegate;
        this.core = core;
    }

    public WorldManager delegate() {
        return delegate;
    }

    @Override
    public int getBlockAt(GeyserSession session, int x, int y, int z) {
        int offset = core.currentOffset(session);
        return offset == 0
            ? delegate.getBlockAt(session, x, y, z)
            : delegate.getBlockAt(session, x, y + offset, z);
    }

    @Override
    public int[] getBlocksAt(GeyserSession session, BlockPositionIterator iter) {
        int offset = core.currentOffset(session);
        if (offset == 0) {
            return delegate.getBlocksAt(session, iter);
        }
        int[] blocks = new int[iter.getMaxIterations()];
        for (; iter.hasNext(); iter.next()) {
            blocks[iter.getIteration()] = delegate.getBlockAt(session, iter.getX(), iter.getY() + offset, iter.getZ());
        }
        return blocks;
    }

    @Override
    public void getDecoratedPotData(GeyserSession session, Vector3i pos, Consumer<List<String>> apply) {
        int offset = core.currentOffset(session);
        if (offset == 0) {
            delegate.getDecoratedPotData(session, pos, apply);
        } else {
            delegate.getDecoratedPotData(session, Vector3i.from(pos.getX(), pos.getY() + offset, pos.getZ()), apply);
        }
    }

    // Everything below is coordinate-free; delegate unchanged.

    @Override
    public boolean hasOwnChunkCache() {
        return delegate.hasOwnChunkCache();
    }

    @Override
    public GameMode getDefaultGameMode(GeyserSession session) {
        return delegate.getDefaultGameMode(session);
    }

    @Override
    public void setDefaultGameMode(GeyserSession session, GameMode gameMode) {
        delegate.setDefaultGameMode(session, gameMode);
    }

    @Override
    public void setDifficulty(GeyserSession session, Difficulty difficulty) {
        delegate.setDifficulty(session, difficulty);
    }

    @Override
    public String[] getBiomeIdentifiers(boolean withTags) {
        return delegate.getBiomeIdentifiers(withTags);
    }
}
