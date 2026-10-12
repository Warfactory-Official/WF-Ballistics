package com.wf.wflib.stream;

import com.wf.wflib.mixin.AccessorPacketEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/**
 * Chunk packets compressed once, shared by every connection sending the same chunk within {@link #TTL} ticks
 * (passengers of one vehicle, players side by side). Compression = the per-connection send cost at speed (`ca_chunkstream`).
 * <ul>
 * <li>{@link Shared} written from the tail like a packet => passes encoder/compress untouched, {@link #HANDLER} (between
 * compress and prepender) turns it into compressed bytes => order with other packets kept.</li>
 * <li>Entry = first sender's packet: vanilla's send-time snapshot. Invalidated on {@code ChunkHolder} block / light change:
 * a change after the snapshot is broadcast before a later sender's copy => that copy must be rebuilt.</li>
 * <li>Bytes differ by connection type (registry-aware codecs, NeoForge bundle filter) or threshold => that connection
 * compresses alone.</li>
 * <li>NeoForge bundles every chunk packet with its aux light payload => unbundled here as the unbundler would: one frame each.</li>
 * </ul>
 */
public final class SharedChunkPackets {
    public static final String HANDLER = "wflib_shared_chunk";
    private static final int TTL = 40;
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(Deflater::new);
    private static final ThreadLocal<byte[]> CHUNK = ThreadLocal.withInitial(() -> new byte[8192]);
    /** Guarded by itself: light changes may arrive off the server thread. */
    private static final Map<LevelHeightAccessor, Long2ObjectMap<Entry>> CACHE = new WeakHashMap<>();
    private static long swept;
    public static final AtomicLong HITS = new AtomicLong(), MISSES = new AtomicLong(), COMPRESSIONS = new AtomicLong();
    /** Kill switch; harness A/B. */
    public static volatile boolean enabled = true;

    private SharedChunkPackets() {
    }

    private static final class Entry {
        final Packet<?> packet;
        final long tick;
        ConnectionType type;
        int threshold;
        byte[][] frames;

        Entry(Packet<?> packet, long tick) {
            this.packet = packet;
            this.tick = tick;
        }
    }

    private record Shared(Entry entry, ConnectionType type) {}

    /** Server thread. @return false => caller sends {@code packet} itself */
    public static boolean send(ServerGamePacketListenerImpl listener, LevelChunk chunk, Packet<?> packet) {
        Connection connection = listener.getConnection();
        Channel channel = connection.channel();
        if (!enabled || channel == null || channel.pipeline().get(HANDLER) == null || !connection.isConnected()) {
            return false;
        }
        long now = listener.player.server.getTickCount();
        long pos = chunk.getPos().toLong();
        Entry entry;
        synchronized (CACHE) {
            if (now != swept) {
                swept = now;
                for (Long2ObjectMap<Entry> map : CACHE.values()) {
                    map.values().removeIf(e -> now - e.tick > TTL);
                }
            }
            Long2ObjectMap<Entry> level = CACHE.computeIfAbsent(chunk.getLevel(), k -> new Long2ObjectOpenHashMap<>());
            entry = level.get(pos);
            if (entry == null) {
                entry = new Entry(packet, now);
                level.put(pos, entry);
                MISSES.incrementAndGet();
            } else {
                HITS.incrementAndGet();
            }
        }
        Shared shared = new Shared(entry, listener.getConnectionType());
        connection.runOnceConnected(c -> write(c.channel(), shared));
        return true;
    }

    /** No flush: the batch-finished packet and {@code Connection.tick} flush, as for vanilla's suspended flushing. */
    private static void write(Channel channel, Shared shared) {
        if (channel.eventLoop().inEventLoop()) {
            channel.write(shared).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        } else {
            channel.eventLoop().execute(() -> channel.write(shared).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));
        }
    }

    /** {@code ChunkHolder} changed this chunk. */
    public static void invalidate(LevelHeightAccessor level, long pos) {
        synchronized (CACHE) {
            Long2ObjectMap<Entry> map = CACHE.get(level);
            if (map != null) {
                map.remove(pos);
            }
        }
    }

    /** {@code Connection.setupCompression} tail. */
    public static void configure(ChannelPipeline pipeline) {
        boolean compress = pipeline.get("compress") instanceof CompressionEncoder;
        if (compress && pipeline.get(HANDLER) == null) {
            pipeline.addBefore("compress", HANDLER, new Handler());
        } else if (!compress && pipeline.get(HANDLER) != null) {
            pipeline.remove(HANDLER);
        }
    }

    private static final class Handler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof Shared shared)) {
                ctx.write(msg, promise);
                return;
            }
            ChannelPipeline pipeline = ctx.pipeline();
            int threshold = ((CompressionEncoder) pipeline.get("compress")).getThreshold();
            PacketEncoder<?> encoder = (PacketEncoder<?>) pipeline.get("encoder");
            Entry e = shared.entry;
            byte[][] out;
            synchronized (e) {
                if (e.frames == null) {
                    e.frames = frames(ctx, encoder, e.packet, threshold);
                    e.type = shared.type;
                    e.threshold = threshold;
                    COMPRESSIONS.incrementAndGet();
                }
                out = e.type == shared.type && e.threshold == threshold ? e.frames : null;
            }
            if (out == null) {
                out = frames(ctx, encoder, e.packet, threshold);
                COMPRESSIONS.incrementAndGet();
            }
            if (out.length == 0) {
                promise.setSuccess();
                return;
            }
            for (int i = 0; i < out.length - 1; i++) {
                ctx.write(Unpooled.wrappedBuffer(out[i]));
            }
            ctx.write(Unpooled.wrappedBuffer(out[out.length - 1]), promise);
        }
    }

    private static byte[][] frames(ChannelHandlerContext ctx, PacketEncoder<?> encoder, Packet<?> packet, int threshold) {
        ProtocolInfo<?> info = ((AccessorPacketEncoder) encoder).wfProtocolInfo();
        List<Packet<?>> parts = new ArrayList<>(3);
        info.bundlerInfo().unbundlePacket(packet, parts::add, ctx);
        byte[][] out = new byte[parts.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = compress(info, parts.get(i), threshold);
        }
        return out;
    }

    /** {@code PacketEncoder} then {@code CompressionEncoder}, byte for byte. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static byte[] compress(ProtocolInfo<?> info, Packet<?> packet, int threshold) {
        ByteBuf raw = Unpooled.buffer();
        ByteBuf out = Unpooled.buffer();
        try {
            ((ProtocolInfo) info).codec().encode(raw, packet);
            int n = raw.readableBytes();
            if (n < threshold) {
                VarInt.write(out, 0);
                out.writeBytes(raw);
            } else {
                byte[] in = new byte[n];
                raw.readBytes(in);
                VarInt.write(out, n);
                Deflater deflater = DEFLATER.get();
                byte[] chunk = CHUNK.get();
                deflater.setInput(in, 0, n);
                deflater.finish();
                while (!deflater.finished()) {
                    out.writeBytes(chunk, 0, deflater.deflate(chunk));
                }
                deflater.reset();
            }
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            raw.release();
            out.release();
        }
    }
}
