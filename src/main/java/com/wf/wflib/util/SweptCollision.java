package com.wf.wflib.util;

import com.wf.wflib.entity.OBBEntity;
import com.wf.wflib.sim.MissileSimConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.function.Predicate;

/**
 * Continuous ("swept") collision for fast projectiles, used so a missile moving many blocks in a single 20&nbsp;TPS
 * tick can't tunnel through a wall.
 */
public final class SweptCollision {

    private SweptCollision() {
    }

    /**
     * @param self the projectile (excluded from its own hit test)
     * @param level the level to trace in
     * @param startPos pre-move position of the projectile origin
     * @param delta this tick's movement vector
     * @param noseForward distance from the origin to the body's front face along the heading (0 for a
     *      point-like projectile); folded into the ray length so the nose, not the base,
     *      triggers the hit
     * @param filter entity hit predicate
     * @param maxSubstepDist max length of one sub-segment (blocks)
     * @param maxSubsteps hard clamp on sub-segments per call
     * @return the first block/entity hit along the corridor, or a {@code MISS} at the corridor end
     */
    public static HitResult sweep(Entity self, Level level,
                                  Vec3 startPos, Vec3 delta, double noseForward,
                                  Predicate<Entity> filter,
                                  double maxSubstepDist, int maxSubsteps) {
        double moveDist = delta.length();
        if (moveDist < 1.0E-6) {
            return miss(startPos);
        }
        Vec3 heading = delta.scale(1.0 / moveDist);

        boolean obbSweep = MissileSimConfig.COLLISION_FIDELITY == MissileSimConfig.CollisionFidelity.OBB_SWEEP
                && self instanceof OBBEntity obbEntity && !obbEntity.enableAABB();

        BlockHitResult blockHit = obbSweep
                ? sweepBlocksObb(self, level, startPos, delta, moveDist, noseForward, maxSubstepDist, maxSubsteps)
                : sweepBlocksRay(self, level, startPos, heading, moveDist, noseForward, maxSubstepDist, maxSubsteps);

        Vec3 corridorEnd = (blockHit != null)
                ? blockHit.getLocation()
                : startPos.add(heading.scale(moveDist + Math.max(0.0, noseForward)));
        AABB search = new AABB(startPos.x, startPos.y, startPos.z, corridorEnd.x, corridorEnd.y, corridorEnd.z)
                .inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, self, startPos, corridorEnd, search, filter);
        if (entityHit != null) {
            return entityHit;
        }
        return blockHit != null ? blockHit : miss(corridorEnd);
    }

    /**
     * Nose-extended center-ray sweep: one DDA {@link Level#clip} per contiguous sub-segment, first block wins.
     */
    private static BlockHitResult sweepBlocksRay(Entity self, Level level, Vec3 startPos, Vec3 heading,
                                                 double moveDist, double noseForward,
                                                 double maxSubstepDist, int maxSubsteps) {
        double sweepLen = moveDist + Math.max(0.0, noseForward);
        int n = Mth.clamp((int) Math.ceil(sweepLen / maxSubstepDist), 1, maxSubsteps);

        Vec3 prev = startPos;
        for (int i = 1; i <= n; i++) {
            Vec3 next = (i == n) ? startPos.add(heading.scale(sweepLen))
                    : startPos.add(heading.scale(sweepLen * i / (double) n));
            BlockHitResult hit = level.clip(new ClipContext(prev, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
            if (hit.getType() != HitResult.Type.MISS) {
                return hit;
            }
            prev = next;
        }
        return null;
    }

    private static BlockHitResult sweepBlocksObb(Entity self, Level level, Vec3 startPos, Vec3 delta,
                                                 double moveDist, double noseForward,
                                                 double maxSubstepDist, int maxSubsteps) {
        List<OBB> obbs = ((OBBEntity) self).getOBBs();
        Vec3 entityPos = self.position();               // post-move; sample offset is relative to it
        double bodyLen = noseForward > 1.0E-3 ? noseForward : moveDist;
        double pitch = Math.clamp(bodyLen, 0.5, maxSubstepDist);
        int n = Mth.clamp((int) Math.ceil(moveDist / pitch), 1, maxSubsteps);

        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= n; i++) {
            Vec3 sample = (i == n) ? startPos.add(delta) : startPos.add(delta.scale(i / (double) n));
            Vec3 off = sample.subtract(entityPos);
            for (OBB base : obbs) {
                OBB moved = base.move(off);
                AABB box = moved.bounds();
                int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX);
                int y0 = Mth.floor(box.minY), y1 = Mth.floor(box.maxY);
                int z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            mp.set(x, y, z);
                            BlockState state = level.getBlockState(mp);
                            if (state.isAir()) {
                                continue;
                            }
                            VoxelShape shape = state.getCollisionShape(level, mp);
                            if (shape.isEmpty()) {
                                continue;
                            }
                            AABB blockBox = shape.bounds().move(x, y, z);
                            if (OBB.isColliding(moved, blockBox)) {
                                return new BlockHitResult(sample,
                                        Direction.getNearest(-delta.x, -delta.y, -delta.z),
                                        mp.immutable(), false);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static BlockHitResult miss(Vec3 at) {
        return BlockHitResult.miss(at, Direction.UP, BlockPos.containing(at));
    }
}
