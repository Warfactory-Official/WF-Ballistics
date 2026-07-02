package com.wf.wfballistics;

import com.wf.wfballistics.util.ObjBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the missile models a {@link MissileEntity} can render with, keyed by a short stable id
 * (persisted/synced on the entity so the model is chosen at runtime, HBM-style, rather than compiled in).
 *
 * <p>Server-safe: it only holds {@link ResourceLocation}s and reads model geometry off the jar via
 * {@link ObjBounds}, so the flight code can look up a model's length without touching client render classes.
 * The client separately bakes a {@code PartialModel} for each of these ids (see {@code ModModels}).
 */
public final class MissileModels {

    /**
     * Id used when a requested one is unknown or unset.
     */
    public static final String DEFAULT = "v2";

    // Continuous spin speed for the Shahed pusher propeller (degrees per tick). Purely visual.
    private static final float SHAHED_ROTOR_SPEED = 45.0f;

    private static final Map<String, ResourceLocation> BY_ID = new LinkedHashMap<>();
    private static final Map<String, Double> LENGTHS = new ConcurrentHashMap<>();
    private static final Map<String, Vec3> DIMENSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Vec3> CENTERS = new ConcurrentHashMap<>();
    // Spinning parts ("rotors") per model id, and a cache of each rotor mesh's centre (its spin pivot).
    private static final Map<String, List<Rotor>> ROTORS = new HashMap<>();
    private static final Map<ResourceLocation, Vec3> ROTOR_PIVOTS = new ConcurrentHashMap<>();

    static {
        // Base airframes (each has its own hand-authored model json = OBJ + default skin).
        reg("abm", "missile_abm");
        reg("atlas", "missile_atlas");
        reg("booster", "missilebooster");
        reg("carrier", "missilecarrier");
        reg("huge", "missile_huge");
        reg("micro", "missile_micro");
        reg("neon", "missileneon");
        reg("shuttle", "missile_shuttle");
        reg("stealth", "missile_stealth");
        reg("strong", "missile_strong");
        reg("taint", "missiletaint");
        reg("thermo", "missilethermo");
        reg("v2", "missile_v2");

        // Skins: the same airframe OBJ re-textured. Each variant model json points at the shared .obj (whose
        // .mtl reads its texture from the json via #missile_texture) with a different skin, so one shape can
        // be flown in several liveries. Selectable anywhere a model id is (dispenser GUI, missile presets).
        reg("v2_bunker", "missile_v2_bu");
        reg("v2_cluster", "missile_v2_cl");
        reg("v2_decoy", "missile_v2_decoy");
        reg("v2_incendiary", "missile_v2_inc");

        reg("strong_bunker", "missile_strong_bu");
        reg("strong_cluster", "missile_strong_cl");
        reg("strong_emp", "missile_strong_emp");
        reg("strong_incendiary", "missile_strong_inc");

        reg("micro_bhole", "missile_micro_bhole");
        reg("micro_emp", "missile_micro_emp");
        reg("micro_schrab", "missile_micro_schrab");
        reg("micro_taint", "missile_micro_taint");

        reg("huge_bunker", "missile_huge_bu");
        reg("huge_cluster", "missile_huge_cl");
        reg("huge_incendiary", "missile_huge_inc");

        reg("atlas_doomsday", "missile_atlas_doomsday");
        reg("atlas_doomsday_weathered", "missile_atlas_doomsday_weathered");
        reg("atlas_tectonic", "missile_atlas_tectonic");
        reg("atlas_thermo", "missile_atlas_thermo");

        // Shahed-136 loitering drones: a winged airframe (body model) with a pusher propeller (the "prop"
        // mesh, split into its own model) that spins continuously about the fuselage/long (+Y) axis. Adding a
        // spinning part is just reg(body) + rotor(prop) — no per-model code anywhere in the render path.
        reg("shahed", "shahed_body");
        rotor("shahed", "shahed_prop", 0.0f, 1.0f, 0.0f, SHAHED_ROTOR_SPEED);
        reg("shahedjarty", "shahedjarty_body");
        rotor("shahedjarty", "shahedjarty_prop", 0.0f, 1.0f, 0.0f, SHAHED_ROTOR_SPEED);
    }

