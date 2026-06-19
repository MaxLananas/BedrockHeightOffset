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
import java.util.concurrent.atomic.AtomicLong;

public class BedrockPacketInterceptor extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "bho_y_interceptor";

    private final UUID             playerUuid;
    private final OffsetRegistry   registry;
    private final BedrockHeightOffset plugin;

    private final ChunkTranslator    chunk;
    private final PositionTranslator position;
    private final EntityTranslator   entity;

    // Packet counters for diagnostics
    private final AtomicLong outboundTotal = new AtomicLong();
    private final AtomicLong chunksHandled = new AtomicLong();

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

        if (data != null) {
            String cls = msg.getClass().getSimpleName();
            outboundTotal.incrementAndGet();

            // Always log chunk packets regardless of offset to diagnose the issue
            if (cls.equals("LevelChunkPacket")) {
                chunksHandled.incrementAndGet();
                if (data.getOffset() != 0) {
                    plugin.getPluginConfig().debugLog(String.format(
                        "LevelChunkPacket → offset=%d (%d sec) bY=%.1f",
                        data.getOffset(), data.offsetSections(), data.currentBedrockY()
                    ));
                    msg = chunk.translateChunk(msg, data);
                }
            } else if (data.getOffset() != 0) {
                msg = switch (cls) {
                    case "MovePlayerPacket"                  -> position.translateMovePlayer(msg, data);
                    case "RespawnPacket"                     -> position.translateRespawn(msg, data);
                    case "MoveEntityAbsolutePacket"          -> entity.translateMoveAbsolute(msg, data);
                    case "AddEntityPacket"                   -> entity.translateAddEntity(msg, data);
                    case "AddPlayerPacket"                   -> entity.translateAddPlayer(msg, data);
                    case "UpdateBlockPacket"                 -> chunk.translateBlockUpdate(msg, data);
                    case "BlockEntityDataPacket"             -> chunk.translateBlockEntity(msg, data);
                    case "LevelEventPacket"                  -> position.translateLevelEvent(msg, data);
                    case "SpawnParticleEffectPacket"         -> position.translateParticle(msg, data);
                    case "NetworkChunkPublisherUpdatePacket" -> position.translateChunkPublisher(msg, data);
                    default                                  -> msg;
                };
            }
        }

        super.write(ctx, msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        PlayerOffsetData data = registry.get(playerUuid);
        if (data != null && data.getOffset() != 0) {
            String cls = msg.getClass().getSimpleName();
            if (cls.equals("PlayerAuthInputPacket")) {
                msg = position.translatePlayerAuthInput(msg, data);
            }
        }
        super.channelRead(ctx, msg);
    }

    public long getOutboundTotal()  { return outboundTotal.get();  }
    public long getChunksHandled()  { return chunksHandled.get();  }
}