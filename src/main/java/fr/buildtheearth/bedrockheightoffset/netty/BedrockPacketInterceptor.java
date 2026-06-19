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
 * Netty ChannelDuplexHandler injected between Geyser's packet codec
 * and the RakNet encoder in the Bedrock client's pipeline.
 *
 * Outbound (write): intercepts BedrockPacket objects after Geyser
 * has translated them from Java packets. We mutate Y coordinates
 * before the packet is encoded and sent.
 *
 * Inbound (channelRead): intercepts PlayerAuthInputPacket from the
 * client before Geyser processes it. We translate bedrockY → javaY
 * so the Java server receives correct coordinates.
 *
 * Packets handled outbound:
 *   LevelChunkPacket              — sub-chunk index bytes rewritten
 *   MovePlayerPacket              — position.y -= offset
 *   RespawnPacket                 — position.y -= offset
 *   MoveEntityAbsolutePacket      — position.y -= offset
 *   AddEntityPacket               — position.y -= offset
 *   AddPlayerPacket               — position.y -= offset
 *   UpdateBlockPacket             — blockPosition.y -= offset
 *   BlockEntityDataPacket         — blockPosition.y -= offset
 *   LevelEventPacket              — position.y -= offset
 *   SpawnParticleEffectPacket     — position.y -= offset
 *   NetworkChunkPublisherUpdatePacket — position.y -= offset
 *
 * Packets handled inbound:
 *   PlayerAuthInputPacket         — position.y += offset
 */
public class BedrockPacketInterceptor extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "bho_y_interceptor";

    private final UUID             playerUuid;
    private final OffsetRegistry   registry;
    private final BedrockHeightOffset plugin;

    private final ChunkTranslator    chunk;
    private final PositionTranslator position;
    private final EntityTranslator   entity;

    public BedrockPacketInterceptor(UUID playerUuid, OffsetRegistry registry,
                                    BedrockHeightOffset plugin) {
        this.playerUuid = playerUuid;
        this.registry   = registry;
        this.plugin     = plugin;
        this.chunk      = new ChunkTranslator(plugin);
        this.position   = new PositionTranslator(plugin);
        this.entity     = new EntityTranslator(plugin);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {

        PlayerOffsetData data = registry.get(playerUuid);
        if (data != null && data.getOffset() != 0) {
            msg = translateOutbound(msg, data);
        }
        super.write(ctx, msg, promise);
    }

    private Object translateOutbound(Object pkt, PlayerOffsetData data) {
        return switch (pkt.getClass().getSimpleName()) {
            case "LevelChunkPacket"                  -> chunk.translateChunk(pkt, data);
            case "MovePlayerPacket"                  -> position.translateMovePlayer(pkt, data);
            case "RespawnPacket"                     -> position.translateRespawn(pkt, data);
            case "MoveEntityAbsolutePacket"          -> entity.translateMoveAbsolute(pkt, data);
            case "AddEntityPacket"                   -> entity.translateAddEntity(pkt, data);
            case "AddPlayerPacket"                   -> entity.translateAddPlayer(pkt, data);
            case "UpdateBlockPacket"                 -> chunk.translateBlockUpdate(pkt, data);
            case "BlockEntityDataPacket"             -> chunk.translateBlockEntity(pkt, data);
            case "LevelEventPacket"                  -> position.translateLevelEvent(pkt, data);
            case "SpawnParticleEffectPacket"         -> position.translateParticle(pkt, data);
            case "NetworkChunkPublisherUpdatePacket" -> position.translateChunkPublisher(pkt, data);
            default                                  -> pkt;
        };
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        PlayerOffsetData data = registry.get(playerUuid);
        if (data != null && data.getOffset() != 0
            && msg.getClass().getSimpleName().equals("PlayerAuthInputPacket")) {
            msg = position.translatePlayerAuthInput(msg, data);
        }
        super.channelRead(ctx, msg);
    }
}