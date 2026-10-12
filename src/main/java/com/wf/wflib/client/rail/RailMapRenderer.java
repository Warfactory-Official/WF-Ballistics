package com.wf.wflib.client.rail;

import com.wf.wflib.rail.align.AlignEditor;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.rail.align.AlignProblem;
import com.wf.wflib.rail.align.AlignResult;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.RightOfWay;
import com.wf.wflib.client.map.MapDraw;
import com.wf.wflib.client.map.MapView;
import net.minecraft.client.Minecraft;
import com.wf.gemrender.ui.Canvas;

import java.util.List;
import java.util.Locale;

/** Draws the line being edited, in pixels, over a fullscreen map. */
public final class RailMapRenderer {

    /** Pixels. */
    private static final int HANDLE_HALF = 3;

    /** GUI pixels. */
    private static final int LEFT_MARGIN = 6;

    /** Below JourneyMap's logo and its centred coordinate line (reaches this far left on a narrow window). */
    private static final int TOP_MARGIN = 44;

    private static final int LINE_HEIGHT = 10;

    private static final int MAX_PROBLEMS = 3;

    /** Pixels; wider than the line => reads as ground. */
    private static final float OWNERSHIP_WIDTH = 9.0f;

    private static final float BLOCKED_WIDTH = 13.0f;

    /** Refused ground != unbuildable geometry red: darker purple, wider, under the line. */
    private static final int BLOCKED_COLOUR = 0xAA8B2C6F;

    /** Laid track: narrower, over the route. */
    private static final float BUILT_WIDTH = 1.0f;
    private static final int BUILT_COLOUR = 0xFFF2F2F2;

    public record Palette(int line, int lineBad, int leg, int handle, int handleSelected, int handleBorder) {
    }

    public void draw(Canvas graphics, MapView t, AlignEditor editor, boolean armed, int hoverIndex,
                     RightOfWay rightOfWay, Palette palette) {
        List<AlignPoint> points = editor.points();
        AlignResult result = editor.result();

        drawLegs(graphics, t, points, palette);
        // Under the line: its colours say whose railway; ground ownership must not overwrite that.
        drawOwnership(graphics, t, result, rightOfWay);
        drawCentreline(graphics, t, result, palette, editor.coreColour());
        drawBuilt(graphics, t, result, editor.built());
        if (armed) {
            drawHandles(graphics, t, points, editor.selected(), hoverIndex, result, palette);
        }
        drawReadout(graphics, editor, result, armed, rightOfWay);
    }

    private void drawLegs(Canvas graphics, MapView t, List<AlignPoint> points, Palette palette) {
        for (int i = 0; i < points.size() - 1; i++) {
            AlignPoint a = points.get(i);
            AlignPoint b = points.get(i + 1);
            MapDraw.dashed(graphics, t.toScreenX(a.x()), t.toScreenY(a.z()),
                    t.toScreenX(b.x()), t.toScreenY(b.z()), 1.0f, palette.leg(), 4.0);
        }
    }

    private void drawCentreline(Canvas graphics, MapView t, AlignResult result, Palette palette,
                                int editorColour) {
        if (result.centreline().isEmpty()) {
            return;
        }
        // Surveyor's colour before publishing; unbuildable => red regardless.
        int colour = result.buildable() ? 0xFF000000 | editorColour : palette.lineBad();
        List<AlignElement.Sample> samples = result.centreline().sample(t.sampleStep());
        double[] xy = new double[samples.size() * 2];
        for (int i = 0; i < samples.size(); i++) {
            xy[i * 2] = t.toScreenX(samples.get(i).x());
            xy[i * 2 + 1] = t.toScreenY(samples.get(i).z());
        }
        MapDraw.polyline(graphics, xy, samples.size(), 2.5f, colour);
    }

    private void drawHandles(Canvas graphics, MapView t, List<AlignPoint> points, int selected,
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

    /** Band under the line, not a recolour: the line's two colours already answer another question. */
    private void drawOwnership(Canvas graphics, MapView t, AlignResult result,
                               RightOfWay rightOfWay) {
        if (rightOfWay == null || rightOfWay.isEmpty() || result.centreline().isEmpty()) {
            return;
        }
        double step = t.sampleStep();
        for (RightOfWay.Span span : rightOfWay.spans()) {
            // Unclaimed: not drawn (would hide the two cases that matter).
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

    /** Fixed colour, narrower, over: a partly built route reads as one line. */
    private void drawBuilt(Canvas graphics, MapView t, AlignResult result, BuildProgress built) {
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

    private void drawReadout(Canvas graphics, AlignEditor editor, AlignResult result, boolean armed,
                             RightOfWay rightOfWay) {
        var font = Minecraft.getInstance().font;
        int x = LEFT_MARGIN;
        int y = TOP_MARGIN;

        int maxWidth = Math.max(120, (int) graphics.width() - LEFT_MARGIN * 2);

        double length = result.centreline().length();
        String head = String.format(Locale.ROOT, "%s  %s  %s  %d point(s)  %.0f blocks",
                armed ? "SURVEYING" : "survey", editor.status().lowerName(),
                editor.designClass().name().toLowerCase(Locale.ROOT), editor.size(), length);
        graphics.text(clip(head, maxWidth), x, y, 0xFFFFC24B, true);
        y += LINE_HEIGHT;

        if (!editor.built().isEmpty()) {
            String built = String.format(Locale.ROOT, "%.0f%% built  (%.0f of %.0f blocks)",
                    editor.built().fractionOf(length) * 100.0, editor.built().builtLength(), length);
            graphics.text(clip(built, maxWidth), x, y, 0xFF8CE99A, true);
            y += LINE_HEIGHT;
        }

        if (!editor.mayEdit()) {
            graphics.text(clip("read only: this route is not yours to change", maxWidth), x, y, 0xFF9AA0A6, true);
            y += LINE_HEIGHT;
        } else if (editor.dirty() && editor.size() >= 2) {
            graphics.text(clip("saving...", maxWidth), x, y, 0xFF9AA0A6, true);
            y += LINE_HEIGHT;
        }

        String message = editor.message();
        if (!message.isEmpty()) {
            graphics.text(clip(message, maxWidth), x, y, 0xFF8FD8FF, true);
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
            graphics.text(clip(line, maxWidth), x, y,
                    rightOfWay.chunksBlocked() > 0 ? 0xFFFF4A3D : 0xFF8CE99A, true);
            y += LINE_HEIGHT;
        }

        List<AlignProblem> problems = result.problems();
        int shown = 0;
        for (AlignProblem problem : problems) {
            if (shown >= MAX_PROBLEMS) {
                graphics.text("and " + (problems.size() - shown) + " more", x, y, 0xFF9AA0A6, true);
                break;
            }
            String line = "point " + (problem.pointIndex() + 1) + ": " + problem.message();
            graphics.text(clip(line, maxWidth), x, y,
                    problem.isError() ? 0xFFFF4A3D : 0xFFFFD479, true);
            y += LINE_HEIGHT;
            shown++;
        }
    }

    private static String clip(String text, int maxWidth) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }
}
