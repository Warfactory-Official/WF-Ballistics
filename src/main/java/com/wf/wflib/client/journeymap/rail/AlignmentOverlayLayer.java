package com.wf.wflib.client.journeymap.rail;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.rail.PublishedAlignments;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.AlignmentView;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.LineColour;
import com.wf.wflib.rail.align.RouteStatus;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.model.TextProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws published lines on both maps, in the owner's colours.
 *
 * <p>Two colours carrying two different questions. The <b>outline</b> is the owning faction's colour,
 * so "whose line is this" never depends on the taste of whoever drew it. The <b>core</b> is the
 * surveyor's own choice, which is what tells two of their own lines apart. One overlay carries both:
 * the polygon's fill is the core and its stroke is the outline.</p>
 *
 * <p>Nothing here filters by faction. Whether a line reaches this client at all was decided on the
 * server, which is the only place it can be decided, so anything in {@link PublishedAlignments} is
 * already something this player is allowed to see.</p>
 */
public final class AlignmentOverlayLayer implements PublishedAlignments.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger("wflib/rail-overlay");

    private static final String GROUP = "WF Rail: lines";

    /**
     * Routes that are still being thought about.
     *
     * <p>Their own group because a faction planning a network has more drafts than railways, and a map
     * covered in proposals is a map nobody reads. Allies never see these at all; this is for the faction
     * that owns them.</p>
     */
    private static final String GROUP_DRAFT = "WF Rail: drafts";

    /**
     * Lines an operator is seeing only because they are one.
     *
     * <p>Its own JourneyMap overlay group so it can be switched off there. An admin who is also a
     * player otherwise gets every faction's railway on their minimap permanently, with no way to see
     * only their own, which is the fastest way to have them turn the whole layer off.</p>
     */
    private static final String GROUP_OPERATOR = "WF Rail: lines (operator)";

    /** Operator-only routes are drawn fainter, so a glance separates them from your own. */
    private static final float OPERATOR_FADE = 0.55f;

    /**
     * Widths to publish each line at, and the zoom range each is for.
     *
     * <p>A {@code PolygonOverlay} is in world coordinates, so a fixed width vanishes when zoomed out
     * and swells to a stripe when zoomed in. Rather than rebuild on every zoom change, the same line
     * goes out once per band and JourneyMap shows whichever applies. Below the lowest band the polygon
     * is thinner than a pixel and only the stroke survives, which is the right thing to lose last: the
     * faction colour is what matters at world scale.</p>
     */
    private static final Band[] BANDS = {
            new Band(2, 128, 32.0),
            new Band(129, 1024, 8.0),
            new Band(1025, 16384, 2.0),
    };

    private record Band(int minZoom, int maxZoom, double halfWidth) {
    }

    @Nullable
    private IClientAPI api;
    private final List<PolygonOverlay> shown = new ArrayList<>();

    public void attach(IClientAPI clientApi) {
        this.api = clientApi;
        // Last, so the replay inside setListener has a live API to draw with.
        PublishedAlignments.addListener(this);
    }

    @Override
    public void onAlignments(ResourceKey<Level> dimension, List<AlignmentView> alignments) {
        Minecraft.getInstance().execute(() -> rebuild(dimension, alignments));
    }

    private void rebuild(ResourceKey<Level> dimension, List<AlignmentView> alignments) {
        IClientAPI jm = this.api;
        if (jm == null) {
            return;
        }
        clear(jm);
        if (alignments.isEmpty() || !jm.playerAccepts(WFLib.MODID, DisplayType.Polygon)) {
            return;
        }
        for (AlignmentView alignment : alignments) {
            Centreline centreline = alignment.compile().centreline();
            double length = centreline.length();
            if (length <= 0.0) {
                continue;
            }
            List<Run> runs = runs(alignment.built(), length);
            for (Band band : BANDS) {
                for (int i = 0; i < runs.size(); i++) {
                    Run run = runs.get(i);
                    List<AlignElement.Sample> samples = sample(centreline, run.from(), run.to(), 8.0);
                    MapPolygon ribbon = ribbon(samples, band.halfWidth());
                    if (ribbon != null) {
                        show(jm, overlay(dimension, alignment, ribbon, band, run.built(), length, i == 0));
                    }
                }
            }
        }
    }

    /** One stretch of a route that is drawn as a piece, because all of it is built or none of it is. */
    private record Run(double from, double to, boolean built) {
    }

    /**
     * Split a route where the track stops.
     *
     * <p>A route half built is two different statements and has to be drawn as two things. An unbuilt
     * route is one run, which is the ordinary case and costs nothing extra.</p>
     */
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

    /** Sample one stretch of a centreline, by chainage rather than from the start. */
    private static List<AlignElement.Sample> sample(Centreline centreline, double from, double to,
                                                    double step) {
        List<AlignElement.Sample> out = new ArrayList<>();
        int parts = Math.max(1, (int) Math.ceil((to - from) / Math.max(0.5, step)));
        for (int i = 0; i <= parts; i++) {
            out.add(centreline.at(from + (to - from) * i / parts));
        }
        return out;
    }

    private PolygonOverlay overlay(ResourceKey<Level> dimension, AlignmentView alignment,
                                   MapPolygon ribbon, Band band, boolean built, double length,
                                   boolean named) {
        boolean viaOp = alignment.viaOperator();
        RouteStatus status = alignment.status();
        // Track that is down is drawn at full weight whatever the rest of the route is: the question
        // being answered there is "is there track here", and it does not get fainter because the other
        // half is still a plan.
        float fill = built ? RouteStatus.BUILT.fillOpacity() : status.fillOpacity();
        float stroke = built ? RouteStatus.BUILT.strokeOpacity() : status.strokeOpacity();
        if (viaOp) {
            fill *= OPERATOR_FADE;
            stroke *= OPERATOR_FADE;
        }
        ShapeProperties props = new ShapeProperties()
                .setStrokeWidth(2.0f)
                .setStrokeColor(alignment.outlineColour())
                .setStrokeOpacity(stroke)
                .setFillColor(alignment.coreColour())
                .setFillOpacity(fill);

        String owner = alignment.ownerName().isEmpty() ? "unowned" : alignment.ownerName();
        double builtLength = alignment.built().builtLength();
        String newline = System.lineSeparator();
        String editor = alignment.lastEditorName().isEmpty() ? ""
                : newline + "last changed by " + alignment.lastEditorName();
        String asOperator = viaOp ? newline + "shown to you as an operator" : "";
        PolygonOverlay overlay = new PolygonOverlay(WFLib.MODID, dimension, props, ribbon);
        overlay.setOverlayGroupName(viaOp ? GROUP_OPERATOR
                        : status == RouteStatus.DRAFT ? GROUP_DRAFT : GROUP)
                // Only one piece carries the label. A route is split wherever its track stops, and a
                // line blown apart in a dozen places would otherwise write its own name a dozen times.
                .setLabel(named ? alignment.name() : "")
                .setTitle(String.format(Locale.ROOT,
                        "%s%n%s, %s class%n%s, %.0f%% built (%.0f of %.0f blocks)%ncore %s%s%s",
                        alignment.name().isEmpty() ? "unnamed route" : alignment.name(),
                        owner, alignment.designClass().name().toLowerCase(Locale.ROOT),
                        status.lowerName(), alignment.built().fractionOf(length) * 100.0,
                        builtLength, length,
                        LineColour.nearest(alignment.coreColour()).lowerName(), editor, asOperator))
                .setMinZoom(band.minZoom())
                .setMaxZoom(band.maxZoom())
                // Above the recon net's links, which are cosmetic, and above claim fills.
                .setDisplayOrder(built ? 21 : 20)
                .setTextProperties(new TextProperties()
                        .setColor(alignment.coreColour())
                        .setOpacity(1.0f)
                        .setMinZoom(256)
                        .setFontShadow(true));
        return overlay;
    }

    /**
     * A ribbon along the centreline: out one side, back the other.
     *
     * <p>Wound the same way as the recon net's link quads, which is the winding JourneyMap treats as a
     * hull rather than a hole.</p>
     */
    @Nullable
    private static MapPolygon ribbon(List<AlignElement.Sample> centreline, double halfWidth) {
        int count = centreline.size();
        if (count < 2) {
            return null;
        }
        List<BlockPos> points = new ArrayList<>(count * 2);
        int y = Minecraft.getInstance().level == null ? 70 : 70;
        for (int i = 0; i < count; i++) {
            points.add(offset(centreline.get(i), halfWidth, y));
        }
        for (int i = count - 1; i >= 0; i--) {
            points.add(offset(centreline.get(i), -halfWidth, y));
        }
        return new MapPolygon(points);
    }

    private static BlockPos offset(AlignElement.Sample sample, double distance, int y) {
        // The heading's left-hand normal, so a positive distance is consistently one side of the line.
        double nx = -Math.sin(sample.heading());
        double nz = Math.cos(sample.heading());
        return BlockPos.containing(sample.x() + nx * distance, y, sample.z() + nz * distance);
    }

    private void show(IClientAPI jm, PolygonOverlay overlay) {
        try {
            jm.show(overlay);
            this.shown.add(overlay);
        } catch (Exception e) {
            // One line failing must not lose the rest of the network, and this is a map, not the world.
            LOGGER.warn("could not show rail overlay {}", overlay.getId(), e);
        }
    }

    private void clear(IClientAPI jm) {
        for (PolygonOverlay overlay : this.shown) {
            jm.remove(overlay);
        }
        this.shown.clear();
    }
}
