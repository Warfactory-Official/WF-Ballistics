package com.wf.wfballistics.client.flywheel;

import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

public class InstancedTrailEffect implements WFFlywheelEffect {

    private static final int EMIT_PER_TICK = 3;

    private final Level level;
    private final Entity source;
    final Flame[] pool;
    private int cursor = 0;
    private boolean sourceGone = false;

    public InstancedTrailEffect(Entity source) {
        this.level = source.level();
        this.source = source;
        this.pool = new Flame[192];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Flame();
        }
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
            Vec3 pos = source.position();
            Vec3 motion = source.getDeltaMovement();
            for (int i = 0; i < EMIT_PER_TICK; i++) {
                pool[cursor].spawn(level.random, pos.x, pos.y + source.getBbHeight() * 0.5, pos.z, motion);
                cursor = (cursor + 1) % pool.length;
            }
        }

        for (Flame flame : pool) {
            if (flame.active) flame.tick();
        }
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

        void spawn(RandomSource r, double x, double y, double z, Vec3 motion) {
            this.active = true;
            this.age = 0;
            this.life = 30 + r.nextInt(20);
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            // Push backwards relative to the source so the trail streams out behind it.
            this.vx = -motion.x * 0.4 + r.nextGaussian() * 0.02;
            this.vy = -motion.y * 0.4 + r.nextGaussian() * 0.02;
            this.vz = -motion.z * 0.4 + r.nextGaussian() * 0.02;
            this.baseScale = 0.6F + r.nextFloat() * 0.4F;
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

        double ix(float pt) { return px + (x - px) * pt; }

        double iy(float pt) { return py + (y - py) * pt; }

        double iz(float pt) { return pz + (z - pz) * pt; }

        float scale(float pt) {
            return baseScale * (0.5F + (age + pt) / life * 1.5F);
        }

        int argb(float pt) {
            float a = (age + pt) / life;
            float dark = 1F - Math.min(a / 0.6F, 1F);
            int rr = (int) (Mth.clamp(dark + 0.1F, 0F, 1F) * 255F);
            int gg = (int) (Mth.clamp(0.6F * dark + 0.1F, 0F, 1F) * 255F);
            int bb = (int) (0.05F * 255F);
            int alpha = (int) (Mth.clamp((float) Math.pow(1 - Math.min(a, 1F), 0.4), 0F, 1F) * 0.75F * 255F);
            return (alpha << 24) | (rr << 16) | (gg << 8) | bb;
        }
    }
}
