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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BedrockPacketInterceptor extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "bho_y_interceptor";

    private final UUID               playerUuid;
    private final OffsetRegistry     registry;
    private final BedrockHeightOffset plugin;
    private final ChunkTranslator    chunk;
    private final PositionTranslator position;
    private final EntityTranslator   entity;

    // Log the first 50 outbound object types to understand what we receive
    private final AtomicLong  msgCount    = new AtomicLong(0);
    private final AtomicBoolean logged50  = new AtomicBoolean(false);

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

        long count = msgCount.incrementAndGet();

        // Log first 100 messages to see what types arrive here
        if (count <= 100 && plugin.getPluginConfig().isDebug()) {
            plugin.getLogger().info("[BHO][PIPE] write #" + count
                + " type=" + msg.getClass().getName()
                + " simple=" + msg.getClass().getSimpleName());
        }

        PlayerOffsetData data = registry.get(playerUuid);
        if (data != null && data.getOffset() != 0) {
            String cls = msg.getClass().getSimpleName();
            msg = switch (cls) {
                case "LevelChunkPacket"                  -> chunk.translateChunk(msg, data);
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

        super.write(ctx, msg, promise);
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