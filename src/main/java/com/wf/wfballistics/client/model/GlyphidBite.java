package com.wf.wfballistics.client.model;

import com.wf.gemrender.gltf.NodeRotation;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;
import net.minecraft.util.Mth;

/**
 * The mandible snap, as a GemRender pose driver.
 *
 * @param radians how far the plate has swung at full bite; negative swings it the other way
 */
public record GlyphidBite(int offset, float axisX, float axisY, float axisZ, float radians)
        implements PoseDriver {

    private static final float TAU = (float) (Math.PI * 2.0);

    public static GlyphidBite about(NodeTable table, int slot, float axisX, float axisY, float axisZ,
                                    float radians) {
        float[] axis = NodeRotation.axis(axisX, axisY, axisZ);
        return new GlyphidBite(NodeRotation.offsetOf(table, slot), axis[0], axis[1], axis[2], radians);
    }

    @Override
    public void apply(float timeSeconds, float[] scratch) {
        float snap = Mth.clamp(Mth.sin(timeSeconds * TAU - (float) (Math.PI * 0.5)), 0.0f, 1.0f);
        NodeRotation.compose(scratch, offset, axisX, axisY, axisZ, radians * snap);
    }

    @Override
    public float cycleSeconds() {
        return 1.0f;
    }
}
