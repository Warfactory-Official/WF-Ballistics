package com.wf.wflib.nvg;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record NvgActionPacket(Action action) implements CustomPacketPayload {
    public enum Action {
        FLIP, IR, GAIN_UP, GAIN_DOWN
    }

    public static final Type<NvgActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "nvg_action"));
    public static final StreamCodec<ByteBuf, NvgActionPacket> STREAM_CODEC = ByteBufCodecs.idMapper(
            i -> Action.values()[i], Action::ordinal).map(NvgActionPacket::new, NvgActionPacket::action);

    @Override
    public Type<NvgActionPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                NvgServer.act(player, action);
            }
        });
    }
}
