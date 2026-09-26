package com.wf.wflib.client.journeymap.rail;

import com.wf.wflib.rail.align.AlignEditor;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.rail.align.AlignProblem;
import com.wf.wflib.rail.align.AlignResult;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.RightOfWay;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Locale;

/** Draws the line being edited, in pixels, over JourneyMap's tiles. */
public final class RailMapRenderer {

    /** Half-side of a PI handle, in pixels. */
    private static final int HANDLE_HALF = 3;

    /** Left edge of the readout, in GUI pixels. */
    private static final int LEFT_MARGIN = 6;

    /**
     * Top edge of the readout.
     *
     * <p>Below JourneyMap's logo <em>and</em> below its centred coordinate line. The logo alone was not
     * enough: the coordinate line is centred, so on a narrow window it reaches further left and lands
     * on the same row. Clearing both rows is cheaper than trying to predict where it starts.</p>
     */
    private static final int TOP_MARGIN = 44;

    private static final int LINE_HEIGHT = 10;

    /** Problems listed before the rest are summarised. Three fits without covering the map. */
    private static final int MAX_PROBLEMS = 3;

    /** Width of the ownership band, in pixels. Wider than the line so it reads as ground, not track. */
    private static final float OWNERSHIP_WIDTH = 9.0f;

    /** Width of the hatching over a span that cannot be built on. */
    private static final float BLOCKED_WIDTH = 13.0f;

    /**
     * Ground the surveyor is refused.
     *
     * <p>Deliberately not the red a broken alignment uses. They are different failures: one says the
     * geometry cannot be built, the other says you are not allowed to build it here, and a player who
     * sees one red should not have to work out which. This one is darker and more purple, and it is
     * drawn wider and under the line, so the two read apart even when they coincide.</p>
     */
    private static final int BLOCKED_COLOUR = 0xAA8B2C6F;

    /** Track that is actually down, drawn narrower than the route and over the top of it. */
    private static final float BUILT_WIDTH = 1.0f;
    private static final int BUILT_COLOUR = 0xFFF2F2F2;

    private MapTransform transform;
    private IFullscreen fullscreen;

    /**
     * Style, passed in rather than held here so the colours stay next to the rest of the plugin's
     * appearance instead of being split across two files.
     */
    public record Palette(int line, int lineBad, int leg, int handle, int handleSelected, int handleBorder) {
    }

    /** The transform as of the last frame, which is also what the input handlers read. */
    public MapTransform transform() {
        return this.transform;
    }

    public void update(IFullscreen fullscreen) {
        this.fullscreen = fullscreen;
        this.transform = MapTransform.of(fullscreen);
    }

    /** The map as JourneyMap last handed it to us, for diagnostics. */
    public IFullscreen fullscreen() {
        return this.fullscreen;
    }

    public void draw(GuiGraphics graphics, AlignEditor editor, boolean armed, int hoverIndex,
                     RightOfWay rightOfWay, Palette palette) {
        MapTransform t = this.transform;
        if (t == null) {
            return;
        }
        List<AlignPoint> points = editor.points();
        AlignResult result = editor.result();

        drawLegs(graphics, t, points, palette);
        // Underneath the line, always: the line's own colours say whose railway it is, and that must
        // not be overwritten by whose ground it happens to be crossing.
        drawOwnership(graphics, t, result, rightOfWay);
        drawCentreline(graphics, t, result, palette, editor.coreColour());
        drawBuilt(graphics, t, result, editor.built());
        if (armed) {
            drawHandles(graphics, t, points, editor.selected(), hoverIndex, result, palette);
        }
        drawReadout(graphics, editor, result, armed, rightOfWay);
    }

    /** The straight PI-to-PI legs, dashed, because they are construction rather than track. */
    private void drawLegs(GuiGraphics graphics, MapTransform t, List<AlignPoint> points, Palette palette) {
        for (int i = 0; i < points.size() - 1; i++) {
            AlignPoint a = points.get(i);
            AlignPoint b = points.get(i + 1);
            MapDraw.dashed(graphics, t.toScreenX(a.x()), t.toScreenY(a.z()),
                    t.toScreenX(b.x()), t.toScreenY(b.z()), 1.0f, palette.leg(), 4.0);
        }
    }

