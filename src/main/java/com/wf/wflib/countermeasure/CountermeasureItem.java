package com.wf.wflib.countermeasure;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Launcher load for a vehicle dispenser: {@link #deploy} spawns the countermeasure. */
public class CountermeasureItem extends Item {

    /** {@code speed} blocks/tick along the launch heading. */
    public enum Kind {
        FLARE(0.5),
        SMOKE(1.0),
        APS(15.0);

        public final double speed;

        Kind(double speed) {
            this.speed = speed;
        }
    }

    private final Kind kind;

    public CountermeasureItem(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Heading + triangle spread {@code 0.0172275 x inaccuracy}, then {@code carrierVelocity}. Flares ignore
     * spread. Carrier: never hit by its own grenade.
     */
    public Entity deploy(ServerLevel level, Vec3 from, Vec3 heading, float inaccuracy, Vec3 carrierVelocity,
                         @Nullable Entity shooter, @Nullable Entity carrier) {
        Projectile e = switch (kind) {
            case FLARE -> new FlareEntity(Countermeasures.FLARE.get(), level);
            case SMOKE -> new SmokeGrenadeEntity(Countermeasures.SMOKE_GRENADE.get(), level);
            case APS -> new ApsGrenadeEntity(Countermeasures.APS_GRENADE.get(), level);
        };
        Vec3 dir = heading.normalize();
        if (kind != Kind.FLARE) {
            RandomSource r = level.random;
            double s = 0.0172275 * inaccuracy;
            dir = dir.add(r.triangle(0, s), r.triangle(0, s), r.triangle(0, s)).normalize();
            ((CountermeasureGrenade) e).carrier(carrier);
        }
        e.setPos(from);
        e.setDeltaMovement(dir.scale(kind.speed).add(carrierVelocity));
        e.setOwner(shooter);
        level.addFreshEntity(e);
        return e;
    }

    @Nullable
    public static CountermeasureItem of(ItemStack stack) {
        return stack.getItem() instanceof CountermeasureItem c ? c : null;
    }
}
