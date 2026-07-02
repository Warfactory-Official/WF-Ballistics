package com.wf.wfballistics.mixin;

import com.wf.wfballistics.entity.OBBEntity;
import com.wf.wfballistics.util.OBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/**
 * Ensures {@link OBBEntity}s (missiles) are included in a projectile's candidate-entity gather even when
 * the query AABB only overlaps their oriented box (not their coarse vanilla AABB).
 *
 * <p>Ported from SuperbWarfare's {@code LevelMixin}: after the vanilla box query returns, scan all entities
 * and add any OBB entity whose OBB actually collides with the query box.
 */
@Mixin(Level.class)
public abstract class MixinLevel {

    @Shadow
    protected abstract LevelEntityGetter<Entity> getEntities();

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"))
    public void wfballistics$getEntities(Entity pEntity, AABB pBoundingBox, Predicate<? super Entity> pPredicate, CallbackInfoReturnable<List<Entity>> cir) {
        if (!(pEntity instanceof Projectile)) return;

        StreamSupport.stream(this.getEntities().getAll().spliterator(), false).filter(e -> pPredicate.test(e) && e != pEntity)
                .forEach(entity -> {
                            if (entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
                                for (OBB obb : obbEntity.getOBBs()) {
                                    if (OBB.isColliding(obb, pBoundingBox) && !cir.getReturnValue().contains(entity)) {
                                        cir.getReturnValue().add(entity);
                                    }
                                }
                            }
                        }
                );
    }
}
