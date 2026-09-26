package com.wf.wflib.mixin;

import com.wf.wflib.stream.client.OffViewChunks;
import com.wf.wflib.stream.client.SodiumChunkProbe;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * Client holds every chunk the server sent and has not forgotten. Vanilla's ring only answers inside
 * {@code centre ± radius}: out-of-window arrivals and chunks the centre moves away from land here.
 * Invariant: a position lives in the ring XOR here. Reads from any thread (the section graph queries
 * from {@code Util.backgroundExecutor}); writes on the client thread only.
 */
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache implements OffViewChunks {

    @Unique
    private final ConcurrentHashMap<Long, LevelChunk> wf$offView = new ConcurrentHashMap<>();

    @Shadow
    @Final
    ClientLevel level;

    @Shadow
    @Final
    private LevelChunk emptyChunk;

    @Shadow
    volatile ClientChunkCache.Storage storage;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN"), cancellable = true)
    private void wfServeOffView(int x, int z, ChunkStatus status, boolean requireChunk,
                                CallbackInfoReturnable<LevelChunk> cir) {
        if (this.wf$offView.isEmpty()) {
            return;
        }
        LevelChunk found = cir.getReturnValue();
        if (found != null && found != this.emptyChunk) {
            return;
        }
        LevelChunk held = this.wf$offView.get(ChunkPos.asLong(x, z));
        if (held != null) {
            cir.setReturnValue(held);
        }
    }

    /** Arriving in window: the ring takes over. Unload(old) before vanilla's Load(new). */
    @Inject(method = "replaceWithPacketData(IILnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("HEAD"))
    private void wfHandToRing(int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
                              Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                              CallbackInfoReturnable<LevelChunk> cir) {
        if (this.wf$offView.isEmpty() || !this.storage.inRange(x, z)) {
            return;
        }
        LevelChunk stale = this.wf$offView.remove(ChunkPos.asLong(x, z));
        if (stale != null) {
            NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(stale));
            stale.clearAllBlockEntities();
        }
    }

    @Inject(method = "replaceWithPacketData(IILnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN"), cancellable = true)
    private void wfKeepOffView(int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
                               Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                               CallbackInfoReturnable<LevelChunk> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        ChunkPos pos = new ChunkPos(x, z);
        LevelChunk chunk = this.wf$offView.get(pos.toLong());
        if (chunk == null) {
            chunk = new LevelChunk(this.level, pos);
            chunk.replaceWithPacketData(buffer, tag, consumer);
            this.wf$offView.put(pos.toLong(), chunk);
        } else {
            chunk.replaceWithPacketData(buffer, tag, consumer);
        }
        this.level.onChunkLoaded(pos);
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
        SodiumChunkProbe.announceLoaded(this.level, x, z);
        cir.setReturnValue(chunk);
    }

    /** Out-of-window arrivals are expected now. */
    @Redirect(method = "replaceWithPacketData(IILnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void wfQuietOffView(Logger logger, String message, Object x, Object z) {
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void wfForgetOffView(ChunkPos pos, CallbackInfo ci) {
        if (this.wf$offView.isEmpty()) {
            return;
        }
        LevelChunk held = this.wf$offView.remove(pos.toLong());
        if (held != null) {
            NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(held));
            this.level.unload(held);
        }
    }

    @Inject(method = "updateViewCenter", at = @At("HEAD"))
    private void wfKeepLeavingCentre(int x, int z, CallbackInfo ci) {
        ClientChunkCache.Storage ring = this.storage;
        if (ring.viewCenterX != x || ring.viewCenterZ != z) {
            wf$evict(ring, x, z, ring.chunkRadius);
        }
    }

    /** Vanilla's rebuild discards out-of-range chunks without unloading them. */
    @Inject(method = "updateViewRadius", at = @At("HEAD"))
    private void wfKeepOnShrink(int viewDistance, CallbackInfo ci) {
        ClientChunkCache.Storage ring = this.storage;
        wf$evict(ring, ring.viewCenterX, ring.viewCenterZ, Math.max(2, viewDistance) + 3);
    }

    /** Off-view copy published before the slot clears: a concurrent reader always finds one. */
    @Unique
    private void wf$evict(ClientChunkCache.Storage ring, int centreX, int centreZ, int radius) {
        AtomicReferenceArray<LevelChunk> chunks = ring.chunks;
        for (int i = 0; i < chunks.length(); i++) {
            LevelChunk chunk = chunks.get(i);
            if (chunk == null) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            if (Math.abs(pos.x - centreX) <= radius && Math.abs(pos.z - centreZ) <= radius) {
                continue;
            }
            this.wf$offView.put(pos.toLong(), chunk);
            chunks.set(i, null);
            ring.chunkCount--;
        }
    }

    @Override
    public LongOpenHashSet wfOffViewPositions() {
        LongOpenHashSet out = new LongOpenHashSet();
        for (Long pos : this.wf$offView.keySet()) {
            out.add(pos.longValue());
        }
        return out;
    }

    @Override
    public LongOpenHashSet wfHeldPositions() {
        LongOpenHashSet out = new LongOpenHashSet();
        ClientChunkCache.Storage ring = this.storage;
        for (int i = 0; i < ring.chunks.length(); i++) {
            LevelChunk chunk = ring.chunks.get(i);
            if (chunk != null && ring.inRange(chunk.getPos().x, chunk.getPos().z)) {
                out.add(chunk.getPos().toLong());
            }
        }
        out.addAll(wfOffViewPositions());
        return out;
    }
}
