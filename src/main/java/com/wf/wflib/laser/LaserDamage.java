package com.wf.wflib.laser;

import com.wf.wflib.damage.WFDamageTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Directed energy: {@code damage = rated / (1 + resistance (m / 2 kg)^1.5 (1 + armour / 5 mm))}.
 * 2 kg unarmoured quad takes half rated; MQ-9-class (~2 t) ~ 1/36000 => FPV killer, not air defence.
 */
public final class LaserDamage {

    public static final double REFERENCE_MASS = 2.0;
    public static final double MASS_EXPONENT = 1.5;
    public static final double REFERENCE_ARMOR = 5.0;
    /** Players and mobs without {@link LaserTarget}, kg. */
    private static final double LIVING_MASS = 80.0;
    /** Other entities: bounding box at this density, kg/m³. */
    private static final double BOX_DENSITY = 100.0;
    /** Beam drawn to clients this far away. */
    private static final double VISIBLE = 256.0;

    private LaserDamage() {
    }

    public static float perTick(float rated, double massKg, double armorMm, double resistance) {
        return (float) (rated / (1.0 + resistance * Math.pow(massKg / REFERENCE_MASS, MASS_EXPONENT)
                * (1.0 + armorMm / REFERENCE_ARMOR)));
    }

    public static double massOf(Entity target) {
        if (target instanceof LaserTarget t) {
            return t.laserMassKg();
        }
        if (target instanceof LivingEntity) {
            return LIVING_MASS;
        }
        return target.getBbWidth() * target.getBbWidth() * target.getBbHeight() * BOX_DENSITY;
    }

    /** One tick of beam {@code from} -> {@code target}; drawn for everyone near either end. */
    public static void burn(ServerLevel level, Vec3 from, Entity target, float rated, @Nullable Entity shooter) {
        Vec3 to = target.getBoundingBox().getCenter();
        Vec3 dir = to.subtract(from).normalize();
        double armor = target instanceof LaserTarget t ? t.laserArmorMm(dir) : 0.0;
        double resistance = target instanceof LaserTarget t ? t.laserResistance() : 1.0;
        float damage = perTick(rated, massOf(target), armor, resistance);
        DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .getHolderOrThrow(WFDamageTypes.LASER), shooter, shooter, from);
        target.invulnerableTime = 0;
        target.hurt(source, damage);
        draw(level, from, to);
    }

    /** Beam that hit nothing (manual fire into the air or a block). */
    public static void draw(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 mid = from.add(to).scale(0.5);
        PacketDistributor.sendToPlayersNear(level, null, mid.x, mid.y, mid.z,
                VISIBLE + from.distanceTo(to) * 0.5, new LaserBeamPacket(from.toVector3f(), to.toVector3f()));
    }
}
