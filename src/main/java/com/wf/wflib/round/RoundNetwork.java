package com.wf.wflib.round;

import com.wf.wflib.WFLib;
import com.wf.wflib.kinetic.KineticPreset;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Rounds on the wire: one spawn batch and one end batch per player per tick. Client flies the same recurrence. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class RoundNetwork {

    private static final Map<ResourceKey<Level>, Map<ServerPlayer, Outbox>> PENDING = new HashMap<>();

    private RoundNetwork() {
    }

    private static final class Outbox {
        final List<Spawn> spawns = new ArrayList<>();
        final LongArrayList ends = new LongArrayList();
    }

    /** @param preset null only on the wire when the client lacks it */
    public record Spawn(long key, ResourceLocation preset, double x, double y, double z,
                        double vx, double vy, double vz, int life, boolean resting) {
    }

    /** Spawn, or a state change the client cannot predict (landing, burrowing): replaces its copy. */
    static void sync(ServerLevel level, RoundBatch b, int i) {
        KineticPreset preset = b.preset[i];
        Spawn spawn = new Spawn(b.key[i], preset.id(), b.x[i], b.y[i], b.z[i], b.vx[i], b.vy[i], b.vz[i], b.life[i],
                b.rest[i] >= 0);
        for (ServerPlayer player : Rounds.audience(level, preset, b.x[i], b.z[i])) {
            outbox(level, player).spawns.add(spawn);
        }
    }

    static void ended(ServerLevel level, RoundBatch b, int i) {
        for (ServerPlayer player : Rounds.audience(level, b.preset[i], b.x[i], b.z[i])) {
            outbox(level, player).ends.add(b.key[i]);
        }
    }

    private static Outbox outbox(ServerLevel level, ServerPlayer player) {
        return PENDING.computeIfAbsent(level.dimension(), k -> new IdentityHashMap<>())
                .computeIfAbsent(player, p -> new Outbox());
    }

    static void flush(ServerLevel level) {
        Map<ServerPlayer, Outbox> byPlayer = PENDING.remove(level.dimension());
        if (byPlayer == null) {
            return;
        }
        for (Map.Entry<ServerPlayer, Outbox> e : byPlayer.entrySet()) {
            Outbox box = e.getValue();
            Rounds.send(e.getKey(), new RoundPacket(box.spawns, box.ends.toLongArray()));
        }
    }

    /** Spawns grouped by preset on the wire; ends by key. */
    public record RoundPacket(List<Spawn> spawns, long[] ends) implements CustomPacketPayload {

        public static final Type<RoundPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "rounds"));

        public static final StreamCodec<ByteBuf, RoundPacket> STREAM_CODEC = StreamCodec.of(
                (buf, pkt) -> pkt.write(new FriendlyByteBuf(buf)), buf -> read(new FriendlyByteBuf(buf)));

        private void write(FriendlyByteBuf buf) {
            Map<ResourceLocation, List<Spawn>> groups = new HashMap<>();
            for (Spawn s : this.spawns) {
                groups.computeIfAbsent(s.preset(), k -> new ArrayList<>()).add(s);
            }
            buf.writeVarInt(groups.size());
            for (Map.Entry<ResourceLocation, List<Spawn>> g : groups.entrySet()) {
                buf.writeResourceLocation(g.getKey());
                buf.writeVarInt(g.getValue().size());
                for (Spawn s : g.getValue()) {
                    buf.writeLong(s.key());
                    buf.writeDouble(s.x());
                    buf.writeDouble(s.y());
                    buf.writeDouble(s.z());
                    buf.writeDouble(s.vx());
                    buf.writeDouble(s.vy());
                    buf.writeDouble(s.vz());
                    buf.writeVarInt(s.life());
                    buf.writeBoolean(s.resting());
                }
            }
            buf.writeLongArray(this.ends);
        }

        private static RoundPacket read(FriendlyByteBuf buf) {
            List<Spawn> spawns = new ArrayList<>();
            int groups = buf.readVarInt();
            for (int g = 0; g < groups; g++) {
                ResourceLocation preset = buf.readResourceLocation();
                int n = buf.readVarInt();
                for (int k = 0; k < n; k++) {
                    spawns.add(new Spawn(buf.readLong(), preset, buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readBoolean()));
                }
            }
            return new RoundPacket(spawns, buf.readLongArray());
        }

        @Override
        public Type<RoundPacket> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("rounds-1");
        registrar.playToClient(RoundPacket.TYPE, RoundPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.round.client.ClientRounds.accept(pkt)));
    }
}
