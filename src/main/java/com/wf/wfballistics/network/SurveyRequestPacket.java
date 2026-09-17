package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.orbital.survey.SurveyRaster;
import com.wf.wfballistics.orbital.survey.SurveyTile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SurveyRequestPacket(long netId, int tileX, int tileZ) implements CustomPacketPayload {

    public static final Type<SurveyRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "survey_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurveyRequestPacket> STREAM_CODEC =
            StreamCodec.of((buf, pkt) -> {
                buf.writeLong(pkt.netId);
                buf.writeVarInt(pkt.tileX);
                buf.writeVarInt(pkt.tileZ);
            }, buf -> new SurveyRequestPacket(buf.readLong(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<SurveyRequestPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            SurveyTile tile = SurveyRaster.get(level).existing(netId, tileX, tileZ);
            if (tile == null) {
                return;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new SurveyTilePacket(netId, tileX, tileZ, tile.stamped(), tile.imaged(),
                            tile.material(), tile.height(), tile.changed()));
        });
    }
}
