package com.wf.wflib.anim;

import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * @param model mesh id (missile prop) or piece id (drone rotor)
 * @param node null => missile prop (loose mesh, no node graph)
 * @param pivot null => read off {@code model}'s own mesh bounds
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

    /** Hub node's own origin doubles as pivot: blades are parented to it. */
    public Rotor at(String node, float x, float y, float z) {
        return new Rotor(model, node, axis, degreesPerTick, new Vector3f(x, y, z));
    }

    public float angle(float ticks) {
        return (float) Math.toRadians(ticks * degreesPerTick % 360.0f);
    }
}
