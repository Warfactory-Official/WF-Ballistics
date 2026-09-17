package com.wf.wfballistics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.nav.GlyphidBridges;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Breaks {@code Entity#move} into its parts for {@link SwarmProfiler}. */
@Mixin(Entity.class)
public abstract class MixinEntity {

    @Shadow
    public Optional<BlockPos> mainSupportingBlockPos;
    @Shadow
    private boolean onGroundNoBlocks;

    @Unique
    private long wfballistics$collideStart;
    @Unique
    private long wfballistics$scanStart;

    /** Names the block a glyphid is standing on without sweeping for it. */
    @Inject(method = "checkSupportingBlock", at = @At("HEAD"), cancellable = true)
    private void wfballistics$supportUnderfoot(boolean onGround, @Nullable Vec3 movement, CallbackInfo ci) {
        if (!onGround || !((Object) this instanceof EntityGlyphid)) {
            return;
        }
        Entity self = (Entity) (Object) this;
        AABB box = self.getBoundingBox();
        // The same 1.0E-6 vanilla drops the box by, so this picks the cell vanilla's sweep would start in.
        BlockPos pos = BlockPos.containing(self.getX(), box.minY - 1.0E-6, self.getZ());
        BlockState state = self.level().getBlockState(pos);
        if (!state.isCollisionShapeFullBlock(self.level(), pos)) {
            return;
        }
        mainSupportingBlockPos = Optional.of(pos);
        onGroundNoBlocks = false;
        ci.cancel();
    }

    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"))
    private void wfballistics$collideBegin(Vec3 vec, CallbackInfoReturnable<Vec3> cir) {
        wfballistics$collideStart = wfballistics$profiled() ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"))
    private void wfballistics$collideEnd(Vec3 vec, CallbackInfoReturnable<Vec3> cir) {
        SwarmProfiler.end(SwarmProfiler.Phase.COLLIDE, wfballistics$collideStart);
        wfballistics$collideStart = 0L;
    }

    /**
     * The entity-overlap half of the sweep, split from the block half because only this one scales with swarm
     * density: it queries the entity sections over the swept box, and in a pack that box is full of glyphids.
     */
    @WrapOperation(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntityCollisions"
                            + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> wfballistics$entityCollisions(Level level, Entity entity, AABB box,
                                                          Operation<List<VoxelShape>> original) {
        if (!((Object) this instanceof EntityGlyphid)) {
            List<VoxelShape> shapes = original.call(level, entity, box);
            List<VoxelShape> deck = GlyphidBridges.deckShapes(level, box);
            if (deck.isEmpty()) {
                return shapes;
            }
            if (shapes.isEmpty()) {
                return deck;
            }
            List<VoxelShape> both = new ArrayList<>(shapes.size() + deck.size());
            both.addAll(shapes);
            both.addAll(deck);
            return both;
        }
        if (SwarmBench.skipEntityCollisions) {
            return GlyphidBridges.deckShapes(level, box);
        }
        if (!SwarmProfiler.enabled()) {
            return original.call(level, entity, box);
        }
        long t = SwarmProfiler.begin();
        List<VoxelShape> shapes = original.call(level, entity, box);
        SwarmProfiler.end(SwarmProfiler.Phase.ENTCOL, t);
        return shapes;
    }

    /**
     * The tail of {@code move}: a lazy stream over every block state the hitbox overlaps, asking whether the entity
     * is standing in fire.
     */
    @Inject(method = "move", at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
            target = "Lnet/minecraft/world/level/Level;getBlockStatesIfLoaded"
                    + "(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"))
    private void wfballistics$scanBegin(CallbackInfo ci) {
        wfballistics$scanStart = wfballistics$profiled() ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "move", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Ljava/util/stream/Stream;noneMatch(Ljava/util/function/Predicate;)Z"))
    private void wfballistics$scanEnd(CallbackInfo ci) {
        SwarmProfiler.end(SwarmProfiler.Phase.SCAN, wfballistics$scanStart);
        wfballistics$scanStart = 0L;
    }

    @Unique
    private boolean wfballistics$profiled() {
        return SwarmProfiler.enabled() && (Object) this instanceof EntityGlyphid;
    }
}
