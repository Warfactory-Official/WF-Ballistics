package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticleInstance;
import com.wf.wflib.MissileEntity;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

/** A missile's exhaust trail, rendered through GemRender's particle instancer. */
public class InstancedTrailEffect implements WFFlywheelEffect {

    /** Target spacing between trail puffs, in blocks, and the number the plume's density is really set by. */
    private static final double PUFF_SPACING = 0.25;

    /** Cap on puffs used to bridge one tick's travel. */
    private static final int MAX_SEGMENT_PUFFS = 96;

    /** Ring capacity. */
    private static final int POOL = 4096;

    /** How long a puff of smoke hangs in the air. */
    private static final float LIFE_SECONDS = 7.4F;

    /** Per-puff lifetime spread, as a fraction. */
    private static final float LIFE_JITTER = 0.06F;

    /** Per-puff size spread, for the same reason and kept just as tight. */
    private static final float SCALE_JITTER = 0.09F;

    /** What fraction of the missile's own speed the gas leaves the nozzle with, backwards. */
    private static final double JET_FRACTION = 0.4;

    /** And the ceiling on it, in blocks per tick: the fix for everything that was wrong at speed. */
    private static final double JET_MAX_PER_TICK = 0.6;

    /** Blocks per tick of random scatter on the jet, which gives the plume its ragged edge. */
    private static final double JET_SCATTER = 0.09;
    /** And the wake's, which is larger relative to everything else about it. */
    private static final double WAKE_SCATTER = 0.12;

    /** What the missile leaves behind, which is decided by what it is travelling through. */
    public enum Kind {
        EXHAUST(WFParticleTextures.EXHAUST_VERTEX, WFParticleTextures.EXHAUST_CULL,
                PUFF_SPACING, LIFE_SECONDS, JET_FRACTION, JET_MAX_PER_TICK, JET_SCATTER) {
            @Override
            int style(int tint) {
                return WFParticleStyles.trail(tint);
            }
        },
        WAKE(WFParticleTextures.WAKE_VERTEX, WFParticleTextures.BILLBOARD_CULL,
                0.18, 3.0F, 0.3, 0.2, WAKE_SCATTER) {
            @Override
            int style(int tint) {
                return WFParticleStyles.wake(); // the sea is the same colour for everything in it
            }

            @Override
            boolean lays(Entity source) {
                return source instanceof MissileEntity missile && missile.isSubmerged();
            }
        };

        /** One instance type per kind, built once and shared by every missile of that kind. */
        private final InstanceType<ParticleInstance> instanceType;
        private final double spacing;
        private final float lifeSeconds;
        private final double jetFraction;
        private final double jetMaxPerTick;
        private final double scatter;

        Kind(net.minecraft.resources.ResourceLocation vertexShader,
             net.minecraft.resources.ResourceLocation cullShader,
             double spacing, float lifeSeconds, double jetFraction, double jetMaxPerTick, double scatter) {
            this.instanceType = GemRenderParticleTypes.custom(vertexShader, cullShader);
            this.spacing = spacing;
            this.lifeSeconds = lifeSeconds;
            this.jetFraction = jetFraction;
            this.jetMaxPerTick = jetMaxPerTick;
            this.scatter = scatter;
        }

        abstract int style(int tint);

        /**
         * @return whether this kind has anything to leave behind where the source currently is. An exhaust
         *      plume always does: a motor carries its own oxidiser and a rocket underwater still burns.
         */
        boolean lays(Entity source) {
            return true;
        }

        InstanceType<ParticleInstance> instanceType() {
            return this.instanceType;
        }

        /**
         * @return what a missile of this description leaves behind. Asked of the synced medium rather than of
         *      where the missile is standing: a torpedo still falling toward the sea after an air launch is in air
         *      by any block test, and giving it a rocket plume for those few ticks and a wake afterwards would mean
         *      two emitters, two pools and a seam between them.
         */
        static Kind of(Entity source) {
            return source instanceof MissileEntity missile && missile.isSubmergedMedium() ? WAKE : EXHAUST;
        }
    }

    private final Level level;
    private final Entity source;
    private final ParticleEmitter emitter;

    /** Emitter centre (missile position), refreshed each tick. */
    double cx, cy, cz;

    private boolean sourceGone = false;

    // Previous emission point, so a fast mover's per-tick jump can be bridged into a continuous trail section.
    private double prevX, prevY, prevZ;
    private double headX, headY, headZ;
    private boolean hasPrevEmit = false;
    private boolean hasHeading = false;

    private final Kind kind;

    public InstancedTrailEffect(Entity source) {
        this.level = source.level();
        this.source = source;

        Vec3 emit = emitPoint();
        this.cx = this.prevX = emit.x;
        this.cy = this.prevY = emit.y;
        this.cz = this.prevZ = emit.z;

        this.kind = Kind.of(source);
        this.emitter = ParticleEmitter.create(kind.style(exhaustTint()), POOL, emit.x, emit.y, emit.z);
    }

