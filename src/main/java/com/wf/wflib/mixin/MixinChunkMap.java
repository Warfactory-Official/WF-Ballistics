package com.wf.wflib.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.wf.wflib.stream.ChunkStreams;
import com.wf.wflib.stream.DetachedBodies;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
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
