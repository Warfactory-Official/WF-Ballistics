package com.wf.wfballistics.recon;

import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.recon.snapshot.TargetSnapshot;
import com.wf.wfballistics.recon.source.EntityTargetSource;
import com.wf.wfballistics.recon.source.SensorTargetSource;
import com.wf.wfballistics.recon.source.SimDroneSource;
import com.wf.wfballistics.recon.source.SimGlyphidSource;
import com.wf.wfballistics.recon.source.SimMissileSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The source registry. */
public final class ReconTargets {

    private static final List<TargetSource> SOURCES = new ArrayList<>();

    private ReconTargets() {
    }

    /**
     * Register a place targets live. Called once per launch, from common setup.
     */
    public static void addSource(TargetSource source) {
        SOURCES.add(source);
    }

    /** Install the sources that ship with this mod. */
    public static void bootstrap() {
        addSource(new EntityTargetSource());
        addSource(new SimMissileSource());
        addSource(new SimGlyphidSource());
        addSource(new SimDroneSource());
        addSource(new SensorTargetSource());
    }

    public static int sourceCount() {
        return SOURCES.size();
    }

    /** Everything inside this volume, from every source, deduplicated. */
    public static List<TargetSnapshot> collect(ServerLevel level, AABB volume) {
        WorldThread.assertOn("recon target collection");
        List<TargetSnapshot> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        TargetSink sink = snapshot -> {
            if (seen.add(snapshot.sourceId())) {
                out.add(snapshot);
            }
        };
        for (int i = 0; i < SOURCES.size(); i++) {
            SOURCES.get(i).collect(level, volume, sink);
        }
        return out;
    }

    /** What each source has inside this volume, attributed to the source that produced it. */
    public static List<SourceCensus> census(ServerLevel level, AABB volume) {
        WorldThread.assertOn("recon target census");
        List<SourceCensus> out = new ArrayList<>(SOURCES.size());
        for (int i = 0; i < SOURCES.size(); i++) {
            TargetSource source = SOURCES.get(i);
            List<TargetSnapshot> found = new ArrayList<>();
            source.collect(level, volume, found::add);
            out.add(new SourceCensus(source.getClass().getSimpleName(), found));
        }
        return out;
    }

    /**
     * One source's answer, with the source named so a report can say who produced what.
     */
    public record SourceCensus(String source, List<TargetSnapshot> found) {
    }
}
