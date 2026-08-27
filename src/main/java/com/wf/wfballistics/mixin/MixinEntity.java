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

    @Shadow
    public Optional<BlockPos> mainSupportingBlockPos;
    @Shadow
    private boolean onGroundNoBlocks;

    @Unique
    private long wfballistics$collideStart;
    @Unique
    private long wfballistics$scanStart;

    /**
     * Names the block a glyphid is standing on without sweeping for it.
     *
     * <p>{@code checkSupportingBlock} runs once per move and is the largest single named cost in the swarm
     * tick: 1.949 ms of 15.392 at 2000 marching bodies, effectively all of it inside
     * {@code findSupportingBlock}, which builds a {@code BlockCollisions} iterator over a box a millionth of
     * a block tall and walks every cell the hitbox overlaps, reading a block state and intersecting a shape
     * for each. It then keeps whichever candidate is nearest {@code entity.position()}.
     *
     * <p>The sweep is unnecessary whenever the column under the entity's own centre holds a full collision
     * cube, because that block is then guaranteed to be the answer:
     * <ul>
     *   <li>it is a candidate — a full cube spans the whole cell, so it meets the flattened box wherever
     *       inside the cell the feet are;</li>
     *   <li>it is the nearest one — every other candidate lies in a different column, and the entity's centre
     *       is inside this one, so no other column's centre can be closer in x or z. Only one layer of cells
     *       can contribute at all, so the y term is shared and cancels.</li>
     * </ul>
     *
     * <p>Exactly vanilla's answer, ties included. The one case where a neighbour ties on distance is an entity
     * standing on an exact block boundary, and vanilla breaks that tie toward the greater {@link BlockPos} —
     * which is this one, since the tie is always with the column below in x or z. Anything else (a slab, a
     * ledge the bug is half off, an empty column) fails the full-cube test and falls through to the sweep.
     */
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
            // Everything that is not a glyphid keeps vanilla's answer, with any bridge deck added to it.
            // An anchored glyphid also reports canBeCollidedWith, which is the proper way to be standable —
            // but a pig will not rest on a hovering boat in this dev runtime either, so that path cannot be
            // shown to work here and the deck does not rely on it. Free when no bridge is standing: the
            // deck lookup is a static int read that returns an empty list.
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
            // Glyphids do not collide with each other, with one exception: the ones holding still to make a
            // floor out of themselves. Answered from the bridge's own record of where its anchors sat rather
            // than by reinstating the entity query this branch exists to avoid.
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
