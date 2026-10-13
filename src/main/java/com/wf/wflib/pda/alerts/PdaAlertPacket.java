package com.wf.wflib.pda.alerts;

import com.wf.wflib.WFLib;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> PDA carrier: one notification. */
public record PdaAlertPacket(Component message, PdaAlert.Tier tier) implements CustomPacketPayload {
    public static final Type<PdaAlertPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_alert"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PdaAlertPacket> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.TRUSTED_STREAM_CODEC, PdaAlertPacket::message,
            ByteBufCodecs.idMapper(i -> PdaAlert.Tier.values()[i], PdaAlert.Tier::ordinal), PdaAlertPacket::tier,
            PdaAlertPacket::new);

    @Override
    public Type<PdaAlertPacket> type() {
        return TYPE;
    }
}
