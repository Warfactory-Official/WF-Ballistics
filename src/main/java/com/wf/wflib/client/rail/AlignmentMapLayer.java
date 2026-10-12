package com.wf.wflib.client.rail;

import com.wf.wflib.client.map.MapPoint;
import com.wf.wflib.client.map.MapShape;
import com.wf.wflib.client.map.MapStyle;
import com.wf.wflib.client.map.WFMaps;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.AlignmentView;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.LineColour;
import com.wf.wflib.rail.align.RouteStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Published lines: outline (stroke) = owning faction's colour, core (fill) = surveyor's choice. No faction
 * filter here: the server decided what reaches this client.
 */
public final class AlignmentMapLayer implements PublishedAlignments.Listener {

    private static final String LAYER = "wflib:rail";

    private static final String GROUP = "WF Rail: lines";
    /** Own group: a faction planning a network has more drafts than railways. Allies never get drafts. */
    private static final String GROUP_DRAFT = "WF Rail: drafts";
    /** Own group so an admin-player can hide every other faction's railway. */
    private static final String GROUP_OPERATOR = "WF Rail: lines (operator)";

    private static final float OPERATOR_FADE = 0.55f;
    private static final float WIDTH_PIXELS = 6.0f;
    private static final int Y = 70;

    public static void register() {
        PublishedAlignments.addListener(new AlignmentMapLayer());
    }

    @Override
    public void onAlignments(ResourceKey<Level> dimension, List<AlignmentView> alignments) {
        List<MapShape> shapes = new ArrayList<>();
        for (AlignmentView alignment : alignments) {
            Centreline centreline = alignment.compile().centreline();
            double length = centreline.length();
            if (length <= 0.0) {
                continue;
            }
            List<Run> runs = runs(alignment.built(), length);
            for (int i = 0; i < runs.size(); i++) {
                Run run = runs.get(i);
                List<MapPoint> path = new ArrayList<>();
                for (AlignElement.Sample sample : sample(centreline, run.from(), run.to(), 8.0)) {
                    path.add(new MapPoint(sample.x(), sample.z()));
                }
                // One labelled piece: a route split wherever its track stops must not repeat its name.
                shapes.add(line(alignment, path, run.built(), length, i == 0));
            }
        }
        WFMaps.publish(LAYER, dimension, shapes);
    }

    private record Run(double from, double to, boolean built) {
    }

    private static List<Run> runs(BuildProgress built, double length) {
        List<Run> out = new ArrayList<>();
        if (built == null || built.isEmpty()) {
            out.add(new Run(0.0, length, false));
            return out;
        }
        for (BuildProgress.Span span : built.spans()) {
            out.add(new Run(span.from(), Math.min(span.to(), length), true));
        }
        for (BuildProgress.Span gap : built.gaps(length)) {
            out.add(new Run(gap.from(), gap.to(), false));
        }
        out.removeIf(run -> run.to() - run.from() < 1.0);
        return out;
    }

    private static List<AlignElement.Sample> sample(Centreline centreline, double from, double to, double step) {
        List<AlignElement.Sample> out = new ArrayList<>();
        int parts = Math.max(1, (int) Math.ceil((to - from) / Math.max(0.5, step)));
        for (int i = 0; i <= parts; i++) {
            out.add(centreline.at(from + (to - from) * i / parts));
        }
        return out;
    }

    private static MapShape line(AlignmentView alignment, List<MapPoint> path, boolean built, double length,
                                 boolean named) {
        boolean viaOp = alignment.viaOperator();
        RouteStatus status = alignment.status();
        // Laid track at full weight whatever the route's status: the question there is "is there track here".
        float fill = built ? RouteStatus.BUILT.fillOpacity() : status.fillOpacity();
        float stroke = built ? RouteStatus.BUILT.strokeOpacity() : status.strokeOpacity();
        if (viaOp) {
            fill *= OPERATOR_FADE;
            stroke *= OPERATOR_FADE;
        }
        String owner = alignment.ownerName().isEmpty() ? "unowned" : alignment.ownerName();
        String editor = alignment.lastEditorName().isEmpty() ? "" : "\nlast changed by " + alignment.lastEditorName();
        String title = String.format(Locale.ROOT, "%s%n%s, %s class%n%s, %.0f%% built (%.0f of %.0f blocks)%ncore %s%s%s",
                alignment.name().isEmpty() ? "unnamed route" : alignment.name(),
                owner, alignment.designClass().name().toLowerCase(Locale.ROOT),
                status.lowerName(), alignment.built().fractionOf(length) * 100.0,
                alignment.built().builtLength(), length,
                LineColour.nearest(alignment.coreColour()).lowerName(), editor,
                viaOp ? "\nshown to you as an operator" : "");
        String group = viaOp ? GROUP_OPERATOR : status == RouteStatus.DRAFT ? GROUP_DRAFT : GROUP;
        // Above recon links (cosmetic) and claim fills.
        MapShape.Look look = MapShape.Look.of(group, built ? 21 : 20).title(title)
                .label(named ? alignment.name() : "", alignment.coreColour());
        return new MapShape.Line(path, Y, WIDTH_PIXELS,
                new MapStyle(alignment.outlineColour(), stroke, 2.0f, alignment.coreColour(), fill), look);
    }
}
