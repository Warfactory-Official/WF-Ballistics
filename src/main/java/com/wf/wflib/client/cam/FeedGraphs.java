package com.wf.wflib.client.cam;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/** Every section-visibility graph currently in play: the main view's, and one per live drone feed. */
public final class FeedGraphs {

    /** Identity list. Never large: one entry per live feed plus the main view's. */
    private static final List<SectionOcclusionGraph> GRAPHS = new ArrayList<>(4);

    private FeedGraphs() {
    }

    static void track(SectionOcclusionGraph graph) {
        for (SectionOcclusionGraph known : GRAPHS) {
            if (known == graph) {
                return;
            }
        }
        GRAPHS.add(graph);
    }

    static void forget(SectionOcclusionGraph graph) {
        GRAPHS.removeIf(known -> known == graph);
    }

    /** Drop every registration. On disconnect, and whenever the section grid is replaced. */
    public static void forgetAll() {
        GRAPHS.clear();
    }

    public static void onSectionCompiled(SectionOcclusionGraph notified,
                                         SectionRenderDispatcher.RenderSection section) {
        for (int i = 0; i < GRAPHS.size(); i++) {
            SectionOcclusionGraph graph = GRAPHS.get(i);
            if (graph != notified) {
                graph.onSectionCompiled(section);
            }
        }
    }

    public static void onChunkLoaded(SectionOcclusionGraph notified, ChunkPos pos) {
        for (int i = 0; i < GRAPHS.size(); i++) {
            SectionOcclusionGraph graph = GRAPHS.get(i);
            if (graph != notified) {
                graph.onChunkLoaded(pos);
            }
        }
    }
}
