package com.wf.wflib.pda.terminal;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** {@link TerminalCommandPacket} output; {@code err:} prefix = refused. */
public record TerminalReplyPacket(List<String> lines) implements CustomPacketPayload {
    public static final Type<TerminalReplyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_terminal_reply"));
    public static final StreamCodec<FriendlyByteBuf, TerminalReplyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(160).apply(ByteBufCodecs.list(256)), TerminalReplyPacket::lines,
            TerminalReplyPacket::new);

    @Override
    public Type<TerminalReplyPacket> type() {
        return TYPE;
    }
}
