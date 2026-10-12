package com.wf.wflib.anim;

import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;

// FIXME: offset() not overridden (unlike NodeHide); BlendMask.drivenBy treats a clip with this as ALL.
public record NodeScale(int offset, float from, float to) implements PoseDriver {

    public static NodeScale away(NodeTable table, int slot) {
        return between(table, slot, 1.0f, 0.0f);
    }

    public static NodeScale between(NodeTable table, int slot, float from, float to) {
        return new NodeScale(table.offsetFor(slot, "scale"), from, to);
    }

    @Override
    public void apply(float timeSeconds, float[] scratch) {
        if (offset < 0) {
            return;
        }
        float unit = Math.min(1.0f, Math.max(0.0f, timeSeconds));
        float factor = from + (to - from) * unit;
        scratch[offset] *= factor;
        scratch[offset + 1] *= factor;
        scratch[offset + 2] *= factor;
    }

    @Override
    public float cycleSeconds() {
        return 1.0f;
    }
}
