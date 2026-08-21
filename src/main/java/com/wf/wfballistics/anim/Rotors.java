package com.wf.wfballistics.anim;

import com.wf.wfballistics.util.ObjBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Rotors {

    private static final Map<ResourceLocation, List<Rotor>> BY_MODEL = new HashMap<>();
    private static final Map<ResourceLocation, Vector3f> PIVOTS = new ConcurrentHashMap<>();

    private Rotors() {
    }

    public static void assign(ResourceLocation modelId, Rotor... rotors) {
        BY_MODEL.computeIfAbsent(modelId, k -> new ArrayList<>()).addAll(List.of(rotors));
    }

    public static List<Rotor> of(ResourceLocation modelId) {
        return BY_MODEL.getOrDefault(modelId, List.of());
    }

    public static Vector3f pivot(Rotor rotor) {
        Vector3f explicit = rotor.pivot();
        return new Vector3f(explicit != null ? explicit : PIVOTS.computeIfAbsent(rotor.model(), Rotors::center));
    }

    private static Vector3f center(ResourceLocation model) {
        try {
            Vec3 center = ObjBounds.centerFromModel(model);
            return new Vector3f((float) center.x, (float) center.y, (float) center.z);
        } catch (Throwable t) {
            return new Vector3f();
        }
    }
}
