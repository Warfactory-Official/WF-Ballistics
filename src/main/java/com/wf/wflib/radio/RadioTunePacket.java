package com.wf.wflib.radio;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.ControllableDrone;
import com.wf.wflib.drone.DroneTracker;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.drone.PdaDrones;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Tune {@code carrier}: sender aboard it, or holding a PDA linked to it (not classified). */
public record RadioTunePacket(UUID carrier, String station) implements CustomPacketPayload {
    public static final int MAX_STATION = 64;
    public static final Type<RadioTunePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "radio_tune"));
    public static final StreamCodec<FriendlyByteBuf, RadioTunePacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RadioTunePacket::carrier, ByteBufCodecs.stringUtf8(MAX_STATION),
            RadioTunePacket::station, RadioTunePacket::new);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> tune(player, carrier, station));
        }
    }

    /** @return true if tuned. Gametest seam. */
    public static boolean tune(ServerPlayer player, UUID id, String station) {
        Entity e = player.serverLevel().getEntity(id);
        if (!(e instanceof RadioCarrier carrier) || carrier.radioVoice() == null
                || !RadioCarrier.validStation(station) || !allowed(player, e)) {
            return false;
        }
        carrier.tuneRadio(station);
        return true;
    }

    private static boolean allowed(ServerPlayer player, Entity e) {
        if (player.getRootVehicle() == e.getRootVehicle() && player.getVehicle() != null) {
            return true;
        }
        if (!PdaDrones.channels(Pda.heldStack(player)).has(FeedTarget.ofDrone(e.getUUID()))) {
            return false;
        }
        ControllableDrone body = DroneTracker.byId(player.serverLevel(), e.getUUID());
        return body == null || !body.orders().isClassified();
    }

    @Override
    public Type<RadioTunePacket> type() {
        return TYPE;
    }
}
