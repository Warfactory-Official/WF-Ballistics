package com.wf.wfballistics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Breaks {@code Entity#move} into its parts for {@link SwarmProfiler}.
 *
 * <p>Movement is the swarm's largest single cost and the one that cannot be moved off the server thread, so
 * "movement is 45%" is where the useful question starts rather than ends. Vanilla's move is three different
 * things wearing one name: a collision sweep that runs several times over when a mob steps up, an entity
 * overlap query whose cost grows with how packed the swarm is, and a walk of every block the hitbox touches.
 * They point at completely different fixes, so they are measured apart.
 *
 * <p>Scoped to glyphids and gated on {@link SwarmProfiler#enabled()} before the {@code instanceof}, so with
 * profiling off this costs every entity in the game one static boolean read per call site.
 */
@Mixin(Entity.class)
public abstract class MixinEntity {

    @Unique
    private long wfballistics$collideStart;
    @Unique
    private long wfballistics$scanStart;

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
            return original.call(level, entity, box);
        }
        if (SwarmBench.skipEntityCollisions) {
            return List.of();
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
     * The tail of {@code move}: a lazy stream over every block state the hitbox overlaps, asking whether the
     * entity is standing in fire. Timed from the stream's construction to the terminal {@code noneMatch},
     * because the construction is lazy and does no work on its own.
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
