package com.wf.wfballistics.mixin;

import com.wf.wfballistics.drone.cam.CameraChunkStream;
import com.wf.wfballistics.drone.cam.FeedTracked;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Lets a drone camera see what is standing in the terrain streamed for it. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class MixinChunkMapTrackedEntity implements FeedTracked {

    @Shadow
    @Final
    Entity entity;

    @Shadow
    @Final
    ServerEntity serverEntity;

    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Shadow
    public abstract void updatePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void wfCamRevealInStreamedChunk(ServerPlayer player, CallbackInfo ci) {
        if (player == this.entity || !CameraChunkStream.reveals(this.entity, player)) {
            return;
        }
        if (!this.entity.broadcastToPlayer(player)) {
            return;
        }
        if (this.seenBy.add(player.connection)) {
            this.serverEntity.addPairing(player);
        }
        ci.cancel();
    }

    @Override
    public void wfCamUpdatePlayer(ServerPlayer player) {
        updatePlayer(player);
    }
}
