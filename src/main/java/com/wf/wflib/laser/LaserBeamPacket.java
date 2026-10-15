package com.wf.wflib.laser;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/** One tick of beam, world space. */
public record LaserBeamPacket(Vector3f from, Vector3f to) implements CustomPacketPayload {
    public static final Type<LaserBeamPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "laser_beam"));
    public static final StreamCodec<FriendlyByteBuf, LaserBeamPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VECTOR3F, LaserBeamPacket::from, ByteBufCodecs.VECTOR3F, LaserBeamPacket::to,
            LaserBeamPacket::new);

    @Override
    public Type<LaserBeamPacket> type() {
        return TYPE;
    }
}
