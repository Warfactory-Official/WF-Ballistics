package com.wf.wflib.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wf.wflib.stream.ChunkStreams;
import com.wf.wflib.stream.DetachedBodies;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Block, block-entity and light updates reach streamed observers too; a remote operator's body is parked. */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap {

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    private int serverViewDistance;

    @ModifyReturnValue(method = "getPlayers", at = @At("RETURN"))
    private List<ServerPlayer> wfAddStreamedWatchers(List<ServerPlayer> players, ChunkPos pos, boolean boundaryOnly) {
        return boundaryOnly || ChunkStreams.idle() ? players : ChunkStreams.extraWatchers(this.level, pos.toLong(), players);
    }

    /** Vanilla view reaching a chunk a streamer already delivered: client holds it, no resend. */
    @WrapOperation(method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/PlayerChunkSender;markChunkPendingToSend(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private static void wfSkipStreamedResend(PlayerChunkSender sender, LevelChunk chunk, Operation<Void> original, ServerPlayer player) {
        if (!ChunkStreams.streamed(player, chunk.getPos().toLong())) {
            original.call(sender, chunk);
        }
    }

    @Inject(method = "getPlayerViewDistance", at = @At("HEAD"), cancellable = true)
    private void wfParkedBodyViewDistance(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        int radius = DetachedBodies.bodyViewDistance(player.getUUID(), this.serverViewDistance);
        if (radius > 0) {
            cir.setReturnValue(radius);
        }
    }

    /** Player ticket off; {@code DetachedBodyStream} holds a small one instead. */
    @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
    private void wfParkedBodyTickets(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (DetachedBodies.parksBodyTickets(player.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
