package com.wf.wflib.grenade;

import com.norwood.ahf.hit.HitRegistration;
import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.api.PreciseHitbox;
import com.wf.wflib.damage.BlastDamageSource;
import com.wf.wflib.damage.DamageClass;
import com.wf.wflib.damage.WFDamageSources;
import com.wf.wflib.round.Rounds;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.EnumMap;

/**
 * Fragment fan: {@code rays} directions (Fibonacci sphere, random rotation per burst). Per victim only rays inside
 * its bounding cone are tested, and only rays that enter its shape pay a block clip => cost ~ hits, not rays.
 * Hit damage {@code perHit * e^(-t/decay)}, t = ray length to entry.
 */
public final class FragRays {

    private FragRays() {
    }

    public record Result(int victims, int hits) {
    }

    /** Unit directions, Fibonacci sphere rotated by {@code turn}. */
    static Vec3[] directions(int rays, Quaternionf turn) {
        Vec3[] out = new Vec3[rays];
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        Vector3f v = new Vector3f();
        for (int i = 0; i < rays; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / rays;
            double r = Math.sqrt(1.0 - y * y);
            double a = golden * i;
            v.set((float) (Math.cos(a) * r), (float) y, (float) (Math.sin(a) * r));
            turn.transform(v);
            out[i] = new Vec3(v);
        }
        return out;
    }

    public static Result burst(Level level, Vec3 pos, int rays, double range, float perHit, double decay,
                               @Nullable Entity attacker, @Nullable Entity direct, RandomSource random) {
        Quaternionf turn = new Quaternionf().rotationXYZ(random.nextFloat() * 6.2832f, random.nextFloat() * 6.2832f,
                random.nextFloat() * 6.2832f);
        Vec3[] dirs = directions(rays, turn);
        int victims = 0;
        int hits = 0;
        for (Entity victim : level.getEntities(direct, new AABB(pos, pos).inflate(range),
                e -> e.isAlive() && e instanceof LivingEntity && !e.isSpectator() && !e.ignoreExplosion(null))) {
            AABB box = victim.getBoundingBox().minmax(HitRegistration.registrationBox(victim)).inflate(Rounds.PICK_INFLATE);
            Vec3 centre = box.getCenter();
            Vec3 to = centre.subtract(pos);
            double d = to.length();
            double reach = Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                    + box.getZsize() * box.getZsize()) * 0.5;
            if (d - reach > range) {
                continue;
            }
            double cosLimit = d <= reach ? -1.0 : Math.cos(Math.asin(Math.min(1.0, reach / d)));
            Vec3 axis = d > 1.0E-6 ? to.scale(1.0 / d) : new Vec3(0, 1, 0);
            float damage = 0;
            int struck = 0;
            EnumMap<HitboxPart, Float> byPart = new EnumMap<>(HitboxPart.class);
            HitboxPart part = null;
            Vec3 through = null;
            float best = 0;
            for (Vec3 dir : dirs) {
                if (dir.dot(axis) < cosLimit) {
                    continue;
                }
                Vec3 end = pos.add(dir.scale(range));
                Vec3 entry;
                HitboxPart rayPart = null;
                if (victim instanceof PreciseHitbox precise) {
                    entry = precise.clip(pos, end);
                } else {
                    Rounds.LiveHit hit = Rounds.hitLive((LivingEntity) victim, pos, end);
                    entry = hit == null ? null : hit.at();
                    rayPart = hit == null ? null : hit.part();
                }
                if (entry == null) {
                    continue;
                }
                if (level.clip(new ClipContext(pos, entry, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                        CollisionContext.empty())).getType() != HitResult.Type.MISS) {
                    continue;
                }
                float share = (float) (perHit * Math.exp(-entry.distanceTo(pos) / decay));
                damage += share;
                struck++;
                float onPart = rayPart == null ? share : byPart.merge(rayPart, share, Float::sum);
                if (through == null || onPart > best) {
                    best = onPart;
                    part = rayPart;
                    through = entry;
                }
            }
            if (struck > 0 && damage >= 0.5f) {
                victims++;
                hits += struck;
                victim.hurt(BlastDamageSource.of(WFDamageSources.create(level, DamageClass.EXPLOSIVE, attacker, direct),
                        victim, pos, through, part), damage);
            }
        }
        return new Result(victims, hits);
    }
}
