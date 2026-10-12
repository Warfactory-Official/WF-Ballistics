package com.wf.wflib.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wf.wflib.stream.ChunkStreams;
import com.wf.wflib.stream.SharedChunkPackets;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla leaving a chunk a streamer still owns must not forget it on the client; chunk packets compressed once (SharedChunkPackets). */
@Mixin(PlayerChunkSender.class)
public abstract class MixinPlayerChunkSender {

    @Shadow
    @Final
    private LongSet pendingChunks;

    @Inject(method = "dropChunk", at = @At("HEAD"), cancellable = true)
    private void wfKeepStreamedChunk(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        long key = pos.toLong();
        if (ChunkStreams.owned(player, key)) {
            ChunkStreams.vanillaDropped(player, key, this.pendingChunks.remove(key));
            ci.cancel();
        }
    }

    @WrapOperation(method = "sendChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private static void wfSharedChunkPacket(ServerGamePacketListenerImpl listener, Packet<?> packet, Operation<Void> original,
                                            ServerGamePacketListenerImpl l, ServerLevel level, LevelChunk chunk) {
        if (!SharedChunkPackets.send(listener, chunk, packet)) {
            original.call(listener, packet);
        }
    }
}
