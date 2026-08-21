package com.wf.wfballistics.anim;

import com.wf.wfballistics.util.ObjBounds;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out what a model's moving parts <em>are</em> from what they are called, so an airframe animates by
 * being modelled rather than by being described in code twice.
 *
 * <p>A part whose name contains {@code prop} is a rotor disc; one containing {@code jaw} is a gripper jaw.
 * Everything else is structure and is left to the body model. The interesting half is that nothing beyond
 * the name is declared: the hinge a jaw swings about, the direction it opens, and which way each rotor turns
 * are all read back off the meshes, so adding a hexacopter is a matter of dropping six {@code *_prop_*.obj}
 * files in and registering them.
 *
 * <p>Server-safe: {@link ObjBounds} reads the meshes straight out of the jar.
 */
public final class Bones {

    private static final String PROP = "prop";
    private static final String JAW = "jaw";
    /**
     * How far a jaw swings when it lets go. Wide enough to read as "opened" from the distance a delivery is
     * normally watched at, short of the arm folding back into the airframe.
     */
    private static final float JAW_OPEN_DEGREES = 38.0f;

    private static final Map<ResourceLocation, List<Jaw>> JAWS = new HashMap<>();

    private Bones() {
    }

    /**
     * Register every moving part of a model at once, sorting them by name into rotors and jaws.
     *
     * <p>Rotors go into the shared {@link Rotors} registry, which is what the missile props already use, so
     * there is one place that knows how to draw a spinning part.
     *
     * @param rotorSpeed hover speed of a rotor disc, degrees/tick. Signs are assigned here, not passed in
     */
    public static void rig(ResourceLocation modelId, float rotorSpeed, List<ResourceLocation> parts) {
        List<ResourceLocation> props = new ArrayList<>();
        for (ResourceLocation part : parts) {
            String name = leaf(part);
            if (name.contains(PROP)) {
                props.add(part);
            } else if (name.contains(JAW)) {
                JAWS.computeIfAbsent(modelId, k -> new ArrayList<>()).add(jaw(part));
            }
        }
        assignRotors(modelId, rotorSpeed, props);
    }

    /**
     * @return the gripper jaws of a model, in registration order.
     */
    public static List<Jaw> jaws(ResourceLocation modelId) {
        return JAWS.getOrDefault(modelId, List.of());
    }

    /**
     * Hand out spin directions so that neighbouring rotors turn opposite ways and opposite rotors turn the
     * same way, which is how a real multirotor cancels the torque it would otherwise spin itself with. Done
     * by sorting the discs by their bearing around the airframe and alternating, so the layout of the model
     * decides it rather than the order the parts happen to be listed in.
     *
     * <p>An odd number of rotors cannot alternate all the way round: the last disc meets the first turning
     * the same way. That is true of real odd-rotor craft too (a tricopter tilts a motor to make up for it),
     * and it costs nothing here beyond one pair of neighbours agreeing.
     */
    private static void assignRotors(ResourceLocation modelId, float rotorSpeed, List<ResourceLocation> props) {
        props.sort(Comparator.comparingDouble(p -> {
            Vec3 c = center(p);
            return Math.atan2(c.z, c.x);
        }));
        for (int i = 0; i < props.size(); i++) {
            float speed = (i % 2 == 0) ? rotorSpeed : -rotorSpeed;
            Rotors.assign(modelId, Rotor.of(props.get(i), Axis.Y, speed));
        }
    }

    /**
     * Read a jaw's hinge off its mesh: it swings from its top edge, on the side facing the airframe's
     * centreline, outward and away from whatever it was holding.
     */
    private static Jaw jaw(ResourceLocation part) {
        ObjBounds.Bounds bounds = ObjBounds.boundsFromModel(part);
        Vec3 center = bounds.center();
        Vec3 size = bounds.size();

        // Which way the jaw sticks out from the airframe's centreline. That is also the way it opens.
        Vector3f out = new Vector3f((float) center.x, 0.0f, (float) center.z);
        if (out.lengthSquared() < 1.0E-6f) {
            // A jaw sitting on the centreline gives no clue which way it should swing; pick one so it at
            // least moves rather than silently animating in place.
            out.set(0.0f, 0.0f, 1.0f);
        }
        out.normalize();

        // Top of the mesh, pulled back to its inboard face: the corner the arm actually hangs from.
        Vector3f hinge = new Vector3f(
                (float) (center.x - out.x * size.x * 0.5),
                (float) bounds.maxY(),
                (float) (center.z - out.z * size.z * 0.5));

        // Rotating about this axis carries the jaw's tip, which hangs below the hinge, outward along
        // `out`, so a positive angle is always "open" whichever side of the airframe the jaw is on.
        Vector3f axis = new Vector3f(-out.z, 0.0f, out.x);

        return new Jaw(part, hinge, axis, (float) Math.toRadians(JAW_OPEN_DEGREES));
    }

    private static Vec3 center(ResourceLocation part) {
        try {
            return ObjBounds.centerFromModel(part);
        } catch (Throwable t) {
            return Vec3.ZERO;
        }
    }

    private static String leaf(ResourceLocation part) {
        String path = part.getPath();
        int slash = path.lastIndexOf('/');
        return (slash < 0 ? path : path.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }
}
