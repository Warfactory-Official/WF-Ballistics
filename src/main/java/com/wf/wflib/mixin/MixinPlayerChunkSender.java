package com.wf.wflib.mixin;

import com.wf.wflib.stream.ChunkStreams;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla leaving a chunk a streamer still owns must not forget it on the client. */
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
}
