package com.wf.wfballistics.anim;

import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

public record Rotor(ResourceLocation model, Vector3f axis, float degreesPerTick, Vector3f pivot) {

    public static Rotor of(ResourceLocation model, Axis axis, float degreesPerTick) {
        return of(model, unit(axis), degreesPerTick);
    }

    public static Rotor of(ResourceLocation model, Vector3f axis, float degreesPerTick) {
        return new Rotor(model, new Vector3f(axis).normalize(), degreesPerTick, null);
    }

    private static Vector3f unit(Axis axis) {
        return switch (axis) {
            case X -> new Vector3f(1.0f, 0.0f, 0.0f);
            case Y -> new Vector3f(0.0f, 1.0f, 0.0f);
            case Z -> new Vector3f(0.0f, 0.0f, 1.0f);
        };
    }

    public Rotor withPivot(float x, float y, float z) {
        return new Rotor(model, axis, degreesPerTick, new Vector3f(x, y, z));
    }

    public float angle(float ticks) {
        return (float) Math.toRadians(ticks * degreesPerTick % 360.0f);
    }
}
