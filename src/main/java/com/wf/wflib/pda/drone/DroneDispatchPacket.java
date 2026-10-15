package com.wf.wflib.pda.drone;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.DroneTask;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;
import java.util.UUID;

/** Drones app DISPATCH -> the selected linked body, else nearest pad; reply + fresh status back. */
public record DroneDispatchPacket(String kind, double x, double y, double z, Optional<UUID> drone)
        implements CustomPacketPayload {
    public static final Type<DroneDispatchPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_drone_dispatch"));
    public static final StreamCodec<FriendlyByteBuf, DroneDispatchPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(16), DroneDispatchPacket::kind, ByteBufCodecs.DOUBLE, DroneDispatchPacket::x,
            ByteBufCodecs.DOUBLE, DroneDispatchPacket::y, ByteBufCodecs.DOUBLE, DroneDispatchPacket::z,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), DroneDispatchPacket::drone, DroneDispatchPacket::new);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> {
                String line = PdaDrones.dispatch(player, DroneTask.Kind.byId(kind), new Vec3(x, y, z), drone);
                PacketDistributor.sendToPlayer(player, new DroneDispatchReplyPacket(line));
                PacketDistributor.sendToPlayer(player, PdaDrones.status(player));
            });
        }
    }

    @Override
    public Type<DroneDispatchPacket> type() {
        return TYPE;
    }
}
