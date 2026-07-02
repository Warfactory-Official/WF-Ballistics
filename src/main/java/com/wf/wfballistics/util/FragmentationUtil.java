package com.wf.wfballistics.util;

import com.wf.wfballistics.entity.BombletEntity;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class FragmentationUtil {

    private FragmentationUtil() {
    }

    /**
     * All-round spherical burst with the default (small-explosion) warhead.
     */
    public static List<BombletEntity> burst(Level level, Vec3 origin, int count, double speed) {
        return burst(level, origin, count, speed, speed * 0.25,
                "standard", BombletEntity.STANDARD, BombletEntity.DEFAULT_FUSE, null);
    }

    /**
     * All-round spherical burst.
     *
     * @param count        number of bomblets
     * @param speed        base launch speed (blocks/tick)
     * @param speedJitter  +/- random variation added to {@code speed}
     * @param detonationId registered warhead id (persists across save/load)
     * @param detonation   the warhead instance to run (usually the one {@code detonationId} maps to)
     * @param fuse         self-detonation fuse in ticks (see {@link BombletEntity#DEFAULT_FUSE})
     * @param owner        who fired the burst (skipped by the bomblets' hit checks, credited for damage); may be null
     */
    public static List<BombletEntity> burst(Level level, Vec3 origin, int count, double speed, double speedJitter,
                                            String detonationId, BombletEntity.Detonation detonation,
                                            int fuse, Entity owner) {
        List<BombletEntity> out = new ArrayList<>();
        if (level.isClientSide || count <= 0) {
            return out;
        }
        RandomSource random = level.random;
        for (int i = 0; i < count; i++) {
            Vec3 dir = randomUnitVector(random);
            out.add(launch(level, origin, dir, speed, speedJitter, detonation, detonationId, fuse, owner, random));
        }
        return out;
    }


    public static List<BombletEntity> cone(Level level, Vec3 origin, Vec3 direction, double halfAngleRad,
                                           int count, double speed, double speedJitter, String detonationId,
                                           BombletEntity.Detonation detonation, int fuse, Entity owner) {
        List<BombletEntity> out = new ArrayList<>();
        if (level.isClientSide || count <= 0) {
            return out;
        }
        RandomSource random = level.random;
        Vec3 axis = direction.lengthSqr() < 1.0e-8 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        for (int i = 0; i < count; i++) {
            Vec3 dir = randomConeVector(random, axis, halfAngleRad);
            out.add(launch(level, origin, dir, speed, speedJitter, detonation, detonationId, fuse, owner, random));
        }
        return out;
    }

    private static BombletEntity launch(Level level, Vec3 origin, Vec3 dir, double speed, double speedJitter,
                                        BombletEntity.Detonation detonation, String detonationId, int fuse,
                                        Entity owner, RandomSource random) {
        double s = Math.max(0.0, speed + (random.nextDouble() * 2.0 - 1.0) * speedJitter);
        BombletEntity bomblet = new BombletEntity(level, origin, dir.scale(s), detonation, detonationId, fuse);
        if (owner != null) {
            bomblet.setOwner(owner);
        }
        level.addFreshEntity(bomblet);
        return bomblet;
    }

    private static Vec3 randomUnitVector(RandomSource random) {
        double u = random.nextDouble() * 2.0 - 1.0;
        double phi = random.nextDouble() * Math.PI * 2.0;
        double s = Math.sqrt(Math.max(0.0, 1.0 - u * u));
        return new Vec3(s * Math.cos(phi), u, s * Math.sin(phi));
    }

    private static Vec3 randomConeVector(RandomSource random, Vec3 axis, double halfAngleRad) {
        double cosTheta = Mth.lerp(random.nextDouble(), Math.cos(halfAngleRad), 1.0);
        double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
        double phi = random.nextDouble() * Math.PI * 2.0;
        Vec3 local = new Vec3(sinTheta * Math.cos(phi), sinTheta * Math.sin(phi), cosTheta);
        return rotateFromZ(local, axis);
    }

    private static Vec3 rotateFromZ(Vec3 local, Vec3 axis) {
        Vec3 z = axis;
        Vec3 up = Math.abs(z.y) < 0.999 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 x = up.cross(z).normalize();
        Vec3 y = z.cross(x);
        return x.scale(local.x).add(y.scale(local.y)).add(z.scale(local.z));
    }
}
