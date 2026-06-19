package fr.buildtheearth.bedrockheightoffset.netty;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import fr.buildtheearth.bedrockheightoffset.translation.ChunkTranslator;
import fr.buildtheearth.bedrockheightoffset.translation.EntityTranslator;
import fr.buildtheearth.bedrockheightoffset.translation.PositionTranslator;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.UUID;

/**
 * Injected into the Geyser ↔ Bedrock client Netty pipeline.
 *
 * Pipeline position (outbound = server→client direction):
 *   ... → [BHO_INTERCEPTOR] → BedrockEncoder → RakNet → Client
 *
 * We override write() to intercept outbound BedrockPackets.
 * The objects arriving here are already deserialized BedrockPacket instances
 * (Geyser has already translated them from Java packets).
 * We mutate their Y fields before they get encoded and sent.
 *
 * For inbound (client→server): we override channelRead() to intercept
 * PlayerAuthInputPacket and translate bedrockY → javaY before Geyser
 * processes it. This fixes the "invisible collision" — the server was
 * receiving the wrong Y position from the Bedrock client.
 */
public class BedrockPacketInterceptor extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "bho_interceptor";

    private final UUID             playerUuid;
    private final OffsetRegistry   registry;
    private final BedrockHeightOffset plugin;

    private final ChunkTranslator    chunkTranslator;
    private final PositionTranslator positionTranslator;
    private final EntityTranslator   entityTranslator;

    public BedrockPacketInterceptor(UUID playerUuid,
                                    OffsetRegistry registry,
                                    BedrockHeightOffset plugin) {
        this.playerUuid         = playerUuid;
        this.registry           = registry;
        this.plugin             = plugin;
        this.chunkTranslator    = new ChunkTranslator(plugin);
        this.positionTranslator = new PositionTranslator(plugin);
        this.entityTranslator   = new EntityTranslator(plugin);
    }

    // ── Outbound: Geyser → Bedrock client ────────────────────────────────────

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        PlayerOffsetData data = registry.get(playerUuid);
        if (data == null || data.getOffset() == 0) {
            super.write(ctx, msg, promise);
            return;
        }

        Object translated = translate(msg, data);
        super.write(ctx, translated, promise);
    }

    private Object translate(Object packet, PlayerOffsetData data) {
        String className = packet.getClass().getSimpleName();

        return switch (className) {
            case "LevelChunkPacket"         -> chunkTranslator.translateChunk(packet, data);
            case "MovePlayerPacket"         -> positionTranslator.translateMovePlayer(packet, data);
            case "RespawnPacket"            -> positionTranslator.translateRespawn(packet, data);
            case "MoveEntityAbsolutePacket" -> entityTranslator.translateMoveAbsolute(packet, data);
            case "AddEntityPacket"          -> entityTranslator.translateAddEntity(packet, data);
            case "AddPlayerPacket"          -> entityTranslator.translateAddPlayer(packet, data);
            case "UpdateBlockPacket"        -> chunkTranslator.translateBlockUpdate(packet, data);
            case "BlockEntityDataPacket"    -> chunkTranslator.translateBlockEntity(packet, data);
            case "LevelEventPacket"         -> positionTranslator.translateLevelEvent(packet, data);
            case "SpawnParticleEffectPacket"-> positionTranslator.translateParticle(packet, data);
            case "NetworkChunkPublisherUpdatePacket" -> positionTranslator.translateChunkPublisher(packet, data);
            default -> packet;
        };
    }

    // ── Inbound: Bedrock client → Geyser ─────────────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        PlayerOffsetData data = registry.get(playerUuid);
        if (data == null || data.getOffset() == 0) {
            super.channelRead(ctx, msg);
            return;
        }

        String className = msg.getClass().getSimpleName();

        // PlayerAuthInputPacket carries the Bedrock client's position.
        // We must translate bedrockY → javaY before Geyser forwards it to Java server.
        if (className.equals("PlayerAuthInputPacket")) {
            msg = positionTranslator.translatePlayerAuthInput(msg, data);
        }

        super.channelRead(ctx, msg);
    }
}