    /** @return which of the two trails this is. Read by {@link InstancedTrailVisual} to pick the shader. */
    Kind kind() {
        return this.kind;
    }

    /** Where the exhaust streams from. */
    private Vec3 emitPoint() {
        if (source instanceof MissileEntity missile) {
            return new Vec3(missile.xOld, missile.yOld, missile.zOld);
        }
        return new Vec3(source.xOld, source.yOld + source.getBbHeight() * 0.5, source.zOld);
    }

    private int exhaustTint() {
        return source instanceof MissileEntity missile
                ? missile.getExhaustColor()
                : MissileEntity.DEFAULT_EXHAUST_COLOR;
    }

    ParticleEmitter emitter() {
        return emitter;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new InstancedTrailVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        if (source.isRemoved() || !source.isAlive()) {
            sourceGone = true;
            return;
        }

        Vec3 emit = emitPoint();
        double ex = emit.x;
        double ey = emit.y;
        double ez = emit.z;
        cx = ex;
        cy = ey;
        cz = ez;
        double segX = ex - prevX;
        double segY = ey - prevY;
        double segZ = ez - prevZ;
        double segLen = hasPrevEmit ? Math.sqrt(segX * segX + segY * segY + segZ * segZ) : 0.0;

        int count = Mth.clamp((int) Math.ceil(segLen / kind.spacing), 1, MAX_SEGMENT_PUFFS);

        double stretch = Math.max(1.0, (segLen / count) / kind.spacing);

        double jetSpeed = Math.min(segLen * kind.jetFraction, kind.jetMaxPerTick);

        double nextHeadX = headX;
        double nextHeadY = headY;
        double nextHeadZ = headZ;
        if (segLen > 1.0e-6) {
            nextHeadX = segX / segLen;
            nextHeadY = segY / segLen;
            nextHeadZ = segZ / segLen;
        }

        double startTanX = (hasHeading ? headX : nextHeadX) * segLen;
        double startTanY = (hasHeading ? headY : nextHeadY) * segLen;
        double startTanZ = (hasHeading ? headZ : nextHeadZ) * segLen;
        double endTanX = nextHeadX * segLen;
        double endTanY = nextHeadY * segLen;
        double endTanZ = nextHeadZ * segLen;

        double lastX = prevX;
        double lastY = prevY;
        double lastZ = prevZ;

        boolean laying = kind.lays(source);

        for (int k = 0; k < count && laying; k++) {
            double t = hasPrevEmit ? (double) (k + 1) / count : 1.0;
            double sx = hermite(prevX, startTanX, ex, endTanX, t);
            double sy = hermite(prevY, startTanY, ey, endTanY, t);
            double sz = hermite(prevZ, startTanZ, ez, endTanZ, t);

            double stepX = sx - lastX;
            double stepY = sy - lastY;
            double stepZ = sz - lastZ;
            double stepLen = Math.sqrt(stepX * stepX + stepY * stepY + stepZ * stepZ);
            double jetX = 0.0;
            double jetY = 0.0;
            double jetZ = 0.0;
            if (stepLen > 1.0e-9) {
                double scale = jetSpeed / stepLen;
                jetX = -stepX * scale;
                jetY = -stepY * scale;
                jetZ = -stepZ * scale;
            }
            lastX = sx;
            lastY = sy;
            lastZ = sz;

            float life = kind.lifeSeconds * (1F - LIFE_JITTER + level.random.nextFloat() * 2F * LIFE_JITTER);
            float scale = (float) (stretch * (1F - SCALE_JITTER + level.random.nextFloat() * 2F * SCALE_JITTER));

            emitter.spawn(sx, sy, sz,
                    (jetX + level.random.nextGaussian() * kind.scatter) * 20.0,
                    (jetY + level.random.nextGaussian() * kind.scatter) * 20.0,
                    (jetZ + level.random.nextGaussian() * kind.scatter) * 20.0,
                    life, scale,
                    level.random.nextFloat() * Mth.TWO_PI, 1.0F);
        }

        prevX = ex;
        prevY = ey;
        prevZ = ez;
        headX = nextHeadX;
        headY = nextHeadY;
        headZ = nextHeadZ;
        hasHeading = hasHeading || segLen > 1.0e-6;
        hasPrevEmit = true;
    }

    /**
     * One axis of a cubic Hermite from {@code p0} to {@code p1}, leaving along {@code m0} and arriving along {@code
     * m1}.
     */
    private static double hermite(double p0, double m0, double p1, double m1, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return (2.0 * t3 - 3.0 * t2 + 1.0) * p0
                + (t3 - 2.0 * t2 + t) * m0
                + (-2.0 * t3 + 3.0 * t2) * p1
                + (t3 - t2) * m1;
    }

    @Override
    public boolean isExpired() {
        return sourceGone && emitter.isIdle();
    }

    @Override
    public void disposeEffect() {
        emitter.close();
    }
}