    /**
     * The compiled line.
     *
     * <p>Sampled at the zoom's own resolution: there is no point emitting a segment per block when a
     * pixel covers 256 of them, and no point emitting one per 256 blocks when a block is 32 pixels
     * wide. {@link MapTransform#sampleStep()} is that number.</p>
     */
    private void drawCentreline(GuiGraphics graphics, MapTransform t, AlignResult result, Palette palette,
                                int editorColour) {
        if (result.centreline().isEmpty()) {
            return;
        }
        // The surveyor's own colour, so choosing one has an effect before the line is published rather
        // than only after. A line that cannot be built is red regardless: that is not a preference.
        int colour = result.buildable() ? 0xFF000000 | editorColour : palette.lineBad();
        List<AlignElement.Sample> samples = result.centreline().sample(t.sampleStep());
        double[] xy = new double[samples.size() * 2];
        for (int i = 0; i < samples.size(); i++) {
            xy[i * 2] = t.toScreenX(samples.get(i).x());
            xy[i * 2 + 1] = t.toScreenY(samples.get(i).z());
        }
        MapDraw.polyline(graphics, xy, samples.size(), 2.5f, colour);
    }

    private void drawHandles(GuiGraphics graphics, MapTransform t, List<AlignPoint> points, int selected,
                             int hoverIndex, AlignResult result, Palette palette) {
        for (int i = 0; i < points.size(); i++) {
            AlignPoint p = points.get(i);
            AlignProblem problem = result.worstAt(i);
            int fill = i == selected ? palette.handleSelected() : palette.handle();
            if (problem != null && problem.isError()) {
                fill = palette.lineBad();
            }
            int half = i == hoverIndex ? HANDLE_HALF + 1 : HANDLE_HALF;
            MapDraw.handle(graphics, t.toScreenX(p.x()), t.toScreenY(p.z()), half, fill,
                    palette.handleBorder());
        }
    }

    /**
     * Length, point count, problems and any status message.
     *
     * <p>Below JourneyMap's own logo rather than beside it, and clipped to the left half of the screen:
     * the first version started at the top-left corner and had its first two characters hidden behind
     * the logo, while the problem lines ran under JourneyMap's centred coordinate readout. Neither is
     * visible until something is actually drawn there, which is why the offsets are worth stating.</p>
     */
    /**
     * Whose ground each stretch of the route sits on, as a band under the line.
     *
     * <p>A band rather than a recolouring of the line. The line already carries two colours that answer
     * "whose railway is this"; ownership of the ground is a different question with a different answer,
     * and painting it over the first one loses both.</p>
     */
    private void drawOwnership(GuiGraphics graphics, MapTransform t, AlignResult result,
                               RightOfWay rightOfWay) {
        if (rightOfWay == null || rightOfWay.isEmpty() || result.centreline().isEmpty()) {
            return;
        }
        double step = t.sampleStep();
        for (RightOfWay.Span span : rightOfWay.spans()) {
            // Unclaimed ground is not drawn: a band the whole length of the line in neutral grey says
            // nothing and hides the two cases that matter.
            boolean interesting = span.blocked() || !span.ownerName().isEmpty();
            if (!interesting) {
                continue;
            }
            int colour = span.blocked() ? BLOCKED_COLOUR : 0xAA000000 | span.ownerColour();
            float width = span.blocked() ? BLOCKED_WIDTH : OWNERSHIP_WIDTH;
            double previousX = Double.NaN;
            double previousY = Double.NaN;
            for (double at = span.from(); at <= span.to(); at += step) {
                var sample = result.centreline().at(Math.min(at, span.to()));
                double x = t.toScreenX(sample.x());
                double y = t.toScreenY(sample.z());
                if (!Double.isNaN(previousX)) {
                    MapDraw.line(graphics, previousX, previousY, x, y, width, colour);
                }
                previousX = x;
                previousY = y;
            }
        }
    }

