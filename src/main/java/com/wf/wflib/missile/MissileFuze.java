package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileModels;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.damage.DamageClass;
import com.wf.wflib.damage.WFDamageSources;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/** Warhead, arming, fuze: what happens where the missile ends. */
public final class MissileFuze {

    private static final double ARMING_DISTANCE_SQ = MissileEntity.ARMING_DISTANCE * MissileEntity.ARMING_DISTANCE;
    /** Armed regardless of distance after this many ticks (a launch that never clears its launcher). */
    private static final int ARMING_FAILSAFE_TICKS = 100;

    private final MissileEntity missile;

    ResourceLocation detonationId = WarheadRegistry.defaultId();
    WarheadRegistry.Detonation detonation = WarheadRegistry.STANDARD;
    int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
    int impactPreloadRadius = MissileEntity.DEFAULT_IMPACT_PRELOAD_RADIUS;
    /** Airburst height above the target; 0 = contact. */
    float explosionOffset;
    boolean armed;
    /** Contact fuze failed once: never strikes again. */
    boolean fuseFailed;
    private boolean detonated;
    /** Goes off within this of any hittable entity; 0 = contact only. */
    float proximityRadius;
    /** Direct-hit damage to the struck entity, before the warhead; 0 = blast only. */
    float impactDamage;
    /** null = HEAT for a shaped charge, else HE. */
    @Nullable
    ThreatKind threatKind;
    /** 0 = airframe diameter. */
    float caliber;
    /** mm RHA. */
    float penetration;
    /** 0 = the warhead's authored size. */
    float blastSize;
    boolean breaksBlocks = true;

    public MissileFuze(MissileEntity missile) {
        this.missile = missile;
    }

    void setDetonation(ResourceLocation id) {
        this.detonationId = id;
        this.detonation = WarheadRegistry.get(id);
    }

    /** Live once clear of the launcher (or after the failsafe). */
    public boolean isArmed() {
        if (this.armed) {
            return true;
        }
        Vec3 launch = this.missile.flight().launchPos();
        if (launch != null && this.missile.position().distanceToSqr(launch) >= ARMING_DISTANCE_SQ
                || this.missile.tickCount >= ARMING_FAILSAFE_TICKS) {
            this.armed = true;
        }
        return this.armed;
    }

    public boolean detonated() {
        return this.detonated;
    }

    public boolean fuseFailed() {
        return this.fuseFailed;
    }

    /** Airburst: in ATTACK within {@link #explosionOffset} above the aim point. */
    boolean airburstDue(Vec3 target) {
        return this.explosionOffset > 0.0f && this.missile.flight().getPhase() == MissileEntity.Phase.ATTACK
                && this.missile.getY() - target.y <= this.explosionOffset;
    }

    /** Any hittable entity within {@link #proximityRadius}. */
    boolean proximityDue(ServerLevel level) {
        if (this.proximityRadius <= 0.0f) {
            return false;
        }
        double r2 = (double) this.proximityRadius * this.proximityRadius;
        Vec3 at = this.missile.position();
        return !level.getEntities(this.missile, this.missile.getBoundingBox().inflate(this.proximityRadius),
                e -> this.missile.isHitCandidate(e) && e.getBoundingBox().distanceToSqr(at) <= r2).isEmpty();
    }

    void impact(HitResult hit) {
        this.missile.recordEvent(WFEventType.IMPACTED, hit.getType().toString());
        if (hit instanceof EntityHitResult ehr && ehr.getEntity() instanceof MissileEntity other) {
            Vec3 pos = hit.getLocation();
            this.detonate(pos, true);
            other.fuze().detonate(pos, true);
            return;
        }
        Vec3 at = hit.getLocation();
        if (hit instanceof EntityHitResult ehr) {
            ProjectileStrikeEvent strike = NeoForge.EVENT_BUS.post(new ProjectileStrikeEvent(this.missile,
                    ehr.getEntity(), at, this.missile.getDeltaMovement(), this.threat()));
            switch (strike.outcome()) {
                case PASS -> {
                    return;
                }
                case DUD -> {
                    this.missile.damage().dropAsDud();
                    return;
                }
                case PROCEED -> {
                    if (strike.detonation() != null) {
                        at = strike.detonation();
                    }
                }
            }
            if (this.impactDamage > 0.0f) {
                ehr.getEntity().hurt(WFDamageSources.create(this.missile.level(), DamageClass.PHYSICAL,
                        this.missile.getOwner(), this.missile), this.impactDamage);
            }
        }
        this.detonate(at, false);
    }

