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
    // Emitter/effect centre (missile position), refreshed each tick; used for the distance LOD.
    double cx, cy, cz;
    private final Level level;
    private final Entity source;
    private int cursor = 0;
    private boolean sourceGone = false;
    // Previous emission point, so a fast mover's per-tick jump can be bridged into a continuous trail section.
    private double prevX, prevY, prevZ;
    private boolean hasPrevEmit = false;

    public InstancedTrailEffect(Entity source) {
        this.level = source.level();
        this.source = source;
        this.pool = new Flame[192];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Flame();
        }
        Vec3 emit = emitPoint();
        this.cx = this.prevX = emit.x;
        this.cy = this.prevY = emit.y;
        this.cz = this.prevZ = emit.z;
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

            for (int k = 0; k < count; k++) {
                double t = hasPrevEmit ? (double) (k + 1) / count : 1.0;
                pool[cursor].spawn(level.random, prevX + segX * t, prevY + segY * t, prevZ + segZ * t,
                        motion, sectionScale, sectionLife, tint);
                cursor = (cursor + 1) % pool.length;
            }

            prevX = ex;
            prevY = ey;
            prevZ = ez;
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
