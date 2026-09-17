package com.wf.wfballistics.drone;

import com.wf.wfballistics.anim.Rotor;
import com.wf.wfballistics.anim.Rotors;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.List;

/**
 * Which rotors a drone has lost, and what that does to the way it falls.
 *
 * @param out bitmask of dead rotors, indexed as {@link Rotors#of} lists them
 * @param leanX model-space X of the lost lift, -1..1
 * @param leanZ model-space Z of the lost lift, -1..1
 * @param spin residual yaw torque, in the same units as a heading step (radians/tick)
 * @param lost how many discs are dead
 * @param count how many the airframe has
 */
public record RotorDamage(byte out, double leanX, double leanZ, float spin, int lost, int count) {

    /** Nothing wrong with it. */
    public static final RotorDamage INTACT = new RotorDamage((byte) 0, 0.0, 0.0, 0.0f, 0, 0);

    /** How much yaw one unbalanced disc is worth, radians/tick. */
    private static final float SPIN_PER_ROTOR = 0.30f;

    /**
     * The most rotors any airframe here can have, because the dead set is carried as a byte: it is one synced value
     * on an entity that already sends a handful every tick, and eight is two more than a hexacopter.
     */
    public static final int MAX_ROTORS = 8;

    /**
     * @param modelId the airframe, for its rotor layout
     * @param out bitmask of dead discs
     */
    public static RotorDamage of(ResourceLocation modelId, byte out) {
        List<Rotor> rotors = Rotors.of(modelId);
        if (rotors.isEmpty() || out == 0) {
            return INTACT;
        }
        double x = 0.0;
        double z = 0.0;
        float torque = 0.0f;
        int lost = 0;
        int n = Math.min(rotors.size(), MAX_ROTORS);
        for (int i = 0; i < n; i++) {
            if ((out & (1 << i)) == 0) {
                continue;
            }
            Rotor rotor = rotors.get(i);
            Vector3f pivot = Rotors.pivot(rotor);
            x += pivot.x;
            z += pivot.z;
            torque += Math.signum(rotor.degreesPerTick());
            lost++;
        }
        if (lost == 0) {
            return INTACT;
        }
        double reach = 0.0;
        for (int i = 0; i < n; i++) {
            Vector3f pivot = Rotors.pivot(rotors.get(i));
            reach = Math.max(reach, Math.sqrt(pivot.x * pivot.x + pivot.z * pivot.z));
        }
        double scale = reach > 1.0E-4 ? 1.0 / (reach * lost) : 0.0;
        return new RotorDamage(out, x * scale, z * scale, torque * SPIN_PER_ROTOR, lost, rotors.size());
    }

    /**
     * @return true if any disc is dead.
     */
    public boolean damaged() {
        return lost > 0;
    }

    /**
     * @return true once every disc is dead, which is a dead airframe rather than a crippled one.
     */
    public boolean dead() {
        return count > 0 && lost >= count;
    }

    /**
     * @return how one-sided the remaining lift is, 0 (balanced, or none left) to 1 (all of it on one side).
     */
    public double asymmetry() {
        return Math.min(1.0, Math.sqrt(leanX * leanX + leanZ * leanZ));
    }

    /**
     * @return true if this disc is dead.
     */
    public boolean isOut(int index) {
        return index >= 0 && index < MAX_ROTORS && (out & (1 << index)) != 0;
    }
}
