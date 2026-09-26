package com.wf.wflib.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.wf.wflib.api.PreciseHitbox;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Vanilla's per-candidate AABB clip -> {@link PreciseHitbox#clip} for shaped entities. Candidates still come from
 * vanilla's AABB scan: a shape must stay inside its entity's AABB (+ query inflation) to be found.
 */
@Mixin(ProjectileUtil.class)
public class MixinProjectileUtil {

    private static final String PICK = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;";
    private static final String PROJECTILE = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;";
    private static final String CLIP = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;";

    /** Crosshair pick: a carrier's AABB holds its whole deck, so AABB picks stole every click there. */
    @WrapOperation(method = PICK, at = @At(value = "INVOKE", target = CLIP))
    private static Optional<Vec3> wflib$pickShape(AABB aabb, Vec3 start, Vec3 end, Operation<Optional<Vec3>> original,
                                                  @Local(ordinal = 2) Entity candidate) {
        if (candidate instanceof PreciseHitbox shape) {
            return Optional.ofNullable(shape.clip(start, end));
        }
        return original.call(aabb, start, end);
    }

    /** Eye inside the AABB is no hit by itself for a shaped entity. */
    @WrapOperation(method = PICK, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;contains(Lnet/minecraft/world/phys/Vec3;)Z"))
    private static boolean wflib$containsUnlessShaped(AABB aabb, Vec3 point, Operation<Boolean> original,
                                                      @Local(ordinal = 2) Entity candidate) {
        return !(candidate instanceof PreciseHitbox) && original.call(aabb, point);
    }

    /** Projectile sweep. A shaped entity never takes a hit from its own owner or the owner's mount. */
    @WrapOperation(method = PROJECTILE, at = @At(value = "INVOKE", target = CLIP))
    private static Optional<Vec3> wflib$sweepShape(AABB aabb, Vec3 start, Vec3 end, Operation<Optional<Vec3>> original,
                                                   @Local(ordinal = 0, argsOnly = true) Entity projectile,
                                                   @Local(ordinal = 2) Entity candidate) {
        if (!(candidate instanceof PreciseHitbox shape)) {
            return original.call(aabb, start, end);
        }
        if (projectile instanceof Projectile p && p.getOwner() != null
                && (p.getOwner() == candidate || candidate.getPassengers().contains(p.getOwner()))) {
            return Optional.empty();
        }
        return Optional.ofNullable(shape.clip(start, end));
    }

    /** Vanilla returns the target's origin as the hit point; a shaped target reports where the sweep met it. */
    @Inject(method = PROJECTILE, at = @At("RETURN"), cancellable = true)
    private static void wflib$shapeHitPoint(Level level, Entity projectile, Vec3 start, Vec3 end, AABB box,
                                            Predicate<Entity> filter, float inflation,
                                            CallbackInfoReturnable<EntityHitResult> cir) {
        EntityHitResult hit = cir.getReturnValue();
        if (hit != null && hit.getEntity() instanceof PreciseHitbox shape) {
            Vec3 at = shape.clip(start, end);
            cir.setReturnValue(new EntityHitResult(hit.getEntity(), at != null ? at : hit.getLocation()));
        }
    }
}
