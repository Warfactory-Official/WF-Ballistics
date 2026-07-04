package com.wf.wfballistics.aef.nuke;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Applies a nuclear blast's area damage to nearby entities (range-falloff damage, fire, and knockback).
 */
public class ExplosionNukeGeneric {
    public static void dealDamage(Level level, Vec3 pos, double radius) {
        dealDamage(level, pos, radius, 250F);
    }

    public static void dealDamage(Level level, Vec3 pos, double radius, float maxDamage) {
        List<Entity> entities = level.getEntities((Entity) null, new AABB(pos, pos).expandTowards(radius, radius, radius), Entity::isAlive);
        for (Entity entity : entities) {
            double dist = entity.distanceToSqr(pos);
            if (dist <= radius) {
                Vec3 eyePosition = entity.getEyePosition();
                if (!isExplosionExempt(entity) && isObstructed(level, pos, eyePosition)) {
                    double damage = maxDamage * (radius - dist) / radius;
                    // The source mod used a dedicated radiation damage source here; we fall back to generic for now.
                    entity.hurt(level.damageSources().generic(), (float) damage);
                    entity.setSecondsOnFire(5);
                    Vec3 knock = eyePosition.subtract(pos).normalize().scale(0.2D);
                    entity.addDeltaMovement(knock);
                }
            }
        }
    }

    private static boolean isExplosionExempt(Entity entity) {
        return entity.ignoreExplosion();
    }

    public static boolean isObstructed(Level level, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() == HitResult.Type.MISS;
    }
}
