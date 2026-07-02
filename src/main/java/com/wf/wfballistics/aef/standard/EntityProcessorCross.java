package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.ICustomDamageHandler;
import com.wf.wfballistics.aef.interfaces.IEntityProcessor;
import com.wf.wfballistics.aef.interfaces.IEntityRangeMutator;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.ProtectionEnchantment;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The recommended entity processor. It improves on a vanilla-style blast in two ways:
 *
 * <ul>
 *   <li><b>Nearest-surface falloff.</b> An entity's blast distance is measured from the closest point of
 *       its bounding box, not its feet, so a large or partially-sheltered mob is damaged consistently
 *       regardless of which part of it faces the epicentre.</li>
 *   <li><b>Multi-node line-of-sight.</b> Cover is sampled from up to seven points (the centre plus six
 *       axis-offset "nodes" at {@code nodeDist}) and the most-exposed sample wins. A target peeking around
 *       a corner therefore still takes damage, instead of being fully shadowed by a single block between it
 *       and the exact centre.</li>
 * </ul>
 *
 * <p>Damage is collected into a map and applied once per entity (keeping the maximum), so an entity sampled
 * by several nodes is never hit twice.
 */
public class EntityProcessorCross implements IEntityProcessor {

    protected double nodeDist;
    protected IEntityRangeMutator range;
    protected ICustomDamageHandler damage;
    protected double knockbackMult = 1D;
    protected boolean allowSelfDamage = false;

    public EntityProcessorCross() {
        this(0);
    }

    /**
     * @param nodeDist spacing of the six line-of-sight sample nodes; {@code 0} samples the centre only
     */
    public EntityProcessorCross(double nodeDist) {
        this.nodeDist = nodeDist;
    }

    /**
     * Distance from the blast centre to the nearest point of the entity's box, as a fraction of {@code size}.
     */
    protected static double nearestSurfaceDistanceScaled(Entity entity, double x, double y, double z, float size) {
        AABB box = entity.getBoundingBox();
        double xDist = (box.minX <= x && box.maxX >= x) ? 0 : Math.min(Math.abs(box.minX - x), Math.abs(box.maxX - x));
        double yDist = (box.minY <= y && box.maxY >= y) ? 0 : Math.min(Math.abs(box.minY - y), Math.abs(box.maxY - y));
        double zDist = (box.minZ <= z && box.maxZ >= z) ? 0 : Math.min(Math.abs(box.minZ - z), Math.abs(box.maxZ - z));
        return Math.sqrt(xDist * xDist + yDist * yDist + zDist * zDist) / size;
    }

    protected static DamageSource explosionDamage(ExplosionAEF explosion) {
        return explosion.level.damageSources().explosion(explosion.compat);
    }

    public EntityProcessorCross setAllowSelfDamage() {
        this.allowSelfDamage = true;
        return this;
    }

    public EntityProcessorCross setKnockback(double mult) {
        this.knockbackMult = mult;
        return this;
    }

    @Override
    public Map<Player, Vec3> process(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        Map<Player, Vec3> affectedPlayers = new HashMap<>();

        size *= 2.0F;
        if (range != null) size = range.mutateRange(explosion, size);

        AABB area = new AABB(x - size - 1, y - size - 1, z - size - 1, x + size + 1, y + size + 1, z + size + 1);
        List<Entity> list = level.getEntities(allowSelfDamage ? null : explosion.exploder, area);
        ForgeEventFactory.onExplosionDetonate(level, explosion.compat, list, size);

        Vec3[] nodes = buildNodes(x, y, z);

        // First pass: knockback + record the damage each entity should take.
        Map<Entity, Float> damageMap = new HashMap<>();
        for (Entity entity : list) {
            double distanceScaled = nearestSurfaceDistanceScaled(entity, x, y, z, size);
            if (distanceScaled > 1.0D) continue;

            double deltaX = entity.getX() - x;
            double deltaY = entity.getY() + entity.getEyeHeight() - y;
            double deltaZ = entity.getZ() - z;
            double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
            if (distance == 0.0D) continue;

            deltaX /= distance;
            deltaY /= distance;
            deltaZ /= distance;

            double density = 0;
            for (Vec3 node : nodes) {
                density = Math.max(density, Explosion.getSeenPercent(node, entity));
            }

            double knockback = (1.0D - distanceScaled) * density;
            float dmg = calculateDamage(distanceScaled, density, knockback, size);
            damageMap.merge(entity, dmg, Math::max);

            double enchKnockback = entity instanceof LivingEntity living
                    ? ProtectionEnchantment.getExplosionKnockbackAfterDampener(living, knockback)
                    : knockback;

            if (shouldDealKnockback(entity)) {
                entity.setDeltaMovement(entity.getDeltaMovement().add(
                        deltaX * enchKnockback * knockbackMult,
                        deltaY * enchKnockback * knockbackMult,
                        deltaZ * enchKnockback * knockbackMult));
            }

            if (entity instanceof Player player) {
                affectedPlayers.put(player, new Vec3(
                        deltaX * knockback * knockbackMult,
                        deltaY * knockback * knockbackMult,
                        deltaZ * knockback * knockbackMult));
            }
        }

        // Second pass: actually deal the damage + any custom payload.
        for (Map.Entry<Entity, Float> entry : damageMap.entrySet()) {
            Entity entity = entry.getKey();
            attackEntity(entity, explosion, entry.getValue());
            if (damage != null) {
                damage.handleAttack(explosion, entity, nearestSurfaceDistanceScaled(entity, x, y, z, size));
            }
        }

        return affectedPlayers;
    }

    private Vec3[] buildNodes(double x, double y, double z) {
        if (nodeDist <= 0) {
            return new Vec3[]{new Vec3(x, y, z)};
        }
        Vec3[] nodes = new Vec3[7];
        Direction[] dirs = Direction.values();
        for (int i = 0; i < 6; i++) {
            Direction d = dirs[i];
            nodes[i] = new Vec3(x + d.getStepX() * nodeDist, y + d.getStepY() * nodeDist, z + d.getStepZ() * nodeDist);
        }
        nodes[6] = new Vec3(x, y, z);
        return nodes;
    }

    /**
     * Deals the blast damage. Override to change the damage source or to route through a custom damage
     * pipeline (see {@link EntityProcessorCrossSmooth}).
     */
    public void attackEntity(Entity entity, ExplosionAEF explosion, float amount) {
        entity.hurt(explosionDamage(explosion), amount);
    }

    /**
     * Vanilla-style scaling damage. Override for flat or otherwise-shaped falloff.
     */
    public float calculateDamage(double distanceScaled, double density, double knockback, float size) {
        return (int) ((knockback * knockback + knockback) / 2.0D * 8.0D * size + 1.0D);
    }

    /**
     * Whether a given entity should receive knockback at all; override to exempt e.g. projectiles.
     */
    protected boolean shouldDealKnockback(Entity entity) {
        return true;
    }

    public EntityProcessorCross withRangeMod(float mod) {
        this.range = (explosion, range) -> range * mod;
        return this;
    }

    public EntityProcessorCross withDamageMod(ICustomDamageHandler damage) {
        this.damage = damage;
        return this;
    }
}
