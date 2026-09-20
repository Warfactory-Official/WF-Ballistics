package com.wf.wflib.mixin;

import com.wf.wflib.client.cam.FeedChunkStore;
import com.wf.wflib.client.cam.FeedChunks;
import com.wf.wflib.client.cam.SodiumChunkProbe;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/** Lets a drone feed's terrain live somewhere. */
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache implements FeedChunkStore {

    @Unique
    private final Long2ObjectMap<LevelChunk> wfCam$pinned = new Long2ObjectOpenHashMap<>();

    @Shadow
    @Final
    ClientLevel level;

    @Shadow
    @Final
    private LevelChunk emptyChunk;

    /** Serve a streamed chunk when the window has none. */
    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN"), cancellable = true)
    private void wfCamGetPinnedChunk(int x, int z, ChunkStatus status, boolean requireChunk,
                                     CallbackInfoReturnable<LevelChunk> cir) {
        if (this.wfCam$pinned.isEmpty()) {
            return;
        }
        LevelChunk found = cir.getReturnValue();
        if (found != null && found != this.emptyChunk) {
            return;
        }
        LevelChunk pinned = this.wfCam$pinned.get(ChunkPos.asLong(x, z));
        if (pinned != null) {
            cir.setReturnValue(pinned);
        }
    }

    /** Keep a chunk vanilla just refused, if it belongs to a feed this client is watching. */
    @Inject(
            method = "replaceWithPacketData(IILnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN"), cancellable = true)
    private void wfCamPinChunk(int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
                               Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                               CallbackInfoReturnable<LevelChunk> cir) {
        if (cir.getReturnValue() != null || !FeedChunks.wanted(x, z, this.wfCam$pinned.size())) {
            return;
        }
        ChunkPos pos = new ChunkPos(x, z);
        LevelChunk chunk = new LevelChunk(this.level, pos);
        chunk.replaceWithPacketData(buffer, tag, consumer);
        this.wfCam$pinned.put(ChunkPos.asLong(x, z), chunk);
        this.level.onChunkLoaded(pos);
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
        SodiumChunkProbe.announceLoaded(this.level, x, z);
        cir.setReturnValue(chunk);
    }

    /** Keep the log readable. */
    @Redirect(method = "replaceWithPacketData(IILnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void wfCamQuietAboutStreamedChunk(Logger logger, String message, Object x, Object z) {
        if (!FeedChunks.wanted((Integer) x, (Integer) z, this.wfCam$pinned.size())) {
            logger.warn(message, x, z);
        }
    }

    /** The server taking a streamed chunk back. */
    @Inject(method = "drop", at = @At("HEAD"))
    private void wfCamDropPinnedChunk(ChunkPos pos, CallbackInfo ci) {
        if (!this.wfCam$pinned.isEmpty()) {
            wfCam$release(this.wfCam$pinned.remove(pos.toLong()));
        }
    }

    @Unique
    private void wfCam$release(LevelChunk chunk) {
        if (chunk == null) {
            return;
        }
        NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
        this.level.unload(chunk);
        ChunkPos pos = chunk.getPos();
        SodiumChunkProbe.announceUnloaded(this.level, pos.x, pos.z);
    }

    @Override
    public void wfCamSweepPins() {
        if (this.wfCam$pinned.isEmpty()) {
            return;
        }
        for (ObjectIterator<Long2ObjectMap.Entry<LevelChunk>> it =
             this.wfCam$pinned.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<LevelChunk> entry = it.next();
            long key = entry.getLongKey();
            if (!FeedChunks.wanted(ChunkPos.getX(key), ChunkPos.getZ(key), 0)) {
                wfCam$release(entry.getValue());
                it.remove();
            }
        }
    }

    @Override
    public void wfCamClearPins() {
        for (LevelChunk chunk : this.wfCam$pinned.values()) {
            wfCam$release(chunk);
        }
        this.wfCam$pinned.clear();
    }

    @Override
    public int wfCamPinnedCount() {
        return this.wfCam$pinned.size();
    }
}
