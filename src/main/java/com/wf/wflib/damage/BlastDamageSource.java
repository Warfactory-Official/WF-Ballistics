package com.wf.wflib.damage;

import com.norwood.ahf.hit.AhfHitSource;
import com.norwood.ahf.hit.HitboxApi;
import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.round.RoundDamageSource;
import com.wf.wflib.round.Rounds;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One victim's share of a blast or fragment burst. AHF reads blast origin -> struck point, never the exploder's eye:
 * a base source with direct == attacker (launcher, thrower) is melee to AHF and gap-tested on the attacker's look.
 * Part always set => never a gap.
 */
public final class BlastDamageSource extends DamageSource implements AhfHitSource {

    private final Vec3 from;
    private final Vec3 to;
    private final HitboxPart part;

    private BlastDamageSource(DamageSource base, Vec3 from, Vec3 to, HitboxPart part) {
        super(base.typeHolder(), base.getDirectEntity(), base.getEntity(), from);
        this.from = from;
        this.to = to;
        this.part = part;
    }

    /**
     * {@code base}'s type and entities on segment {@code origin -> through}, extended past the victim. Part: {@code
     * part}, else AHF's on the segment ({@link Rounds#hitLive}), else banded at {@code through}, else torso.
     * Non-living victim => {@code base}.
     */
    public static DamageSource of(DamageSource base, Entity victim, Vec3 origin, Vec3 through,
                                  @Nullable HitboxPart part) {
        if (!(victim instanceof LivingEntity living)) {
            return base;
        }
        Vec3 d = through.subtract(origin);
        Vec3 to = d.lengthSqr() < 1.0e-12 ? through
                : through.add(d.normalize().scale(victim.getBoundingBox().getSize() * 2.0));
        if (part == null) {
            Rounds.LiveHit hit = Rounds.hitLive(living, origin, to);
            part = hit != null ? hit.part() : null;
        }
        if (part == null) {
            part = HitboxApi.classifyLocal(living, through);
        }
        return new BlastDamageSource(base, origin, to, part != null ? part : HitboxPart.TORSO);
    }

    @Override
    public Vec3 hitFrom() {
        return this.from;
    }

    @Override
    public Vec3 hitTo() {
        return this.to;
    }

    @Override
    public long hitGameTime() {
        return RoundDamageSource.LIVE;
    }

    @Override
    public HitboxPart hitPart() {
        return this.part;
    }
}
