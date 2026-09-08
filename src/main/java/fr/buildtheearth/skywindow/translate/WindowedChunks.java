package fr.buildtheearth.skywindow.translate;

import fr.buildtheearth.skywindow.chunk.SectionCodec;
import fr.buildtheearth.skywindow.window.WindowRules;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds a {@code ClientboundLevelChunkWithLight} packet so that its contents appear at
 * {@code realY - offset} while staying inside the section array of the REAL dimension. Geyser maps
 * payload slot {@code w} to client subchunk {@code w + javaMinSection - clientMinSection}, so moving
 * each section to {@code w = r - offset/16} lands every block at exactly {@code realY - offset} in
 * client space - which is what the rest of Geyser (block entities, chunk cache, subchunk indices) then
 * consistently works with.
 */
public final class WindowedChunks {
    private WindowedChunks() {
    }

    public record Params(int offset, int javaMinY, int sectionCount) {
        /** {@code javaHeightY} in blocks; Geyser's per-chunk section array has {@code height/16} slots. */
        public static Params of(int offset, int javaMinY, int javaHeightY) {
            return new Params(offset, javaMinY, Math.max(1, javaHeightY / WindowRules.SECTION));
        }
    }

    /** Packet plus the anomaly flag from the tolerant section walk. */
    public record Outcome(ClientboundLevelChunkWithLightPacket packet, boolean anomaly) {
    }

    /**
     * Never throws: the section walk pads/truncates to exactly the section count Geyser expects, so a
     * short, long or mid-payload-corrupt chunk degrades to (possibly holed) air instead of a decoder
     * exception on Geyser's packet thread. The flag reports that normalization happened.
     */
    public static Outcome window(ClientboundLevelChunkWithLightPacket packet, Params p) {
        int shiftSections = p.offset() >> 4;
        SectionCodec.Result resliced =
            SectionCodec.resliceTolerant(packet.getChunkData(), p.sectionCount(), shiftSections, p.sectionCount());
        BlockEntityInfo[] out = shiftBlockEntities(packet.getBlockEntities(), p);
        ClientboundLevelChunkWithLightPacket w = packet.withChunkData(resliced.data());
        if (out != null) {
            w = w.withBlockEntities(out);
        }
        return new Outcome(w, resliced.anomaly());
    }

    /**
     * Block entity Y moves with the blocks. Entries whose shifted Y would index Geyser's per-chunk
     * section array out of bounds ({@code javaChunks[(y >> 4) - javaMinSection]}) are dropped: those
     * blocks are outside the payload window and the client could not show their block entities anyway.
     *
     * @return the replacement array, or null when nothing needed changing
     */
    public static BlockEntityInfo[] shiftBlockEntities(BlockEntityInfo[] input, Params p) {
        if (p.offset() == 0 || input.length == 0) {
            return null;
        }
        int maxIndex = p.sectionCount();
        int javaMinSection = p.javaMinY() >> 4;
        List<BlockEntityInfo> kept = new ArrayList<>(input.length);
        for (BlockEntityInfo info : input) {
            int shiftedY = info.getY() - p.offset();
            int sectionIndex = (shiftedY >> 4) - javaMinSection;
            if (sectionIndex < 0 || sectionIndex >= maxIndex) {
                continue;
            }
            kept.add(withY(info, shiftedY));
        }
        return kept.toArray(new BlockEntityInfo[0]);
    }

    private static BlockEntityInfo withY(BlockEntityInfo info, int y) {
        return new BlockEntityInfo(info.getX(), y, info.getZ(), info.getType(), info.getNbt());
    }
}
