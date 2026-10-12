package com.wf.wflib.anim;

import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;

public record NodeHide(int offset, float threshold) implements PoseDriver {

    public static NodeHide at(NodeTable table, int slot, float threshold) {
        return new NodeHide(table.offsetFor(slot, "scale"), threshold);
    }

    @Override
    public void apply(float timeSeconds, float[] scratch) {
        if (offset < 0 || timeSeconds < threshold) {
            return;
        }
        scratch[offset] = 0.0f;
        scratch[offset + 1] = 0.0f;
        scratch[offset + 2] = 0.0f;
    }

    @Override
    public float cycleSeconds() {
        return 1.0f;
    }

    @Override
    public int offset() {
        return offset;
    }
}
