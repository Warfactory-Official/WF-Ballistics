package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.entity.FireLingeringEntity;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class InstancedFlameEffect implements WFFlywheelEffect {

    private static final float FLAMES_PER_BLOCK = 2.0F;
    private static final int MAX_EMIT = 256;
    private static final int MAX_LIFE = 16;
    private static final double FULL_DENSITY_SQ = 64.0 * 64.0;
    private static final double MIN_DENSITY = 0.15;

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
        this.pool = new Flame[emitPerTick() * MAX_LIFE];
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
        return flameCount(spanX, spanZ);
    }

    public static int flameCount(double spanX, double spanZ) {
        double area = Math.max(1.0, spanX * spanZ);
        return Mth.clamp((int) Math.ceil(area * FLAMES_PER_BLOCK), 2, MAX_EMIT);
    }

    private int scaledEmit() {
        int emit = emitPerTick();
        double distSq = cameraDistSq();
        if (distSq <= FULL_DENSITY_SQ) {
            return emit;
        }
        double factor = Math.max(MIN_DENSITY, Math.sqrt(FULL_DENSITY_SQ / distSq));
        return Math.max(1, (int) Math.ceil(emit * factor));
    }

    private double cameraDistSq() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 p = camera.getPosition();
        double dx = cx - p.x;
        double dy = cy - p.y;
        double dz = cz - p.z;
        return dx * dx + dy * dy + dz * dz;
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
            int emit = scaledEmit();
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
            float r = Mth.clamp(1F - f * 0.3F, 0F, 1F);
            float g = Mth.clamp(0.6F * (1F - f) + 0.1F, 0F, 1F);
            float b = 0.05F;
            float w = Mth.clamp((f - 0.55F) / 0.45F, 0F, 1F);
            r += (1F - r) * w;
            g += (1F - g) * w;
            b += (1F - b) * w;
            int rr = (int) (r * 255F);
            int gg = (int) (g * 255F);
            int bb = (int) (b * 255F);
            int alpha = (int) (Mth.clamp((float) Math.pow(1F - f, 0.5), 0F, 1F) * 0.85F * 255F);
            return (alpha << 24) | (rr << 16) | (gg << 8) | bb;
        }
    }
}
