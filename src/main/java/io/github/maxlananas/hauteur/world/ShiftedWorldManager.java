package io.github.maxlananas.hauteur.world;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.erosion.util.BlockPositionIterator;
import org.geysermc.geyser.level.WorldManager;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.setting.Difficulty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Wraps the platform's {@link WorldManager} so that the session-aware world reads Geyser performs (collision
 * corrections, swimming and climbing checks, container re-opening, decorated pots) resolve through the calling
 * session's current window offset.
 *
 * <p>Whether a read needs the offset depends on where the delegate reads from, and Geyser decides that the same
 * way: a platform with its own world ({@code hasOwnChunkCache()}, Geyser-Spigot) and a session with an active
 * Erosion backend read the REAL world, so a window-space Y is shifted up by the offset. A standalone session without
 * Erosion reads Geyser's session chunk cache, which already holds the windowed chunks in window space, so its reads
 * are passed through unchanged. Shifting those reads too would sample the chunk 2 offsets away, which is the
 * corrective movement this class exists to prevent. See {@link #readShift(GeyserSession)}.</p>
 *
 * <p>Every read path is overridden: single reads ({@code blockAt}, {@code getBlockAt}), async reads (delegated to the
 * delegate's own async method, so an Erosion backend stays asynchronous), batch reads ({@code getBlocksAt}, which keep
 * their single batch request), and the pot lookup. Writes never go through here: block placement goes through the
 * server, which receives translated coordinates at the packet layer.</p>
 */
public final class ShiftedWorldManager extends WorldManager {
    private final WorldManager delegate;
    /** Current window offset of a session; 0 for sessions without a window. Kept as a function to stay testable. */
    private final ToIntFunction<GeyserSession> offsetOf;
    /** Whether a session's reads go to the real world. See {@link #readsRealWorld(WorldManager)}. */
    private final Predicate<GeyserSession> readsRealWorld;

    public ShiftedWorldManager(WorldManager delegate, ToIntFunction<GeyserSession> offsetOf,
                               Predicate<GeyserSession> readsRealWorld) {
        this.delegate = delegate;
        this.offsetOf = offsetOf;
        this.readsRealWorld = readsRealWorld;
    }

    /**
     * The predicate Geyser itself uses to decide whether reads reach the real world: a platform that owns its
     * world, or a session whose Erosion backend is active. Otherwise reads hit the session chunk cache.
     */
    public static Predicate<GeyserSession> readsRealWorld(WorldManager delegate) {
        boolean ownWorld = delegate.hasOwnChunkCache();
        return session -> ownWorld || session.getErosionHandler().isActive();
    }

    public WorldManager delegate() {
        return delegate;
    }

    /**
     * The amount to add to a read's Y. Zero when the window is off, and zero when the delegate reads the
     * window-space chunk cache (there the window-space Y is already the right index).
     */
    int readShift(GeyserSession session) {
        int offset = offsetOf.applyAsInt(session);
        return offset != 0 && readsRealWorld.test(session) ? offset : 0;
    }

    @Override
    public int getBlockAt(GeyserSession session, int x, int y, int z) {
        return delegate.getBlockAt(session, x, y + readShift(session), z);
    }

    @Override
    public CompletableFuture<Integer> getBlockAtAsync(GeyserSession session, int x, int y, int z) {
        return delegate.getBlockAtAsync(session, x, y + readShift(session), z);
    }

    @Override
    public int[] getBlocksAt(GeyserSession session, BlockPositionIterator iter) {
        int shift = readShift(session);
        if (shift == 0) {
            return delegate.getBlocksAt(session, iter);
        }
        if (iter.getMaxIterations() == 0) {
            return new int[0]; // nothing to shift; an empty box cannot be rebuilt with fromMinMax
        }
        return delegate.getBlocksAt(session, shifted(iter, shift));
    }

    @Override
    public void getDecoratedPotData(GeyserSession session, Vector3i pos, Consumer<List<String>> apply) {
        delegate.getDecoratedPotData(session, Vector3i.from(pos.getX(), pos.getY() + readShift(session), pos.getZ()),
            apply);
    }

    /**
     * A fresh iterator over the same box, with Y moved up by {@code shift}. Same extent, so the batch result has the
     * same length and the same index for every position, and the delegate still answers it in one request.
     *
     * <p>The box is recovered by walking the iterator once, because the iterator keeps its bounds private. The
     * caller must pass a non-empty iterator; {@link #getBlocksAt} checks that first.</p>
     */
    static BlockPositionIterator shifted(BlockPositionIterator iter, int shift) {
        iter.reset();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (; iter.hasNext(); iter.next()) {
            minX = Math.min(minX, iter.getX());
            minY = Math.min(minY, iter.getY());
            minZ = Math.min(minZ, iter.getZ());
            maxX = Math.max(maxX, iter.getX());
            maxY = Math.max(maxY, iter.getY());
            maxZ = Math.max(maxZ, iter.getZ());
        }
        iter.reset();
        return BlockPositionIterator.fromMinMax(minX, minY + shift, minZ, maxX, maxY + shift, maxZ);
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