    public Threat threat() {
        ThreatKind kind = this.threatKind != null ? this.threatKind
                : WarheadRegistry.rl("shaped_charge").equals(this.detonationId) ? ThreatKind.HEAT : ThreatKind.HE;
        float caliber = this.caliber > 0.0f ? this.caliber
                : (float) MissileModels.dimensions(this.missile.getModelId()).x * 1000.0f;
        return new Threat(kind, caliber, this.penetration);
    }

    public float getBlastSize() {
        return this.blastSize;
    }

    public boolean breaksBlocks() {
        return this.breaksBlocks;
    }

    /**
     * Remove the missile, running the full warhead or, {@code intercepted}, the (typically neutralised)
     * {@link WarheadRegistry#getIntercept intercept effect}.
     */
    public void detonate(Vec3 pos, boolean intercepted) {
        if (this.detonated) {
            return;
        }
        this.detonated = true; // before the blast: it can hurt() this missile before discard()
        this.missile.recordEvent(intercepted ? WFEventType.INTERCEPTED : WFEventType.DETONATED, "");
        if (intercepted) {
            WarheadRegistry.getIntercept(this.detonationId).detonate(this.missile, pos);
        } else {
            this.detonation.detonate(this.missile, pos);
        }
        if (this.missile.level() instanceof ServerLevel sl) {
            if (!intercepted) {
                DetonationChunkGuard.hold(sl, pos, Math.max(1, this.impactPreloadRadius));
            }
            this.missile.chunkLoader().releaseAll(this.missile, sl);
        }
        this.missile.discard();
    }

    public ResourceLocation getDetonationId() {
        return this.detonationId;
    }

    public int getFragmentCount() {
        return this.fragmentCount;
    }

    public int getImpactPreloadRadius() {
        return this.impactPreloadRadius;
    }

    public float getExplosionOffset() {
        return this.explosionOffset;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("DetonationId", this.detonationId.toString());
        tag.putInt("FragmentCount", this.fragmentCount);
        tag.putInt("ImpactPreloadRadius", this.impactPreloadRadius);
        tag.putFloat("ExplosionOffset", this.explosionOffset);
        tag.putBoolean("Armed", this.armed);
        tag.putBoolean("FuseFailed", this.fuseFailed);
        tag.putFloat("Proximity", this.proximityRadius);
        tag.putFloat("ImpactDamage", this.impactDamage);
        if (this.threatKind != null) {
            tag.putString("ThreatKind", this.threatKind.name());
        }
        tag.putFloat("Caliber", this.caliber);
        tag.putFloat("Penetration", this.penetration);
        tag.putFloat("BlastSize", this.blastSize);
        tag.putBoolean("BreaksBlocks", this.breaksBlocks);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.setDetonation(WarheadRegistry.parse(tag.getString("DetonationId")));
        this.fragmentCount = tag.getInt("FragmentCount");
        this.impactPreloadRadius = tag.getInt("ImpactPreloadRadius");
        this.explosionOffset = tag.getFloat("ExplosionOffset");
        this.armed = tag.getBoolean("Armed");
        this.fuseFailed = tag.getBoolean("FuseFailed");
        this.proximityRadius = tag.getFloat("Proximity");
        this.impactDamage = tag.getFloat("ImpactDamage");
        this.threatKind = tag.contains("ThreatKind") ? ThreatKind.valueOf(tag.getString("ThreatKind")) : null;
        this.caliber = tag.getFloat("Caliber");
        this.penetration = tag.getFloat("Penetration");
        this.blastSize = tag.getFloat("BlastSize");
        this.breaksBlocks = tag.getBoolean("BreaksBlocks");
    }
}
