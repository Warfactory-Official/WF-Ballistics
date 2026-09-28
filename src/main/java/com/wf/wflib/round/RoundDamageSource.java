package com.wf.wflib.round;

import com.norwood.ahf.hit.AhfHitSource;
import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.api.StrikeContext;
import com.wf.wflib.api.Threat;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Direct hit by a round or rocket; the strike rides on the source. AHF reads the swept segment
 * {@code position() -> segmentEnd} at {@link #hitGameTime}, never the shooter's eye.
 */
public final class RoundDamageSource extends DamageSource implements StrikeContext, AhfHitSource {

    /** {@link #hitGameTime} of an unrewound strike: no history entry => AHF classifies live. */
    public static final long LIVE = Long.MIN_VALUE;

    private final Threat threat;
    private final Vec3 position;
    private final Vec3 velocity;
    private final Object key;
    private final Vec3 segmentEnd;
    private final long gameTime;
    @Nullable
    private final List<HitboxPart> parts;
    private final float pierceDT;
    private final float pierceDR;

    RoundDamageSource(Holder<DamageType> type, @Nullable Entity direct, @Nullable Entity shooter, Threat threat,
                      Vec3 position, Vec3 velocity, Object key, Vec3 segmentEnd, long gameTime,
                      @Nullable List<HitboxPart> parts, float pierceDT, float pierceDR) {
        super(type, direct, shooter, position);
        this.threat = threat;
        this.position = position;
        this.velocity = velocity;
        this.key = key;
        this.segmentEnd = segmentEnd;
        this.gameTime = gameTime;
        this.parts = parts;
        this.pierceDT = pierceDT;
        this.pierceDR = pierceDR;
    }

    /** Preset pierce; armour reads it here, never from the thread context (nested damage must not inherit it). */
    public float pierceDT() {
        return this.pierceDT;
    }

    public float pierceDR() {
        return this.pierceDR;
    }

    @Override
    public Threat threat() {
        return this.threat;
    }

    @Override
    public Vec3 position() {
        return this.position;
    }

    @Override
    public Vec3 velocity() {
        return this.velocity;
    }

    @Override
    public Object key() {
        return this.key;
    }

    @Nullable
    @Override
    public Entity shooter() {
        return this.getEntity();
    }

    @Nullable
    @Override
    public List<HitboxPart> parts() {
        return this.parts;
    }

    @Override
    public Vec3 hitFrom() {
        return this.position;
    }

    @Override
    public Vec3 hitTo() {
        return this.segmentEnd;
    }

    @Override
    public long hitGameTime() {
        return this.gameTime;
    }

    /** First part; AHF's pierced list then = this one part => pierced readers use {@link #parts}. */
    @Nullable
    @Override
    public HitboxPart hitPart() {
        return this.parts == null ? null : this.parts.get(0);
    }
}
