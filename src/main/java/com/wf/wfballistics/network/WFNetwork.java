package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * The mod's single play-phase network channel and the home for all server→client effect packets.
 *
 * <p>Everything the explosion framework shows the player — knockback, block debris, custom particle
 * effects — is computed on the server and shipped here, because particles and client-authoritative player
 * motion only exist on the client.
 *
 * <p><b>Efficiency:</b> positional effects go out via {@link #sendToTracking} ({@link PacketDistributor#TRACKING_CHUNK}),
 * which targets only the players who actually have that chunk loaded, instead of iterating every player in
 * the dimension and distance-checking them ({@link PacketDistributor#NEAR}). Payloads are kept small too —
 * see {@link ExplosionBlockFXPacket}'s delta-varint block encoding.
 */
public final class WFNetwork {

    private static final int PROTOCOL = 1;
    private static int packetId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(WFBallistics.MODID, "main"))
            .networkProtocolVersion(() -> Integer.toString(PROTOCOL))
            .clientAcceptedVersions(Integer.toString(PROTOCOL)::equals)
            .serverAcceptedVersions(Integer.toString(PROTOCOL)::equals)
            .simpleChannel();

    private WFNetwork() { }

    public static void register() {
        clientbound(ExplosionKnockbackPacket.class,
                ExplosionKnockbackPacket::encode, ExplosionKnockbackPacket::decode, ExplosionKnockbackPacket::handle);
        clientbound(ExplosionBlockFXPacket.class,
                ExplosionBlockFXPacket::encode, ExplosionBlockFXPacket::decode, ExplosionBlockFXPacket::handle);
        clientbound(AuxParticlePacket.class,
                AuxParticlePacket::encode, AuxParticlePacket::decode, AuxParticlePacket::handle);
        serverbound(SpawnMissilePacket.class,
                SpawnMissilePacket::encode, SpawnMissilePacket::decode, SpawnMissilePacket::handle);
    }

    private static <MSG> void clientbound(Class<MSG> type,
                                          java.util.function.BiConsumer<MSG, net.minecraft.network.FriendlyByteBuf> encoder,
                                          java.util.function.Function<net.minecraft.network.FriendlyByteBuf, MSG> decoder,
                                          java.util.function.BiConsumer<MSG, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context>> handler) {
        CHANNEL.registerMessage(packetId++, type, encoder, decoder, handler, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static <MSG> void serverbound(Class<MSG> type,
                                          java.util.function.BiConsumer<MSG, net.minecraft.network.FriendlyByteBuf> encoder,
                                          java.util.function.Function<net.minecraft.network.FriendlyByteBuf, MSG> decoder,
                                          java.util.function.BiConsumer<MSG, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context>> handler) {
        CHANNEL.registerMessage(packetId++, type, encoder, decoder, handler, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    /** Sends a packet from the client to the server over the mod channel. */
    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    /**
     * Sends to every player who has the chunk containing {@code (x, z)} loaded — the correct, cheap audience
     * for a localized effect. No-op off the server.
     */
    public static void sendToTracking(Level level, double x, double z, Object msg) {
        if (!(level instanceof ServerLevel)) return;
        LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord((int) Math.floor(x)),
                SectionPos.blockToSectionCoord((int) Math.floor(z)));
        CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), msg);
    }

    /** Sends to a specific {@link net.minecraft.server.level.ServerPlayer} via the player distributor. */
    public static void sendToPlayer(net.minecraft.server.level.ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /** Radius-based fallback when an explicit cutoff (rather than view distance) is wanted. */
    public static void sendToAllAround(Level level, double x, double y, double z, double radius, Object msg) {
        ResourceKey<Level> dim = level.dimension();
        CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(x, y, z, radius, dim)), msg);
    }
}
