package com.wf.wfballistics.client.flywheel;

import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The pile of bones an entity collapses into when cremated, rendered as Flywheel instances of the
 * {@code bone_*} models (skull / torso / limbs) instead of a baked VBO. The CPU simulates each bone falling
 * and tumbling to the ground where it settles and fades; the matching {@link SkeletonBoneVisual} draws them
 * as instanced meshes.
 *
 * <p>Built from a generic biped layout ({@link #biped}) scaled to the dead entity's height — so it diverges
 * from HBM's per-mob bone tables, but works for any humanoid-ish entity without those mappings.
 */
public class SkeletonBoneEffect implements WFFlywheelEffect {

    final List<Bone> bones;
    final float brightness;
    private final Level level;
    private boolean playedClatter = false;
    public SkeletonBoneEffect(Level level, List<Bone> bones, float brightness) {
        this.level = level;
        this.bones = bones;
        this.brightness = brightness;
    }

    /**
     * Generic upright-biped skeleton at {@code (x,y,z)}, sized to {@code height} (1.8 ≈ a player).
     */
    public static SkeletonBoneEffect biped(Level level, double x, double y, double z,
                                           float bodyYaw, float headYaw, float height, float brightness) {
        float h = Math.max(0.25F, height / 1.8F);
        double bodyRad = Math.toRadians(-bodyYaw);
        Vec3 arm = new Vec3(0.375 * h, 0, 0).yRot((float) bodyRad);
        Vec3 leg = new Vec3(0.125 * h, 0, 0).yRot((float) bodyRad);

        List<Bone> bones = new ArrayList<>(6);
        bones.add(new Bone(level, BonePart.SKULL, x, y + 1.75 * h, z, -headYaw, h));
        bones.add(new Bone(level, BonePart.TORSO, x, y + 1.125 * h, z, -bodyYaw, h));
        bones.add(new Bone(level, BonePart.LIMB, x + arm.x, y + 1.125 * h, z + arm.z, -bodyYaw, h));
        bones.add(new Bone(level, BonePart.LIMB, x - arm.x, y + 1.125 * h, z - arm.z, -bodyYaw, h));
        bones.add(new Bone(level, BonePart.LIMB, x + leg.x, y + 0.375 * h, z + leg.z, -bodyYaw, h));
        bones.add(new Bone(level, BonePart.LIMB, x - leg.x, y + 0.375 * h, z - leg.z, -bodyYaw, h));
        return new SkeletonBoneEffect(level, bones, brightness);
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new SkeletonBoneVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        boolean anyLandedThisTick = false;
        for (Bone bone : bones) {
            if (bone.tick(level)) anyLandedThisTick = true;
        }
        if (anyLandedThisTick && !playedClatter) {
            playedClatter = true;
            Bone b = bones.get(0);
            level.playLocalSound(b.x, b.y, b.z, SoundEvents.SKELETON_HURT, SoundSource.NEUTRAL, 0.25F, 0.8F, false);
        }
    }

    @Override
    public boolean isExpired() {
        for (Bone bone : bones) {
            if (bone.age < bone.life) return false;
        }
        return true;
    }

    public enum BonePart {SKULL, TORSO, LIMB}

    /**
     * One bone: position, fall/settle state, and a free-axis tumble while airborne.
     */
    static final class Bone {
        final BonePart part;
        final float scale;
        final float yaw;
        final float tax, tay, taz; // tumble axis (unit)
        double x, y, z, px, py, pz, vx, vy, vz;
        float tumble, ptumble, tumbleVel;
        int age, life;
        boolean onGround;

        Bone(Level level, BonePart part, double x, double y, double z, float yaw, float scale) {
            this.part = part;
            this.scale = scale;
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.yaw = yaw;
            this.life = 300 + level.random.nextInt(40);
            this.vx = level.random.nextGaussian() * 0.05;
            this.vy = level.random.nextDouble() * 0.05;
            this.vz = level.random.nextGaussian() * 0.05;
            float ax = (float) level.random.nextGaussian();
            float ay = (float) level.random.nextGaussian();
            float az = (float) level.random.nextGaussian();
            float len = (float) Math.sqrt(ax * ax + ay * ay + az * az) + 1e-5F;
            this.tax = ax / len;
            this.tay = ay / len;
            this.taz = az / len;
            this.tumbleVel = (level.random.nextFloat() - 0.5F) * 0.6F;
        }

        /**
         * @return true if the bone landed this tick
         */
        boolean tick(Level level) {
            px = x;
            py = y;
            pz = z;
            ptumble = tumble;
            age++;

            if (onGround) return false;

            BlockPos below = BlockPos.containing(x, y - 0.1, z);
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                onGround = true;
                vx = vy = vz = 0;
                return true;
            }

            vy -= 0.04;
            vx *= 0.98;
            vy *= 0.98;
            vz *= 0.98;
            x += vx;
            y += vy;
            z += vz;
            tumble += tumbleVel;
            return false;
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

        float itumble(float pt) {
            return ptumble + (tumble - ptumble) * pt;
        }

        float alpha(float pt) {
            float left = life - (age + pt);
            return left < 40 ? Math.max(0F, left / 40F) : 1F;
        }

        boolean dead() {
            return age >= life;
        }
    }
}
