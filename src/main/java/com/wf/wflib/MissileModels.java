package com.wf.wflib;

import com.wf.wflib.anim.Rotor;
import com.wf.wflib.anim.Rotors;
import com.wf.wflib.util.ObjBounds;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** {@link MissileEntity} model registry; id persists/syncs on the entity (HBM-style runtime pick, not compiled in). */
public final class MissileModels {

    /** Fallback when a requested id is unknown or unset. */
    public static final ResourceLocation DEFAULT = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "v2");
    public static final String DEFAULT_ATTITUDE = "missile";
    // Shahed pusher prop spin, deg/tick; visual only.
    private static final float SHAHED_ROTOR_SPEED = 45.0f;
    private static final Map<ResourceLocation, ResourceLocation> BY_ID = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Double> LENGTHS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Vec3> DIMENSIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Vec3> CENTERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, String> ATTITUDES = new HashMap<>();

    static {
        // Base airframes (each has its own hand-authored model json = OBJ + default skin).
        reg("abm", "missile_abm");
        reg("atlas", "missile_atlas");
        reg("booster", "missilebooster");
        reg("carrier", "missilecarrier");
        reg("cluster", "missile_cluster");
        reg("cluster_part", "missile_cluster_part");
        reg("huge", "missile_huge");
        reg("micro", "missile_micro");
        reg("neon", "missileneon");
        reg("shuttle", "missile_shuttle");
        reg("stealth", "missile_stealth");
        reg("strong", "missile_strong");
        reg("taint", "missiletaint");
        reg("thermo", "missilethermo");
        reg("v2", "missile_v2");

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

        // Air-launched, pylon-sized: tools/small_airframes.py.
        reg("atgm", "missile_atgm");
        reg("guided_rocket", "missile_guided_rocket");

        reg("atlas_doomsday", "missile_atlas_doomsday");
        reg("atlas_doomsday_weathered", "missile_atlas_doomsday_weathered");
        reg("atlas_tectonic", "missile_atlas_tectonic");
        reg("atlas_thermo", "missile_atlas_thermo");

        reg("shahed", "shahed_body");
        rotor("shahed", "shahed_prop", Axis.Y, SHAHED_ROTOR_SPEED);
        attitude("shahed", "drone");
        reg("shahedjarty", "shahedjarty_body");
        rotor("shahedjarty", "shahedjarty_prop", Axis.Y, SHAHED_ROTOR_SPEED);
        attitude("shahedjarty", "drone");
    }

    private MissileModels() {
    }

    private static void reg(String id, String modelName) {
        BY_ID.put(rl(id), partModel(modelName));
    }

    public static ResourceLocation partModel(String modelName) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "entity/missiles/" + modelName);
    }

    public static ResourceLocation rl(String id) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, id);
    }

    public static ResourceLocation defaultId() {
        return DEFAULT;
    }

    /** Bare path under mod namespace, {@code ns:path} as-is, unparseable => {@link #DEFAULT}. */
    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null ? parsed : DEFAULT;
    }

    public static boolean exists(ResourceLocation id) {
        return BY_ID.containsKey(id);
    }

    public static ResourceLocation model(ResourceLocation id) {
        return BY_ID.getOrDefault(id, BY_ID.get(DEFAULT));
    }

    /** @return registered ids, in registration order. */
    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }

    /** @return longest mesh axis (model units), cached; reads off the jar (works server-side); 1.0 if unreadable. */
    public static double length(ResourceLocation id) {
        return LENGTHS.computeIfAbsent(id, i -> {
            try {
                double len = ObjBounds.longestAxisFromModel(model(i));
                return len > 1.0E-3 ? len : 1.0;
            } catch (Throwable t) {
                return 1.0;
            }
        });
    }

    /** @return mesh size per axis (model units), cached; reads off the jar (works server-side); 1x1x1 if unreadable. */
    public static Vec3 dimensions(ResourceLocation id) {
        return DIMENSIONS.computeIfAbsent(id, i -> {
            try {
                Vec3 d = ObjBounds.dimensionsFromModel(model(i));
                return (d.x > 1.0E-3 || d.y > 1.0E-3 || d.z > 1.0E-3) ? d : new Vec3(1.0, 1.0, 1.0);
            } catch (Throwable t) {
                return new Vec3(1.0, 1.0, 1.0);
            }
        });
    }

    /** @return center offset (model units), cached; base-at-origin mesh => roughly {@code (0, length/2, 0)}. */
    public static Vec3 center(ResourceLocation id) {
        return CENTERS.computeIfAbsent(id, i -> {
            try {
                return ObjBounds.centerFromModel(model(i));
            } catch (Throwable t) {
                return Vec3.ZERO;
            }
        });
    }

    public static void rotor(String id, String rotorModelName, Axis axis, float degreesPerTick) {
        Rotors.assign(rl(id), Rotor.of(partModel(rotorModelName), axis, degreesPerTick));
    }

    public static void rotor(String id, String rotorModelName, Vector3f axis, float degreesPerTick) {
        Rotors.assign(rl(id), Rotor.of(partModel(rotorModelName), axis, degreesPerTick));
    }

    public static void rotors(String id, Axis axis, float degreesPerTick, String... rotorModelNames) {
        for (String rotorModelName : rotorModelNames) {
            rotor(id, rotorModelName, axis, degreesPerTick);
        }
    }

    public static List<Rotor> rotors(ResourceLocation id) {
        return Rotors.of(id);
    }

    /** Set how a model orients to its heading, by attitude id (see {@code MissileAttitudeRegistry}). */
    public static void attitude(String id, String attitudeId) {
        ATTITUDES.put(rl(id), attitudeId);
    }

    public static String attitudeId(ResourceLocation id) {
        return ATTITUDES.getOrDefault(id, DEFAULT_ATTITUDE);
    }

}