    /**
     * The stretches that have track on them, as a second line laid over the first.
     *
     * <p>Over rather than under, and narrower, so a partly built route reads as one line with rails on
     * part of it rather than as two routes. The colour is fixed rather than the surveyor's: this is
     * answering "is there track here", which is not a matter of taste.</p>
     */
    private void drawBuilt(GuiGraphics graphics, MapTransform t, AlignResult result, BuildProgress built) {
        if (built == null || built.isEmpty() || result.centreline().isEmpty()) {
            return;
        }
        double step = t.sampleStep();
        for (BuildProgress.Span span : built.spans()) {
            double previousX = Double.NaN;
            double previousY = Double.NaN;
            for (double at = span.from(); at <= span.to(); at += step) {
                var sample = result.centreline().at(Math.min(at, span.to()));
                double sx = t.toScreenX(sample.x());
                double sy = t.toScreenY(sample.z());
                if (!Double.isNaN(previousX)) {
                    MapDraw.line(graphics, previousX, previousY, sx, sy, BUILT_WIDTH, BUILT_COLOUR);
                }
                previousX = sx;
                previousY = sy;
            }
        }
    }

    private void drawReadout(GuiGraphics graphics, AlignEditor editor, AlignResult result, boolean armed,
                             RightOfWay rightOfWay) {
        var font = Minecraft.getInstance().font;
        int x = LEFT_MARGIN;
        int y = TOP_MARGIN;
        // Below JourneyMap's own furniture there is nothing to collide with, so the readout may use
        // the width. It is still clipped: a problem message is a sentence and the map is not a console.
        int maxWidth = Math.max(120, graphics.guiWidth() - LEFT_MARGIN * 2);

        double length = result.centreline().length();
        String head = String.format(Locale.ROOT, "%s  %s  %s  %d point(s)  %.0f blocks",
                armed ? "SURVEYING" : "survey", editor.status().lowerName(),
                editor.designClass().name().toLowerCase(Locale.ROOT), editor.size(), length);
        graphics.drawString(font, clip(head, maxWidth), x, y, 0xFFFFC24B, true);
        y += LINE_HEIGHT;

        // Only once there is track to report. A line of "0% built" on every draft is noise.
        if (!editor.built().isEmpty()) {
            String built = String.format(Locale.ROOT, "%.0f%% built  (%.0f of %.0f blocks)",
                    editor.built().fractionOf(length) * 100.0, editor.built().builtLength(), length);
            graphics.drawString(font, clip(built, maxWidth), x, y, 0xFF8CE99A, true);
            y += LINE_HEIGHT;
        }

        // Saved state, said plainly: a route that lives on the server should not make anyone wonder.
        if (!editor.mayEdit()) {
            graphics.drawString(font, clip("read only: this route is not yours to change", maxWidth),
                    x, y, 0xFF9AA0A6, true);
            y += LINE_HEIGHT;
        } else if (editor.dirty() && editor.size() >= 2) {
            graphics.drawString(font, clip("saving...", maxWidth), x, y, 0xFF9AA0A6, true);
            y += LINE_HEIGHT;
        }

        String message = editor.message();
        if (!message.isEmpty()) {
            graphics.drawString(font, clip(message, maxWidth), x, y, 0xFF8FD8FF, true);
            y += LINE_HEIGHT;
        }

        if (rightOfWay != null && !rightOfWay.isEmpty()) {
            String line;
            if (rightOfWay.chunksBlocked() > 0) {
                line = String.format(Locale.ROOT,
                        "crosses %d claim(s); %.0f blocks of route you may not build on",
                        rightOfWay.ownersCrossed(), rightOfWay.blockedLength());
            } else if (rightOfWay.ownersCrossed() > 0) {
                line = String.format(Locale.ROOT, "crosses %d claim(s), all of which you may build on",
                        rightOfWay.ownersCrossed());
            } else {
                line = "unclaimed ground the whole way";
            }
            graphics.drawString(font, clip(line, maxWidth), x, y,
                    rightOfWay.chunksBlocked() > 0 ? 0xFFFF4A3D : 0xFF8CE99A, true);
            y += LINE_HEIGHT;
        }

        List<AlignProblem> problems = result.problems();
        int shown = 0;
        for (AlignProblem problem : problems) {
            if (shown >= MAX_PROBLEMS) {
                graphics.drawString(font, "and " + (problems.size() - shown) + " more", x, y, 0xFF9AA0A6, true);
                break;
            }
            String line = "point " + (problem.pointIndex() + 1) + ": " + problem.message();
            graphics.drawString(font, clip(line, maxWidth), x, y,
                    problem.isError() ? 0xFFFF4A3D : 0xFFFFD479, true);
            y += LINE_HEIGHT;
            shown++;
        }
    }

    /** Cut a line to fit, with an ellipsis, rather than let it run under something else. */
    private static String clip(String text, int maxWidth) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }
}
