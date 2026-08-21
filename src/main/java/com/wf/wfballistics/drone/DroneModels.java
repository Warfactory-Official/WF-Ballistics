package com.wf.wfballistics.drone;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.anim.Bones;
import com.wf.wfballistics.anim.Rotors;
import com.wf.wfballistics.attitude.MissileAttitudeRegistry;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.util.ObjBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of drone airframes, the {@code MissileModels} counterpart for things that fly under rotors. Kept
 * separate so drones never show up in the missile model pickers, but it feeds the same
 * {@link Rotors} animation registry and the same {@link MissileAttitudeRegistry}.
 *
 * <p>An airframe is registered as a body mesh plus a flat list of part meshes; {@link Bones} sorts those
 * into rotors and jaws by name and reads their pivots off the geometry, so this file says what a drone is
 * made of and nothing about how any of it moves.
 *
 * <p>Server-safe: only {@link ResourceLocation}s plus geometry read off the jar via {@link ObjBounds}.
 */
public final class DroneModels {

    public static final ResourceLocation DEFAULT = rl("quadcopter");
    public static final String DEFAULT_ATTITUDE = "copter";
    /**
     * Degrees per tick each disc turns while hovering; the visual scales it by the throttle. Taken from the
     * airframe so the drawn rotor speed and the thrust it is supposed to represent come from one number.
     *
     * <p><b>It must not be a multiple of 90.</b> The prop mesh is two crossed bars, so it is identical under
     * a quarter turn: at exactly 90°/tick every frame draws it in a pose indistinguishable from the last and
     * the rotors appear welded in place, spinning perfectly and visibly not at all. It is the wagon-wheel
     * effect, and it is why this used to be 90.
     */
    private static final float QUAD_ROTOR_SPEED = (float) Airframe.QUADCOPTER.hoverRotorSpeed();
    /**
     * Where the quadcopter's claws close, in model units: on the centreline, at the height the two grippers
     * curl under. A crate hangs by its lid from here.
     */
    private static final Vec3 QUAD_MOUNT = new Vec3(0.0, 0.21, 0.0);

    private static final Map<ResourceLocation, Rig> RIGS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Vec3> DIMENSIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Vec3> CENTERS = new ConcurrentHashMap<>();

    static {
        reg("quadcopter", "drone_body", QUAD_MOUNT, DEFAULT_ATTITUDE, QUAD_ROTOR_SPEED,
                "drone_prop_px", "drone_prop_nx", "drone_prop_pz", "drone_prop_nz",
                "drone_jaw_pz", "drone_jaw_nz");
    }

    private DroneModels() {
    }

    public static ResourceLocation rl(String id) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, id);
    }

    public static ResourceLocation partModel(String modelName) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "entity/drones/" + modelName);
    }

    /**
     * Register an airframe: its hull mesh, where a payload hangs off it, how it orients to its heading, and
     * every part that moves. The parts are not classified here: see {@link Bones}.
     *
     * @param mount       payload attach point in model units: the <em>top centre</em> of whatever is slung
     *                    underneath, so a load of any size hangs from the grippers rather than through them
     * @param rotorSpeed  hover speed of one rotor disc, degrees/tick
     */
    private static void reg(String id, String bodyModel, Vec3 mount, String attitudeId, float rotorSpeed,
                            String... partModels) {
        ResourceLocation key = rl(id);
        List<ResourceLocation> parts = new ArrayList<>(partModels.length);
        for (String part : partModels) {
            parts.add(partModel(part));
        }
        RIGS.put(key, new Rig(partModel(bodyModel), mount, attitudeId, List.copyOf(parts)));
        Bones.rig(key, rotorSpeed, parts);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null && RIGS.containsKey(parsed) ? parsed : DEFAULT;
    }

    public static boolean exists(ResourceLocation id) {
        return RIGS.containsKey(id);
    }

    public static ResourceLocation model(ResourceLocation id) {
        return rig(id).body();
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(RIGS.keySet());
    }

    public static String attitudeId(ResourceLocation id) {
        return rig(id).attitudeId();
    }

    /**
     * @return every moving part of an airframe: the meshes drawn on top of the body. Rotors and jaws both,
     * unclassified, which is all the client needs to bake them. {@link Rotors#of} and {@link Bones#jaws} are
     * what say which is which.
     */
    public static List<ResourceLocation> parts(ResourceLocation id) {
        return rig(id).parts();
    }

    /**
     * @return where a slung payload attaches, in model units: the point its top face is held at.
     */
    public static Vec3 mount(ResourceLocation id) {
        return rig(id).mount();
    }

    /**
     * @return the mesh size (per-axis, model units) of the whole airframe, cached. Measured across the body
     * <em>and</em> its parts, because a quadcopter is as wide as its rotor discs and the hitbox this feeds
     * would otherwise stop at the arms. Falls back to a 1x1x1 box when the assets can't be read.
     */
    public static Vec3 dimensions(ResourceLocation id) {
        return DIMENSIONS.computeIfAbsent(id, i -> {
            Vec3 d = union(i).size();
            return (d.x > 1.0E-3 || d.y > 1.0E-3 || d.z > 1.0E-3) ? d : new Vec3(1.0, 1.0, 1.0);
        });
    }

    /**
     * @return the geometric centre offset (model units) of the whole airframe relative to its origin,
     * cached. Drone meshes sit feet-at-origin, so this is roughly {@code (0, height/2, 0)}.
     */
    public static Vec3 center(ResourceLocation id) {
        return CENTERS.computeIfAbsent(id, i -> union(i).center());
    }

    /**
     * @return bounds enclosing the body and every part of an airframe. Parts that fail to read are skipped
     * rather than collapsing the box, so a missing prop mesh costs a bit of hitbox, not all of it.
     */
    private static ObjBounds.Bounds union(ResourceLocation id) {
        Rig frame = rig(id);
        ObjBounds.Bounds total = read(frame.body());
        for (ResourceLocation part : frame.parts()) {
            total = merge(total, read(part));
        }
        return total;
    }

    private static ObjBounds.Bounds read(ResourceLocation model) {
        try {
            return ObjBounds.boundsFromModel(model);
        } catch (Throwable t) {
            return ObjBounds.Bounds.EMPTY;
        }
    }

    private static ObjBounds.Bounds merge(ObjBounds.Bounds a, ObjBounds.Bounds b) {
        if (!a.any()) {
            return b;
        }
        if (!b.any()) {
            return a;
        }
        return new ObjBounds.Bounds(
                Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()),
                true);
    }

    private static Rig rig(ResourceLocation id) {
        Rig frame = RIGS.get(id);
        return frame != null ? frame : RIGS.get(DEFAULT);
    }

    /**
     * One registered drone: what it is made of and how it carries things.
     *
     * @param body       the hull mesh, everything that does not move
     * @param mount      payload attach point, model units (top centre of the load)
     * @param attitudeId how it orients to its heading, see {@link MissileAttitudeRegistry}
     * @param parts      every moving mesh, sorted into rotors and jaws by {@link Bones}
     */
    private record Rig(ResourceLocation body, Vec3 mount, String attitudeId,
                       List<ResourceLocation> parts) {
    }
}
