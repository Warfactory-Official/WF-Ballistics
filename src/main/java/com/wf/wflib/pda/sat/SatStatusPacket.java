package com.wf.wflib.pda.sat;

import com.wf.wflib.WFLib;
import com.wf.wflib.pda.uplink.Uplink;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Satellites app: own net's birds as seen from the player; empty unless uplinked. */
public record SatStatusPacket(long netId, Uplink.Link link, List<Sat> sats) implements CustomPacketPayload {
    public static final Type<SatStatusPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_sat_status"));

    /** One command a bird answers; {@code pure} = read-only value. */
    public record Verb(String name, int arity, boolean pure, String describe) {
        static final StreamCodec<FriendlyByteBuf, Verb> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), Verb::name, ByteBufCodecs.VAR_INT, Verb::arity, ByteBufCodecs.BOOL,
                Verb::pure, ByteBufCodecs.stringUtf8(128), Verb::describe, Verb::new);
    }

    /**
     * @param nextPass ticks until the footprint covers the player; 0 = overhead, -1 = beyond the horizon
     * @param threat   ticks to an inbound hunter; -1 = none
     */
    public record Sat(long id, String callsign, String payload, boolean contact, float fuel, float power, int x, int z,
                      int nextPass, int threat, String reply, List<Verb> verbs) {
        static final StreamCodec<FriendlyByteBuf, Sat> CODEC = StreamCodec.of(Sat::write, Sat::read);

        private static void write(FriendlyByteBuf buf, Sat s) {
            buf.writeLong(s.id);
            buf.writeUtf(s.callsign, 64);
            buf.writeUtf(s.payload, 128);
            buf.writeBoolean(s.contact);
            buf.writeFloat(s.fuel);
            buf.writeFloat(s.power);
            buf.writeVarInt(s.x);
            buf.writeVarInt(s.z);
            buf.writeVarInt(s.nextPass);
            buf.writeVarInt(s.threat);
            buf.writeUtf(s.reply, 256);
            Verb.CODEC.apply(ByteBufCodecs.list()).encode(buf, s.verbs);
        }

        private static Sat read(FriendlyByteBuf buf) {
            return new Sat(buf.readLong(), buf.readUtf(64), buf.readUtf(128), buf.readBoolean(), buf.readFloat(),
                    buf.readFloat(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf(256), Verb.CODEC.apply(ByteBufCodecs.list()).decode(buf));
        }

        public boolean overhead() {
            return nextPass == 0;
        }
    }

    public static final StreamCodec<FriendlyByteBuf, SatStatusPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeLong(p.netId);
                buf.writeEnum(p.link);
                Sat.CODEC.apply(ByteBufCodecs.list()).encode(buf, p.sats);
            },
            buf -> new SatStatusPacket(buf.readLong(), buf.readEnum(Uplink.Link.class),
                    Sat.CODEC.apply(ByteBufCodecs.list()).decode(buf)));

    @Override
    public Type<SatStatusPacket> type() {
        return TYPE;
    }
}
