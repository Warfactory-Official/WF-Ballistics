package com.wf.wflib.mixin;

import com.wf.wflib.drone.cam.CameraNet;
import com.wf.wflib.stream.ChunkStreams;
import com.wf.wflib.stream.DetachedBodies;
import com.wf.wflib.stream.StreamTracked;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Entities standing in streamed terrain, and an entity whose feed a player watches, are tracked to them at any range.
 * Remote operator: own host always; around the parked body only what it rides or carries.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class MixinChunkMapTrackedEntity implements StreamTracked {

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
    private void wfRevealInStreamedChunk(ServerPlayer player, CallbackInfo ci) {
        if (player == this.entity) {
            return;
        }
        ChunkPos pos = this.entity.chunkPosition();
        if (CameraNet.watches(player, this.entity) && this.entity.broadcastToPlayer(player)
                && (player.getChunkTrackingView().contains(pos)
                || ChunkStreams.streamed(player, ChunkPos.asLong(pos.x, pos.z)))) {
            if (this.seenBy.add(player.connection)) {
                this.serverEntity.addPairing(player);
            }
            ci.cancel();
            return;
        }
        if (ChunkStreams.idle()) {
            return;
        }
        if (!ChunkStreams.streamed(player, ChunkPos.asLong(pos.x, pos.z)) || !this.entity.broadcastToPlayer(player)) {
            return;
        }
        if (this.seenBy.add(player.connection)) {
            this.serverEntity.addPairing(player);
        }
        ci.cancel();
    }

    @ModifyVariable(method = "updatePlayer", at = @At("STORE"), ordinal = 0)
    private boolean wfDetachedVisibility(boolean visible, ServerPlayer player) {
        if (DetachedBodies.ridesTogether(this.entity, player)) {
            return true;
        }
        if (DetachedBodies.suppressesBodyEntities(player.getUUID())) {
            return DetachedBodies.mustStayPaired(this.entity, player) && visible;
        }
        return visible;
    }

    @Override
    public void wfUpdatePlayer(ServerPlayer player) {
        updatePlayer(player);
    }
}
