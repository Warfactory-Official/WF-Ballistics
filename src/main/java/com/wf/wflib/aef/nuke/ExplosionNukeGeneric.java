package com.wf.wflib.aef.nuke;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class ExplosionNukeGeneric {
    public static void dealDamage(Level level, Vec3 pos, double radius) {
        dealDamage(level, pos, radius, 250F);
    }

    public static void dealDamage(Level level, Vec3 pos, double radius, float maxDamage) {
        List<Entity> entities = level.getEntities((Entity) null, new AABB(pos, pos).inflate(radius), Entity::isAlive);
        for (Entity entity : entities) {
            double dist = Math.sqrt(entity.distanceToSqr(pos));
            if (dist <= radius) {
                Vec3 eyePosition = entity.getEyePosition();
                if (!isExplosionExempt(entity) && isObstructed(level, pos, eyePosition)) {
                    double damage = maxDamage * (radius - dist) / radius;
                    // TODO: dedicated radiation damage source (upstream had one); generic for now.
                    entity.hurt(level.damageSources().generic(), (float) damage);
                    entity.igniteForSeconds(5);
                    Vec3 knock = eyePosition.subtract(pos).normalize().scale(0.2D);
                    entity.addDeltaMovement(knock);
                }
            }
        }
    }

    private static boolean isExplosionExempt(Entity entity) {
        // ignoreExplosion(Explosion) needs an argument; no Explosion context here => null.
        return entity.ignoreExplosion(null);
    }

    public static boolean isObstructed(Level level, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }
}
