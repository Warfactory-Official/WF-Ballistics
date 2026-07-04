package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.entity.FireLingeringEntity;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;

public class InstancedFlameEffect implements WFFlywheelEffect {

    private static final int MAX_EMIT = 24;
    private static final int MAX_LIFE = 16;

    final Flame[] pool;
    private final Level level;
    private final FireLingeringEntity source;
    double cx, cy, cz;
    private int cursor = 0;
    private boolean sourceGone = false;
    private double minX, minZ, baseY, spanX, spanZ;

    public InstancedFlameEffect(FireLingeringEntity source) {
        this.level = source.level();
        this.source = source;
        refreshFootprint();
        this.pool = new Flame[MAX_EMIT * MAX_LIFE];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Flame();
        }
    }

    private void refreshFootprint() {
        AABB box = source.getBoundingBox();
        minX = box.minX;
        minZ = box.minZ;
        baseY = box.minY;
        spanX = box.maxX - box.minX;
        spanZ = box.maxZ - box.minZ;
        cx = (box.minX + box.maxX) * 0.5;
        cy = box.minY;
        cz = (box.minZ + box.maxZ) * 0.5;
    }

    private int emitPerTick() {
        int area = Math.max(1, (int) (spanX * spanZ));
        return Mth.clamp((int) (area * 0.75), 2, MAX_EMIT);
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new InstancedFlameVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        if (source.isRemoved() || !source.isAlive()) {
            sourceGone = true;
        }
        if (!sourceGone) {
            refreshFootprint();
            int emit = emitPerTick();
            for (int k = 0; k < emit; k++) {
                double px = minX + level.random.nextDouble() * spanX;
                double pz = minZ + level.random.nextDouble() * spanZ;
                double py = baseY + level.random.nextDouble() * 0.3;
                pool[cursor].spawn(level.random, px, py, pz);
                cursor = (cursor + 1) % pool.length;
            }
        }
        for (Flame flame : pool) {
            if (flame.active) {
                flame.tick();
            }
        }
    }

    @Override
    public boolean isExpired() {
        if (!sourceGone) {
            return false;
        }
        for (Flame flame : pool) {
            if (flame.active) {
                return false;
            }
        }
        return true;
    }

    static final class Flame {
        boolean active;
        double x, y, z, px, py, pz, vx, vy, vz;
        int age, life;
        float baseScale;

        void spawn(RandomSource r, double x, double y, double z) {
            this.active = true;
            this.age = 0;
            this.life = 8 + r.nextInt(8);
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.vx = (r.nextDouble() - 0.5) * 0.02;
            this.vy = 0.02 + r.nextDouble() * 0.03;
            this.vz = (r.nextDouble() - 0.5) * 0.02;
            this.baseScale = 0.4F + r.nextFloat() * 0.3F;
        }

        void tick() {
            px = x;
            py = y;
            pz = z;
            vy += 0.002;
            vx *= 0.9;
            vz *= 0.9;
            x += vx;
            y += vy;
            z += vz;
            if (++age >= life) {
                active = false;
            }
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
            return baseScale;
        }

        int argb(float pt) {
            float f = Math.min((age + pt) / life, 1F);
            int rr = (int) (Mth.clamp(1F - f * 0.3F, 0F, 1F) * 255F);
            int gg = (int) (Mth.clamp(0.6F * (1F - f) + 0.1F, 0F, 1F) * 255F);
            int bb = (int) (0.05F * 255F);
            int alpha = (int) (Mth.clamp((float) Math.pow(1F - f, 0.5), 0F, 1F) * 0.85F * 255F);
            return (alpha << 24) | (rr << 16) | (gg << 8) | bb;
        }
    }
}
