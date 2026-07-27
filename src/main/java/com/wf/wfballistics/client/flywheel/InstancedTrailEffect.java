package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.MissileEntity;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

public class InstancedTrailEffect implements WFFlywheelEffect {

    private static final double PUFF_SPACING = 0.6;   // target spacing between trail puffs (blocks)
    private static final int MAX_SEGMENT_PUFFS = 24;  // cap on puffs used to bridge one tick's travel (near)
    final Flame[] pool;
    private final Level level;
    private final Entity source;
    // Emitter/effect centre (missile position), refreshed each tick; used for the distance LOD.
    double cx, cy, cz;
    private int cursor = 0;
    private boolean sourceGone = false;
    // Previous emission point, so a fast mover's per-tick jump can be bridged into a continuous trail section.
    private double prevX, prevY, prevZ;
    // The emission point before that: gives a third sample so the bridge can follow a Catmull-Rom curve through
    // the last few points instead of a straight chord, rounding the sharp corners a hard turn would otherwise
    // leave as a kink in the trail (see tickEffect).
    private double prev2X, prev2Y, prev2Z;
    private boolean hasPrevEmit = false;
    private boolean hasPrev2 = false;

    public InstancedTrailEffect(Entity source) {
        this.level = source.level();
        this.source = source;
        this.pool = new Flame[192];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Flame();
        }
        Vec3 emit = emitPoint();
        this.cx = this.prevX = this.prev2X = emit.x;
        this.cy = this.prevY = this.prev2Y = emit.y;
        this.cz = this.prevZ = this.prev2Z = emit.z;
    }

    /**
     * Where the exhaust streams from. For a missile the mesh base sits at the entity origin and the model's
     * +Y nose points along the heading, so the rear-face centre (the nozzle) is the entity position itself —
     * not the AABB centre, which drifts to a corner of the oriented box as the missile pitches. Generic
     * entities fall back to the vertical centre of their bounding box.
     */
    private Vec3 emitPoint() {
        if (source instanceof MissileEntity missile) {
            return new Vec3(missile.getX(), missile.getY(), missile.getZ());
        }
        Vec3 p = source.position();
        return new Vec3(p.x, p.y + source.getBbHeight() * 0.5, p.z);
    }

    private int exhaustTint() {
        return source instanceof MissileEntity missile
                ? missile.getExhaustColor()
                : MissileEntity.DEFAULT_EXHAUST_COLOR;
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
        }

        if (!sourceGone) {
            Vec3 emit = emitPoint();
            double ex = emit.x;
            double ey = emit.y;
            double ez = emit.z;
            cx = ex;
            cy = ey;
            cz = ez;
            Vec3 motion = source.getDeltaMovement();
            int tint = exhaustTint();

            // Bridge the distance travelled since the last emission with a run of puffs, so even a fast
            // (supersonic) missile that jumps many blocks per tick leaves a continuous trail section rather
            // than spaced-out dots. The whole section shares one scale and lifetime, so it reads as a cohesive
            // tube and its instances differ only by position (letting the visual reuse the billboard matrix).
            double segX = ex - prevX;
            double segY = ey - prevY;
            double segZ = ez - prevZ;
            double segLen = hasPrevEmit ? Math.sqrt(segX * segX + segY * segY + segZ * segZ) : 0.0;

            int count = Mth.clamp((int) Math.ceil(segLen / PUFF_SPACING), 1, segmentBudget());
            float sectionScale = 0.6F + level.random.nextFloat() * 0.4F;
            int sectionLife = 30 + level.random.nextInt(20);

            // Lay the puffs along a Catmull-Rom curve through the last three emit points (prev2 -> prev ->
            // current) instead of the straight prev->current chord, so a sharp heading change rounds into a
            // visible arc rather than a hard kink in the trail. The fourth control point is extrapolated
            // straight ahead since the missile's next position isn't known yet. Falls back to the chord until
            // enough history exists. "Not extremely accurate" by design — it just reads the flight's curvature.
            boolean curve = hasPrev2;
            double p3x = ex + (ex - prevX);
            double p3y = ey + (ey - prevY);
            double p3z = ez + (ez - prevZ);
            for (int k = 0; k < count; k++) {
                double t = hasPrevEmit ? (double) (k + 1) / count : 1.0;
                double sx;
                double sy;
                double sz;
                if (curve) {
                    sx = catmullRom(prev2X, prevX, ex, p3x, t);
                    sy = catmullRom(prev2Y, prevY, ey, p3y, t);
                    sz = catmullRom(prev2Z, prevZ, ez, p3z, t);
                } else {
                    sx = prevX + segX * t;
                    sy = prevY + segY * t;
                    sz = prevZ + segZ * t;
                }
                pool[cursor].spawn(level.random, sx, sy, sz, motion, sectionScale, sectionLife, tint);
                cursor = (cursor + 1) % pool.length;
            }

            prev2X = prevX;
            prev2Y = prevY;
            prev2Z = prevZ;
            prevX = ex;
            prevY = ey;
            prevZ = ez;
            hasPrev2 = hasPrevEmit;
            hasPrevEmit = true;
        }

        for (Flame flame : pool) {
            if (flame.active) flame.tick();
        }
    }

    // Per-tick cap on how many puffs may bridge one movement segment, scaled down with distance from the
    // camera: nearby trails get a dense, gap-free section; distant ones progressively fewer, since the gaps
    // are sub-pixel out there. Keeps a fixed cost ceiling regardless of missile speed.
    private int segmentBudget() {
        // Fabulous: keep the trail at full density (see BillboardLod for the rationale).
        if (Minecraft.useShaderTransparency()) {
            return MAX_SEGMENT_PUFFS;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return MAX_SEGMENT_PUFFS;
        }
        double dx = cx - player.getX();
        double dy = cy - player.getY();
        double dz = cz - player.getZ();
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 64.0 * 64.0) {
            return MAX_SEGMENT_PUFFS;
        }
        if (d2 < 160.0 * 160.0) {
            return 8;
        }
        if (d2 < 320.0 * 320.0) {
            return 3;
        }
        return 1;
    }

    /**
     * One axis of a uniform Catmull-Rom spline (tension 0.5) through {@code p1}->{@code p2}, shaped by the
     * neighbours {@code p0} and {@code p3}. Passes through p1 at t=0 and p2 at t=1, bending toward the incoming
     * and outgoing directions so a cornered path reads as a smooth curve.
     */
    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    @Override
    public boolean isExpired() {
        if (!sourceGone) return false;
        for (Flame flame : pool) {
            if (flame.active) return false;
        }
        return true;
    }

    static final class Flame {
        boolean active;
        double x, y, z, px, py, pz, vx, vy, vz;
        int age, life;
        float baseScale;
        // Hot RGB (0xRRGGBB) this puff fades from; captured at spawn so a missile can change tint mid-flight.
        int tint;

        void spawn(RandomSource r, double x, double y, double z, Vec3 motion, float baseScale, int life, int tint) {
            this.active = true;
            this.age = 0;
            this.life = life;
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            // Push backwards relative to the source so the trail streams out behind it.
            this.vx = -motion.x * 0.4 + r.nextGaussian() * 0.02;
            this.vy = -motion.y * 0.4 + r.nextGaussian() * 0.02;
            this.vz = -motion.z * 0.4 + r.nextGaussian() * 0.02;
            this.baseScale = baseScale;
            this.tint = tint;
        }

        void tick() {
            px = x;
            py = y;
            pz = z;
            vx *= 0.9;
            vy *= 0.9;
            vz *= 0.9;
            x += vx;
            y += vy;
            z += vz;
            if (++age >= life) active = false;
        }

        double ix(float pt) {
            return px + (x - px) * pt;
        }

        double iy(float pt) {
            return py + (y - py) * pt;
        }

        double iz(float pt) {
            return pz + (z - pz) * pt;
        }

        float scale(float pt) {
            return baseScale * (0.5F + (age + pt) / life * 1.5F);
        }

        int argb(float pt) {
            float a = (age + pt) / life;
            // Fade the configured hot tint toward a dim ember (10% brightness) as the puff cools, then out.
            float f = 0.1F + 0.9F * (1F - Math.min(a / 0.6F, 1F));
            int rr = (int) (Mth.clamp(((tint >> 16) & 0xFF) / 255F * f, 0F, 1F) * 255F);
            int gg = (int) (Mth.clamp(((tint >> 8) & 0xFF) / 255F * f, 0F, 1F) * 255F);
            int bb = (int) (Mth.clamp((tint & 0xFF) / 255F * f, 0F, 1F) * 255F);
            int alpha = (int) (Mth.clamp((float) Math.pow(1 - Math.min(a, 1F), 0.4), 0F, 1F) * 0.75F * 255F);
            return (alpha << 24) | (rr << 16) | (gg << 8) | bb;
        }
    }
}
