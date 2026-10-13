package com.wf.wflib.pda.drone;

import com.wf.wflib.WFLib;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/** Drones app: the held PDA's linked drones, in channel order; nearest permitted dispatcher. */
public record DroneStatusPacket(List<Entry> drones, Optional<Pad> pad) implements CustomPacketPayload {
    public static final Type<DroneStatusPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_drone_status"));
    public static final StreamCodec<FriendlyByteBuf, DroneStatusPacket> STREAM_CODEC = StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list(PdaDrones.CAP)), DroneStatusPacket::drones,
            ByteBufCodecs.optional(Pad.STREAM_CODEC), DroneStatusPacket::pad, DroneStatusPacket::new);

    /** @param offers {@code DroneTask.Kind} ids */
    public record Pad(BlockPos pos, int distance, List<String> offers) {
        public static final StreamCodec<FriendlyByteBuf, Pad> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Pad::pos, ByteBufCodecs.VAR_INT, Pad::distance,
                ByteBufCodecs.stringUtf8(16).apply(ByteBufCodecs.list(16)), Pad::offers, Pad::new);
    }

    /**
     * @param feedId 0 = no camera feed
     * @param name callsign; unloaded => UUID prefix
     * @param state {@code DroneState} name
     * @param battery percent
     * @param task current program step label; empty = none
     * @param distance blocks from the player; -1 = not loaded (no state above)
     */
    public record Entry(int feedId, String name, String state, float battery, String task, int distance) {
        public static final StreamCodec<FriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Entry::feedId, ByteBufCodecs.stringUtf8(64), Entry::name,
                ByteBufCodecs.stringUtf8(16), Entry::state, ByteBufCodecs.FLOAT, Entry::battery,
                ByteBufCodecs.stringUtf8(96), Entry::task, ByteBufCodecs.VAR_INT, Entry::distance, Entry::new);

        public boolean loaded() {
            return distance >= 0;
        }
    }

    @Override
    public Type<DroneStatusPacket> type() {
        return TYPE;
    }
}