    private MissileModels() {
    }

    private static void reg(String id, String modelName) {
        BY_ID.put(id, new ResourceLocation(WFBallistics.MODID, "entity/missiles/" + modelName));
    }

    /**
     * @return true if the id maps to a known model.
     */
    public static boolean exists(String id) {
        return BY_ID.containsKey(id);
    }

    /**
     * @return the model json location for the id, falling back to {@link #DEFAULT}.
     */
    public static ResourceLocation model(String id) {
        return BY_ID.getOrDefault(id, BY_ID.get(DEFAULT));
    }

    /**
     * @return all registered ids, in registration order.
     */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }

    /**
     * @return the longest axis of the model's mesh, in model units (cached). Reads the model json + obj
     * off the jar, so it works server-side. Falls back to 1.0 if the assets can't be read.
     */
    public static double length(String id) {
        return LENGTHS.computeIfAbsent(id, i -> {
            try {
                double len = ObjBounds.longestAxisFromModel(model(i));
                return len > 1.0E-3 ? len : 1.0;
            } catch (Throwable t) {
                return 1.0;
            }
        });
    }

    /**
     * @return the mesh size (per-axis, model units) of the missile model, cached. Reads the model json +
     * obj off the jar so it works server-side. Falls back to a 1×1×1 box if the assets can't be read.
     */
    public static Vec3 dimensions(String id) {
        return DIMENSIONS.computeIfAbsent(id, i -> {
            try {
                Vec3 d = ObjBounds.dimensionsFromModel(model(i));
                return (d.x > 1.0E-3 || d.y > 1.0E-3 || d.z > 1.0E-3) ? d : new Vec3(1.0, 1.0, 1.0);
            } catch (Throwable t) {
                return new Vec3(1.0, 1.0, 1.0);
            }
        });
    }

    /**
     * @return the geometric center offset (model units) of the missile model relative to its origin,
     * cached. Missile meshes sit base-at-origin, so this is roughly {@code (0, length/2, 0)}.
     */
    public static Vec3 center(String id) {
        return CENTERS.computeIfAbsent(id, i -> {
            try {
                return ObjBounds.centerFromModel(model(i));
            } catch (Throwable t) {
                return Vec3.ZERO;
            }
        });
    }

    /**
     * A continuously spinning part of a missile model (rotor / propeller): a separate mesh the client draws
     * as its own instance, spun about {@code axis} at {@code degreesPerTick}. The spin pivot is derived from
     * the mesh's own centre (see {@link #rotorPivot}), so nothing is hardcoded per model.
     *
     * @param model          model-json location of the rotor mesh (baked separately by {@code ModModels})
     * @param axis           unit spin axis in model space
     * @param degreesPerTick constant spin rate
     */
    public record Rotor(ResourceLocation model, Vector3f axis, float degreesPerTick) {
    }

    /**
     * Register a spinning part for a model. Drop in the rotor's model json/obj and add one call — the client
     * renderer picks it up generically; there is no per-model spin code.
     */
    public static void rotor(String id, String rotorModelName, float axisX, float axisY, float axisZ,
                             float degreesPerTick) {
        ResourceLocation model = new ResourceLocation(WFBallistics.MODID, "entity/missiles/" + rotorModelName);
        ROTORS.computeIfAbsent(id, k -> new ArrayList<>())
                .add(new Rotor(model, new Vector3f(axisX, axisY, axisZ).normalize(), degreesPerTick));
    }

    /**
     * @return the spinning parts registered for a model id (empty if none).
     */
    public static List<Rotor> rotors(String id) {
        return ROTORS.getOrDefault(id, List.of());
    }

    /**
     * @return the spin pivot for a rotor mesh (its geometric centre, cached), read off the jar so it works
     * without hardcoded coordinates.
     */
    public static Vec3 rotorPivot(ResourceLocation rotorModel) {
        return ROTOR_PIVOTS.computeIfAbsent(rotorModel, m -> {
            try {
                return ObjBounds.centerFromModel(m);
            } catch (Throwable t) {
                return Vec3.ZERO;
            }
        });
    }
}
