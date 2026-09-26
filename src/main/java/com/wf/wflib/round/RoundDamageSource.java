package com.wf.wflib.round;

import com.wf.wflib.api.StrikeContext;
import com.wf.wflib.api.Threat;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Direct hit by a round or rocket; the strike rides on the source. */
public final class RoundDamageSource extends DamageSource implements StrikeContext {

    private final Threat threat;
    private final Vec3 position;
    private final Vec3 velocity;
    private final Object key;

    RoundDamageSource(Holder<DamageType> type, @Nullable Entity direct, @Nullable Entity shooter, Threat threat,
                      Vec3 position, Vec3 velocity, Object key) {
        super(type, direct, shooter, position);
        this.threat = threat;
        this.position = position;
        this.velocity = velocity;
        this.key = key;
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
}
