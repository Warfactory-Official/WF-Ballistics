package com.wf.wflib.anim;

import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;

import java.util.ArrayList;
import java.util.List;

/** Draws one node of a model and nothing else, by collapsing every other branch to a point. */
public record NodeIsolate(int[] hidden) implements PoseDriver {

    /**
     * @param keep the palette slot of the one node that stays
     */
    public static NodeIsolate of(NodeTable table, int keep) {
        int count = table.nodeCount();
        boolean[] onPath = new boolean[count];
        if (keep >= 0 && keep < count) {
            onPath[keep] = true;
            int[] parents = table.parentSlots();
            for (int p = parents[keep]; p >= 0; p = parents[p]) {
                onPath[p] = true;
            }
            for (int i = 0; i < count; i++) {
                for (int p = i; p >= 0; p = parents[p]) {
                    if (p == keep) {
                        onPath[i] = true;
                        break;
                    }
                }
            }
        }

        List<Integer> hide = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!onPath[i] && table.isPosable(i)) {
                hide.add(table.offsetFor(i, "scale"));
            }
        }

        int[] offsets = new int[hide.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = hide.get(i);
        }
        return new NodeIsolate(offsets);
    }

    @Override
    public void apply(float timeSeconds, float[] scratch) {
        for (int offset : hidden) {
            if (offset < 0) {
                continue;
            }
            scratch[offset] = 0.0f;
            scratch[offset + 1] = 0.0f;
            scratch[offset + 2] = 0.0f;
        }
    }

    @Override
    public float cycleSeconds() {
        return 1.0f;
    }
}
