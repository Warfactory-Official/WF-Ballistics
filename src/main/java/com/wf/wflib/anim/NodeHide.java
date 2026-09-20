package com.wf.wflib.anim;

import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;

/**
 * Collapses one node's subtree the moment clip time passes a threshold: a part that is either there or gone, with
 * nothing in between.
 *
 * @param offset the node's scale offset in the pose scratch
 * @param threshold clip time at and after which the node is collapsed
 */
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
