package com.wf.wflib.round;

import com.wf.wflib.WFLib;
import com.wf.wflib.kinetic.KineticPreset;
import io.netty.buffer.ByteBuf;
import com.wf.wflib.round.pen.BlockPen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rounds on the wire: one spawn batch and one end batch per player per tick. Client flies the same recurrence; an
 * end reaching the client before the round's first client tick = its whole life, replayed as one segment.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class RoundNetwork {

    private static final Map<ResourceKey<Level>, Map<ServerPlayer, Outbox>> PENDING = new HashMap<>();

    private RoundNetwork() {
    }

    private static final class Outbox {
        final List<Spawn> spawns = new ArrayList<>();
        final List<End> ends = new ArrayList<>();
        final List<Pierce> pierces = new ArrayList<>();
    }

    /**
     * @param shooter shooter entity id; -1 none
     * @param seq     {@link Rounds#launch} {@code shooterSeq}; {@link Rounds#NO_SEQ} none
     */
    public record Spawn(long key, ResourceLocation preset, double x, double y, double z,
                        double vx, double vy, double vz, int life, boolean resting, int shooter, int seq) {
    }

    public record End(long key, double x, double y, double z, RoundEnd reason) {

        public Vec3 position() {
            return new Vec3(this.x, this.y, this.z);
        }
    }

    /**
     * {@link RoundPierceEvent} for clients: {@code block} entered at {@code entry} through {@code face}; left at
     * {@code point} ({@code exited}) or stopped there. Exit => a re-sync spawn follows in the same packet.
     */
    public record Pierce(long key, long block, double x, double y, double z, Direction face, double px, double py,
                         double pz, boolean exited) {

        public BlockPos blockPos() {
            return BlockPos.of(this.block);
        }

        public Vec3 entry() {
            return new Vec3(this.x, this.y, this.z);
        }

        public Vec3 point() {
            return new Vec3(this.px, this.py, this.pz);
        }
    }

    static void pierced(ServerLevel level, RoundBatch b, int i, BlockHitResult entry, BlockPen.Pass pass) {
        Vec3 at = entry.getLocation();
        Pierce p = new Pierce(b.key[i], entry.getBlockPos().asLong(), at.x, at.y, at.z, entry.getDirection(),
                pass.point().x, pass.point().y, pass.point().z, pass.exited());
        for (ServerPlayer player : recipients(level, b, i)) {
            outbox(level, player).pierces.add(p);
        }
    }

    /** Spawn, or a state change the client cannot predict (landing, burrowing, pierce): replaces its copy. */
    static void sync(ServerLevel level, RoundBatch b, int i) {
        KineticPreset preset = b.preset[i];
        Spawn spawn = new Spawn(b.key[i], preset.id(), b.x[i], b.y[i], b.z[i], b.vx[i], b.vy[i], b.vz[i], b.life[i],
                b.rest[i] >= 0, b.shooter[i], b.seq[i]);
        List<ServerPlayer> to = recipients(level, b, i);
        UUID[] viewers = new UUID[to.size()];
        for (int k = 0; k < viewers.length; k++) {
            ServerPlayer player = to.get(k);
            outbox(level, player).spawns.add(spawn);
            viewers[k] = player.getUUID();
        }
        b.viewers[i] = viewers;
    }

    static void ended(ServerLevel level, RoundBatch b, int i, Vec3 at, RoundEnd reason) {
        End end = new End(b.key[i], at.x, at.y, at.z, reason);
        for (ServerPlayer player : recipients(level, b, i)) {
            outbox(level, player).ends.add(end);
        }
    }

    /** Audience of round {@code i} now, plus its viewers still in the level. */
    private static List<ServerPlayer> recipients(ServerLevel level, RoundBatch b, int i) {
        List<ServerPlayer> out = Rounds.audience(level, b.preset[i], b.x[i], b.z[i]);
        for (UUID id : b.viewers[i]) {
            if (level.getPlayerByUUID(id) instanceof ServerPlayer player && !out.contains(player)) {
                out.add(player);
            }
        }
        return out;
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
            // Unnegotiated channel => send throws; FakePlayer connection has no netty channel => hasChannel NPEs.
            if (!(e.getKey() instanceof FakePlayer) && e.getKey().connection.hasChannel(RoundPacket.TYPE)) {
                Outbox box = e.getValue();
                Rounds.send(e.getKey(), new RoundPacket(box.spawns, box.ends, box.pierces));
            }
        }
    }

    /** Spawns grouped by preset on the wire. */
    public record RoundPacket(List<Spawn> spawns, List<End> ends, List<Pierce> pierces)
            implements CustomPacketPayload {

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
                    // +1: -1 (none) as a 1-byte varint.
                    buf.writeVarInt(s.shooter() + 1);
                    buf.writeVarInt(s.seq() + 1);
                }
            }
            buf.writeVarInt(this.ends.size());
            for (End e : this.ends) {
                buf.writeLong(e.key());
                buf.writeDouble(e.x());
                buf.writeDouble(e.y());
                buf.writeDouble(e.z());
                buf.writeByte(e.reason().ordinal());
            }
            buf.writeVarInt(this.pierces.size());
            for (Pierce p : this.pierces) {
                buf.writeLong(p.key());
                buf.writeLong(p.block());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeByte(p.face().get3DDataValue() | (p.exited() ? 8 : 0));
                buf.writeDouble(p.px());
                buf.writeDouble(p.py());
                buf.writeDouble(p.pz());
            }
        }

        private static RoundPacket read(FriendlyByteBuf buf) {
            List<Spawn> spawns = new ArrayList<>();
            int groups = buf.readVarInt();
            for (int g = 0; g < groups; g++) {
                ResourceLocation preset = buf.readResourceLocation();
                int n = buf.readVarInt();
                for (int k = 0; k < n; k++) {
                    spawns.add(new Spawn(buf.readLong(), preset, buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readBoolean(),
                            buf.readVarInt() - 1, buf.readVarInt() - 1));
                }
            }
            int n = buf.readVarInt();
            List<End> ends = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                ends.add(new End(buf.readLong(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        RoundEnd.VALUES[buf.readByte()]));
            }
            n = buf.readVarInt();
            List<Pierce> pierces = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                long key = buf.readLong();
                long block = buf.readLong();
                double x = buf.readDouble();
                double y = buf.readDouble();
                double z = buf.readDouble();
                int flags = buf.readByte();
                pierces.add(new Pierce(key, block, x, y, z, Direction.from3DDataValue(flags & 7), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), (flags & 8) != 0));
            }
            return new RoundPacket(spawns, ends, pierces);
        }

        @Override
        public Type<RoundPacket> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("rounds-5");
        registrar.playToClient(RoundPacket.TYPE, RoundPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.round.client.ClientRounds.accept(pkt)));
    }
}
