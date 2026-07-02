package com.wf.wfballistics.util;

import com.wf.wfballistics.entity.OBBEntity;
import com.wf.wfballistics.sim.MissileSimConfig;
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
import org.joml.Vector3d;

import java.util.List;
import java.util.function.Predicate;

/**
 * Continuous ("swept") collision for fast projectiles, used so a missile moving many blocks in a single
 * 20&nbsp;TPS tick can't tunnel through a wall.
 *
 * <p>The caller passes the <em>pre-move</em> {@code startPos} explicitly, so this is agnostic to whether
 * the entity has already been moved this tick. The traversed corridor {@code [startPos, startPos+delta]}
 * (extended forward by {@code noseForward} to account for an elongated body) is tested for the first
 * block or entity hit and returned as a vanilla {@link HitResult}, so callers reuse their existing impact
 * handler unchanged.
 *
 * <p>Blocks are found with {@link Level#clip(ClipContext)} — a DDA that visits every cell along the ray,
 * so it never tunnels regardless of speed. The corridor is split into contiguous sub-segments only to
 * bound per-call setup and (in {@code OBB_SWEEP} mode) to sample the oriented body box. Entities are
 * found with a single {@link ProjectileUtil#getEntityHitResult} broadphase capped at the block hit, which
 * already routes through the missile OBBs via {@code MixinProjectileUtil} and is continuous for OBB/AABB
 * targets. Slow projectiles collapse to a single ray + single broadphase (the common, near-free case).
 */
public final class SweptCollision {

    private SweptCollision() {
    }

    /**
     * @param self           the projectile (excluded from its own hit test)
     * @param level          the level to trace in
     * @param startPos       pre-move position of the projectile origin
     * @param delta          this tick's movement vector
     * @param noseForward    distance from the origin to the body's front face along the heading (0 for a
     *                       point-like projectile); folded into the ray length so the nose, not the base,
     *                       triggers the hit
     * @param filter         entity hit predicate
     * @param maxSubstepDist max length of one sub-segment (blocks)
     * @param maxSubsteps    hard clamp on sub-segments per call
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

        // Entities: one broadphase over the corridor, capped at the block hit so nothing behind a wall is
        // reported. getEntityHitResult already returns the nearest hit and (via the mixin) tests missile OBBs.
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
        double pitch = Math.min(maxSubstepDist, Math.max(0.5, bodyLen));
        int n = Mth.clamp((int) Math.ceil(moveDist / pitch), 1, maxSubsteps);

        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= n; i++) {
            Vec3 sample = (i == n) ? startPos.add(delta) : startPos.add(delta.scale(i / (double) n));
            Vec3 off = sample.subtract(entityPos);
            for (OBB base : obbs) {
                OBB moved = base.move(off);
                AABB box = enclosingAABB(moved);
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

    /**
     * Axis-aligned box enclosing the oriented box (from its 8 world-space corners).
     */
    private static AABB enclosingAABB(OBB obb) {
        Vector3d[] v = obb.getVertices();
        double minX = v[0].x, minY = v[0].y, minZ = v[0].z;
        double maxX = minX, maxY = minY, maxZ = minZ;
        for (int i = 1; i < v.length; i++) {
            Vector3d p = v[i];
            if (p.x < minX) minX = p.x;
            else if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            else if (p.y > maxY) maxY = p.y;
            if (p.z < minZ) minZ = p.z;
            else if (p.z > maxZ) maxZ = p.z;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static BlockHitResult miss(Vec3 at) {
        return BlockHitResult.miss(at, Direction.UP, BlockPos.containing(at));
    }
}
