package com.wf.wfballistics.anim;

import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * One turning disc: what it is called, which way and how fast it goes round, and where it pivots.
 *
 * @param model the disc's identity: a mesh for a missile prop, a piece id for a drone rotor
 * @param node  the glTF node the disc is, for an airframe drawn from a node graph; null for a missile
 *              prop, which is a loose mesh with no graph to name a node in
 * @param pivot where it turns about, model units, or null to read it back off {@code model}'s own mesh
 */
public record Rotor(ResourceLocation model, @Nullable String node, Vector3f axis, float degreesPerTick,
                    @Nullable Vector3f pivot) {

    public static Rotor of(ResourceLocation model, Axis axis, float degreesPerTick) {
        return of(model, unit(axis), degreesPerTick);
    }

    public static Rotor of(ResourceLocation model, Vector3f axis, float degreesPerTick) {
        return new Rotor(model, null, new Vector3f(axis).normalize(), degreesPerTick, null);
    }

    private static Vector3f unit(Axis axis) {
        return switch (axis) {
            case X -> new Vector3f(1.0f, 0.0f, 0.0f);
            case Y -> new Vector3f(0.0f, 1.0f, 0.0f);
            case Z -> new Vector3f(0.0f, 0.0f, 1.0f);
        };
    }

    public Rotor withPivot(float x, float y, float z) {
        return new Rotor(model, node, axis, degreesPerTick, new Vector3f(x, y, z));
    }

    /**
     * Name the node this disc is and where it turns, which for a model with a hierarchy are one fact: the
     * hub node's own origin is the pivot, because that is what the blades were parented to.
     */
    public Rotor at(String node, float x, float y, float z) {
        return new Rotor(model, node, axis, degreesPerTick, new Vector3f(x, y, z));
    }

    public float angle(float ticks) {
        return (float) Math.toRadians(ticks * degreesPerTick % 360.0f);
    }
}
