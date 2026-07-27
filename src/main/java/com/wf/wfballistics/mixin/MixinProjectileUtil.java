package com.wf.wfballistics.mixin;

import com.wf.wfballistics.entity.OBBEntity;
import com.wf.wfballistics.entity.OBBEntityTracker;
import com.wf.wfballistics.util.OBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Routes projectile entity hit-testing through {@link OBB}s for any {@link OBBEntity} (missiles), so a
 * bullet/missile hits the oriented box that wraps the model rather than the coarse vanilla AABB.
 *
 * <p>Ported from SuperbWarfare's {@code ProjectileUtilMixin} (SW's hit particles/sounds and per-part hit
 * tracking are dropped). Instead of SW's extra spatial query, this iterates the small per-level
 * {@link OBBEntityTracker} set and early-outs entirely when the level has no OBB entities.
 *
 * <p>The OBB is the sole authority for hitting a missile. The HEAD injections resolve a precise OBB hit;
 * if none is found they do nothing, and the {@code getEntities} redirect below drops OBB entities from
 * vanilla's fallback scan so a projectile can never hit the missile's coarse enclosing AABB. That AABB is
 * left purely for rendering, frustum culling, broadphase and F3+B. Non-OBB entities are untouched, so
 * vanilla AABB hit-testing runs unchanged for everything else.
 */
@Mixin(ProjectileUtil.class)
public class MixinProjectileUtil {

    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true)
    private static void wfballistics$getEntityHitResult(Level pLevel, Entity pProjectile, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox, Predicate<Entity> pFilter, float pInflationAmount, CallbackInfoReturnable<EntityHitResult> cir) {
        if (!OBBEntityTracker.hasAny(pLevel)) return;

        AABB search = pBoundingBox.inflate(8);
        Vector3d startVec = OBB.vec3ToVector3d(pStartVec);
        Vector3d from = OBB.vec3ToVector3d(pStartVec);
        Vector3d to = OBB.vec3ToVector3d(pEndVec);
        double pDistance = pStartVec.distanceToSqr(pEndVec);

        for (Entity entity : OBBEntityTracker.get(pLevel)) {
            if (entity == pProjectile || !(entity instanceof OBBEntity obbEntity) || obbEntity.enableAABB()) continue;
            if (!pFilter.test(entity) || !entity.getBoundingBox().intersects(search)) continue;
            if (pProjectile instanceof Projectile projectile &&
                    (projectile.getOwner() == entity || entity.getPassengers().contains(projectile.getOwner()))) {
                continue;
            }
            for (var obb : obbEntity.getOBBs()) {
                obb = obb.inflate(entity.getPickRadius() * 2);
                Optional<Vector3d> optional = obb.clip(from, to);
                if (obb.contains(pStartVec)) {
                    if (pDistance >= 0) {
                        cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(optional.orElse(startVec))));
                        return;
                    }
                } else if (optional.isPresent()) {
                    var vec = new Vector3d(optional.get());
                    double d1 = pStartVec.distanceToSqr(OBB.vector3dToVec3(vec));
                    if (d1 < pDistance || pDistance == 0) {
                        cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(vec)));
                        return;
                    }
                }
            }
        }
    }

    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true)
    private static void wfballistics$getEntityHitResult(Entity pShooter, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox, Predicate<Entity> pFilter, double pDistance, CallbackInfoReturnable<EntityHitResult> cir) {
        Level level = pShooter.level();
        if (!OBBEntityTracker.hasAny(level)) return;

        AABB search = pBoundingBox.inflate(8);
        Vector3d startVec = OBB.vec3ToVector3d(pStartVec);
        Vector3d from = OBB.vec3ToVector3d(pStartVec);
        Vector3d to = OBB.vec3ToVector3d(pEndVec);

        for (Entity entity : OBBEntityTracker.get(level)) {
            if (entity == pShooter || !(entity instanceof OBBEntity obbEntity) || obbEntity.enableAABB()) continue;
            if (!pFilter.test(entity) || !entity.getBoundingBox().intersects(search)) continue;
            if (entity.getPassengers().contains(pShooter)) continue;

            for (var obb : obbEntity.getOBBs()) {
                obb = obb.inflate(entity.getPickRadius() * 2);
                Optional<Vector3d> optional = obb.clip(from, to);
                if (obb.contains(pStartVec)) {
                    if (pDistance >= 0) {
                        cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(optional.orElse(startVec))));
                        return;
                    }
                } else if (optional.isPresent()) {
                    var vec = new Vector3d(optional.get());
                    double d1 = pStartVec.distanceToSqr(OBB.vector3dToVec3(vec));
                    if (d1 < pDistance || pDistance == 0) {
                        if (entity.getRootVehicle() == pShooter.getRootVehicle() && !entity.canRiderInteract()) {
                            if (pDistance == 0) {
                                cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(vec)));
                                return;
                            }
                        } else {
                            cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(vec)));
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Vanilla's fallback loop (run when the HEAD injections above found no OBB hit) clips each candidate's
     * {@link Entity#getBoundingBox()}. For a rotated missile that AABB is the fat enclosing box of the OBB,
     * so a ray grazing an empty corner would register a bogus AABB hit. Dropping OBB entities from this scan
     * leaves the OBB as the only way to hit them; the enclosing AABB stays for rendering/culling/broadphase.
     * Guarded by {@link OBBEntityTracker#hasAny} so it's a no-op in levels with no missiles.
     */
    @Redirect(method = {
            "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<Entity> wfballistics$dropObbFromAabbScan(Level level, Entity entity, AABB box, Predicate<? super Entity> predicate) {
        List<Entity> candidates = level.getEntities(entity, box, predicate);
        if (!candidates.isEmpty() && OBBEntityTracker.hasAny(level)) {
            candidates.removeIf(e -> e instanceof OBBEntity obbEntity && !obbEntity.enableAABB());
        }
        return candidates;
    }
}
