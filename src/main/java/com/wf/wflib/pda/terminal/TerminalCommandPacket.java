package com.wf.wflib.pda.terminal;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Terminal app line -> {@link PdaTerminal#run} -> {@link TerminalReplyPacket}. */
public record TerminalCommandPacket(String line) implements CustomPacketPayload {
    public static final Type<TerminalCommandPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_terminal_command"));
    public static final StreamCodec<FriendlyByteBuf, TerminalCommandPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(96), TerminalCommandPacket::line, TerminalCommandPacket::new);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> PacketDistributor.sendToPlayer(player,
                    new TerminalReplyPacket(PdaTerminal.run(player, line))));
        }
    }

    @Override
    public Type<TerminalCommandPacket> type() {
        return TYPE;
    }
}
