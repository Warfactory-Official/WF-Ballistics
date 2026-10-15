package com.wf.wflib.pda.drone;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Drones app: the held PDA's linked bodies, in channel order; nearest permitted dispatcher. */
public record DroneStatusPacket(List<Entry> drones, Optional<Pad> pad) implements CustomPacketPayload {
    public static final Type<DroneStatusPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_drone_status"));
    public static final StreamCodec<FriendlyByteBuf, DroneStatusPacket> STREAM_CODEC = StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list(PdaDrones.CAP)), DroneStatusPacket::drones,
            ByteBufCodecs.optional(Pad.STREAM_CODEC), DroneStatusPacket::pad, DroneStatusPacket::new);
    private static final StreamCodec<ByteBuf, List<String>> KINDS =
            ByteBufCodecs.stringUtf8(16).apply(ByteBufCodecs.list(16));

    /** @param offers {@code DroneTask.Kind} ids */
    public record Pad(BlockPos pos, int distance, List<String> offers) {
        public static final StreamCodec<FriendlyByteBuf, Pad> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Pad::pos, ByteBufCodecs.VAR_INT, Pad::distance, KINDS, Pad::offers, Pad::new);
    }

    /**
     * @param feedId 0 = no camera feed
     * @param name callsign; unknown => UUID prefix
     * @param status short state: flight state, ONLINE, ASLEEP, NO LINK, ...
     * @param task current program step label; empty = none
     * @param fuel 0..1
     * @param link 0..1 datalink quality at this distance
     * @param distance blocks from the player; -1 = not loaded (nothing below status)
     * @param refusal empty = CONNECT allowed, else why not
     * @param offers {@code DroneTask.Kind} ids this body flies on its own; empty = manual only
     */
    public record Entry(UUID id, int feedId, String name, String status, String task, float fuel, boolean electric,
                        float link, List<RemoteBody.Cell> cells, List<String> ammo, int distance, String refusal,
                        List<String> offers) {
        private static final StreamCodec<FriendlyByteBuf, RemoteBody.Cell> CELL = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(24), RemoteBody.Cell::label, ByteBufCodecs.FLOAT, RemoteBody.Cell::ratio,
                RemoteBody.Cell::new);
        public static final StreamCodec<FriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.of((buf, e) -> {
            buf.writeUUID(e.id);
            buf.writeVarInt(e.feedId);
            buf.writeUtf(e.name, 64);
            buf.writeUtf(e.status, 24);
            buf.writeUtf(e.task, 96);
            buf.writeFloat(e.fuel);
            buf.writeBoolean(e.electric);
            buf.writeFloat(e.link);
            CELL.apply(ByteBufCodecs.list(24)).encode(buf, e.cells);
            ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(8)).encode(buf, e.ammo);
            buf.writeVarInt(e.distance);
            buf.writeUtf(e.refusal, 64);
            KINDS.encode(buf, e.offers);
        }, buf -> new Entry(buf.readUUID(), buf.readVarInt(), buf.readUtf(64), buf.readUtf(24), buf.readUtf(96),
                buf.readFloat(), buf.readBoolean(), buf.readFloat(), CELL.apply(ByteBufCodecs.list(24)).decode(buf),
                ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(8)).decode(buf), buf.readVarInt(),
                buf.readUtf(64), KINDS.decode(buf)));

        public boolean loaded() {
            return distance >= 0;
        }
    }

    @Override
    public Type<DroneStatusPacket> type() {
        return TYPE;
    }
}
