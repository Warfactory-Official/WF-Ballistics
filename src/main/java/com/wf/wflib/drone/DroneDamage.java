package com.wf.wflib.drone;

import com.wf.wflib.anim.Rotor;
import com.wf.wflib.anim.Rotors;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.ai.TerrainSampler;
import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/** Health, lost rotor discs, and the way down: shot down, broken up, crashed. */
public final class DroneDamage {

    public static final float DEFAULT_HEALTH = 20.0f;
    /** A hit worth at least this much takes a rotor disc off. */
    public static final float ROTOR_LOSS_DAMAGE = 4.0f;
    /** How far past dead a hit has to carry to leave no airframe at all. */
    public static final float OVERKILL_HEALTH = 15.0f;
    /** Blast size when a wreck hits the ground. */
    public static final float CRASH_BLAST = 1.4f;
    /** How far off level a wreck can come to rest, radians. */
    private static final double CRASH_TILT = Math.toRadians(35.0);
    /** Height above the ground at which a falling wreck counts as having arrived. */
    private static final double CRASH_CONTACT = 0.4;

    private final DroneEntity drone;
    private float health = DEFAULT_HEALTH;
    // True once a wreck has hit the ground and been laid out where it stopped: see crash().
    private boolean crashed;

    DroneDamage(DroneEntity drone) {
        this.drone = drone;
    }

    /** A falling wreck close enough to the ground crashes. */
    void tick(ServerLevel level) {
        if (this.isDowned() && !this.crashed) {
            double ground = TerrainSampler.measure(level, this.drone.getX(), this.drone.getZ());
            if (!Double.isNaN(ground) && this.drone.getY() - ground <= CRASH_CONTACT) {
                this.crash(level);
            }
        }
    }

    public boolean crashed() {
        return this.crashed;
    }

    public float getHealth() {
        return this.health;
    }

    public boolean isDowned() {
        return this.drone.flight().getDroneState() == DroneState.DOWNED;
    }

    /** Shot down: cut the power and let it spin in. */
    public void shootDown() {
        if (this.isDowned()) {
            return;
        }
        if (!this.rotorDamage().damaged()) {
            this.knockOutRotor(null);
        }
        this.drone.flight().setState(DroneState.DOWNED);
        this.drone.hold().spill();
        this.drone.recordEvent(WFEventType.DESTROYED, "shot down");
    }

    /**
     * Take one rotor disc off the airframe.
     *
     * @param from where the hit came from, or null for anywhere. A shot that arrives from one side takes
     *      the disc on that side: the drone then falls away from the shooter, which is both what
     *      would happen and the readable outcome.
     */
    public void knockOutRotor(@Nullable Vec3 from) {
        List<Rotor> rotors = Rotors.of(this.drone.getModelId());
        if (rotors.isEmpty()) {
            return;
        }
        int count = Math.min(rotors.size(), RotorDamage.MAX_ROTORS);
        byte out = this.drone.getEntityData().get(DroneEntity.ROTORS_OUT);
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if ((out & (1 << i)) != 0) {
                continue;
            }
            double score;
            if (from == null) {
                score = this.drone.getRandom().nextDouble();
            } else {
                Vector3f pivot = Rotors.pivot(rotors.get(i));
                score = from.distanceToSqr(this.armPosition(pivot));
            }
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            return;     // every disc already gone
        }
        this.drone.getEntityData().set(DroneEntity.ROTORS_OUT, (byte) (out | (1 << best)));
    }

    /**
     * @return where one rotor arm is in the world, for deciding which disc a shot took off.
     */
    private Vec3 armPosition(Vector3f pivot) {
        float yaw = this.drone.headingRadians();
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new Vec3(this.drone.getX() + pivot.x * cos + pivot.z * sin,
                this.drone.getY() + pivot.y,
                this.drone.getZ() + pivot.z * cos - pivot.x * sin);
    }

    /**
     * @return which discs are gone and what that does to the way this drone falls.
     */
    public RotorDamage rotorDamage() {
        return RotorDamage.of(this.drone.getModelId(), this.drone.getEntityData().get(DroneEntity.ROTORS_OUT));
    }

    /**
     * Blown apart in the air rather than shot down: the airframe stops existing as an airframe and comes down as
     * its own bones.
     */
    public void breakUp() {
        if (!(this.drone.level() instanceof ServerLevel serverLevel) || this.drone.isRemoved()) {
            return;
        }
        this.drone.hold().spill();
        this.drone.recordEvent(WFEventType.DESTROYED, "broke up");
        DroneDebrisEntity.shatter(serverLevel, this.drone.getModelId(), this.drone.position(),
                this.drone.headingRadians(), this.drone.getDeltaMovement());
        this.drone.discard();
    }

    /** The wreck reaching the ground: a small blast where it hit, and then it lies there. */
    private void crash(ServerLevel level) {
        this.crashed = true;
        Vec3 at = this.drone.position().add(0.0, DroneModels.center(this.drone.getModelId()).y, 0.0);
        level.explode(this.drone, at.x, at.y, at.z, CRASH_BLAST, Level.ExplosionInteraction.NONE);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 18, 0.5, 0.25, 0.5, 0.02);
        this.drone.setHeadingRadians(this.drone.getRandom().nextFloat() * (float) (Math.PI * 2.0));
        this.drone.flight().setAttitude(new FlightAttitude(
                (this.drone.getRandom().nextDouble() * 2.0 - 1.0) * CRASH_TILT,
                (this.drone.getRandom().nextDouble() * 2.0 - 1.0) * CRASH_TILT,
                0.0));
        this.drone.recordEvent(WFEventType.DESTROYED, "crashed");
    }

    /** Server side: rotor loss past {@link #ROTOR_LOSS_DAMAGE}, shoot-down at 0, break-up past overkill or when downed. */
    void hurt(@Nullable DamageSource source, float amount) {
        Vec3 from = source == null ? null : source.getSourcePosition();
        if (this.isDowned()) {
            // Hit again on the way down: there is nothing left to shoot down, so it comes apart instead.
            this.breakUp();
            return;
        }
        this.health -= amount;
        this.drone.recordEvent(WFEventType.DAMAGED, String.format("%.1f damage, %.1f hp left", amount, this.health));
        if (amount >= ROTOR_LOSS_DAMAGE) {
            this.knockOutRotor(from);
        }
        if (this.health <= 0.0f) {
            // Overkill: a warhead rather than a burst of cannon fire. There is no airframe left to fall.
            if (this.health <= -OVERKILL_HEALTH) {
                this.breakUp();
            } else {
                this.shootDown();
            }
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Health", this.health);
        tag.putByte("RotorsOut", this.drone.getEntityData().get(DroneEntity.ROTORS_OUT));
        tag.putBoolean("Crashed", this.crashed);
        return tag;
    }

    void load(CompoundTag tag) {
        this.health = tag.contains("Health") ? tag.getFloat("Health") : DEFAULT_HEALTH;
        this.drone.getEntityData().set(DroneEntity.ROTORS_OUT, tag.getByte("RotorsOut"));
        this.crashed = tag.getBoolean("Crashed");
    }
}
