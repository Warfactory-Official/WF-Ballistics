package com.wf.wfballistics.item;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * An immutable, launch-ready missile configuration — the full {@link MissileEntity.Builder} minus the target,
 * which is supplied at fire time. Registered in {@link MissilePresetRegistry}; each preset becomes a
 * {@link MissileItem} the player can carry and launch.
 *
 * <p>Build one with {@link Builder}; only {@code id} / {@code model} / {@code warhead} are required, the rest
 * default to a plain terrain-following cruise missile.
 */
public final class MissilePreset {

    private final String id;
    private final String modelId;
    private final String warheadId;
    private final boolean highAltitude;
    private final double altitudeParam; // cruiseAltitude (high) or terrainClearance (terrain follow)
    private final double cruiseSpeed;
    private final double turnRate;       // <= 0 = model-size default
    private final float health;
    private final int fragmentCount;
    private final float explosionOffset;
    private final int splitDepth;

    private MissilePreset(Builder b) {
        this.id = b.id;
        this.modelId = b.modelId;
        this.warheadId = b.warheadId;
        this.highAltitude = b.highAltitude;
        this.altitudeParam = b.altitudeParam;
        this.cruiseSpeed = b.cruiseSpeed;
        this.turnRate = b.turnRate;
        this.health = b.health;
        this.fragmentCount = b.fragmentCount;
        this.explosionOffset = b.explosionOffset;
        this.splitDepth = b.splitDepth;
    }

    public String id() {
        return id;
    }

    public String modelId() {
        return modelId;
    }

    public String warheadId() {
        return warheadId;
    }

    /** Builds (but does not spawn) a live missile aimed at {@code target}. */
    public MissileEntity build(Level level, Vec3 target) {
        MissileEntity.Builder b = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .model(modelId)
                .detonation(warheadId)
                .target(target)
                .cruiseSpeed(cruiseSpeed)
                .health(health)
                .fragmentCount(fragmentCount)
                .explosionOffset(explosionOffset);
        if (highAltitude) {
            b.highAltitude(altitudeParam);
        } else {
            b.terrainFollow(altitudeParam);
        }
        if (turnRate > 0.0) {
            b.turnRate(turnRate);
        }
        if (splitDepth > 0) {
            b.splitDepth(splitDepth);
        }
        return b.build();
    }

    public static Builder builder(String id, String modelId, String warheadId) {
        return new Builder(id, modelId, warheadId);
    }

    public static final class Builder {
        private final String id;
        private final String modelId;
        private final String warheadId;
        private boolean highAltitude = false;
        private double altitudeParam = 24.0;
        private double cruiseSpeed = MissileEntity.CRUISE_SPEED;
        private double turnRate = 0.0;
        private float health = MissileEntity.DEFAULT_HEALTH;
        private int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
        private float explosionOffset = 0.0f;
        private int splitDepth = 0;

        private Builder(String id, String modelId, String warheadId) {
            this.id = id;
            this.modelId = MissileModels.exists(modelId) ? modelId : MissileModels.DEFAULT;
            this.warheadId = WarheadRegistry.exists(warheadId) ? warheadId : WarheadRegistry.defaultId();
        }

        /** Fly at a fixed altitude, ignoring terrain. */
        public Builder highAltitude(double cruiseAltitude) {
            this.highAltitude = true;
            this.altitudeParam = cruiseAltitude;
            return this;
        }

        /** Hug the ground at the given clearance (the default). */
        public Builder terrainFollow(double clearance) {
            this.highAltitude = false;
            this.altitudeParam = clearance;
            return this;
        }

        public Builder cruiseSpeed(double blocksPerTick) {
            this.cruiseSpeed = blocksPerTick;
            return this;
        }

        public Builder turnRate(double radiansPerTick) {
            this.turnRate = radiansPerTick;
            return this;
        }

        public Builder health(float health) {
            this.health = health;
            return this;
        }

        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
            return this;
        }

        /** Airburst this many blocks above the target (0 = contact). */
        public Builder explosionOffset(float offset) {
            this.explosionOffset = offset;
            return this;
        }

        /** Recursive-fragmentation generations (see the {@code recursive_frag} warhead). */
        public Builder splitDepth(int splitDepth) {
            this.splitDepth = splitDepth;
            return this;
        }

        public MissilePreset build() {
            return new MissilePreset(this);
        }
    }
}
