package com.wf.wflib.ui;

import com.wf.wflib.ui.widget.Align;
import com.wf.wflib.ui.widget.Anchor;
import com.wf.wflib.ui.widget.Arc;
import com.wf.wflib.ui.widget.Bar;
import com.wf.wflib.ui.widget.Box;
import com.wf.wflib.ui.widget.Flow;
import com.wf.wflib.ui.widget.Line;
import com.wf.wflib.ui.widget.Place;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Poly;
import com.wf.wflib.ui.widget.Rect;
import com.wf.wflib.ui.widget.Repeat;
import com.wf.wflib.ui.widget.Sized;
import com.wf.wflib.ui.widget.Stack;
import com.wf.wflib.ui.widget.Text;
import com.wf.wflib.ui.widget.Transform;
import com.wf.wflib.ui.widget.When;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Shorthand constructors for layouts built in Java; every argument string is an expression or template. */
public final class Ui {
    private Ui() {
    }

    public static UiLayout layout(ResourceLocation id, WidgetDef root) {
        return new UiLayout(id, root);
    }

    public static WidgetDef stack(WidgetDef... children) {
        return new Stack(List.of(children));
    }

    public static WidgetDef row(float gap, WidgetDef... children) {
        return new Flow(false, gap, Align.START, List.of(children));
    }

    public static WidgetDef row(float gap, Align align, WidgetDef... children) {
        return new Flow(false, gap, align, List.of(children));
    }

    public static WidgetDef column(float gap, WidgetDef... children) {
        return new Flow(true, gap, Align.START, List.of(children));
    }

    public static WidgetDef column(float gap, Align align, WidgetDef... children) {
        return new Flow(true, gap, align, List.of(children));
    }

    public static WidgetDef anchor(Point at, float margin, WidgetDef child) {
        return new Anchor(at, margin, "0", "0", child);
    }

    public static WidgetDef anchor(Point at, float margin, String dx, String dy, WidgetDef child) {
        return new Anchor(at, margin, dx, dy, child);
    }

    /** Child's {@code pivot} at ({@code x}, {@code y}) of the parent box; {@code ui.width} etc. available. */
    public static WidgetDef place(String x, String y, Point pivot, WidgetDef child) {
        return new Place(x, y, pivot, child);
    }

    /** Child's top-left at ({@code x}, {@code y}). */
    public static WidgetDef at(String x, String y, WidgetDef child) {
        return new Place(x, y, Point.TOP_LEFT, child);
    }

    public static WidgetDef text(String template, String color) {
        return new Text(template, color, false, "1");
    }

    public static WidgetDef shadowed(String template, String color) {
        return new Text(template, color, true, "1");
    }

    public static WidgetDef text(String template, String color, boolean shadow, String scale) {
        return new Text(template, color, shadow, scale);
    }

    public static WidgetDef when(String condition, WidgetDef then) {
        return new When(condition, then, null);
    }

    public static WidgetDef when(String condition, WidgetDef then, WidgetDef otherwise) {
        return new When(condition, then, otherwise);
    }

    public static WidgetDef rect(String w, String h, String color) {
        return new Rect(w, h, color, 0, 0);
    }

    public static WidgetDef rect(float w, float h, String color) {
        return new Rect(Float.toString(w), Float.toString(h), color, 0, 0);
    }

    public static WidgetDef spacer(float w, float h) {
        return new Sized(Float.toString(w), Float.toString(h), Point.TOP_LEFT, false, null);
    }

    public static WidgetDef sized(float w, float h, WidgetDef child) {
        return new Sized(Float.toString(w), Float.toString(h), Point.TOP_LEFT, false, child);
    }

    public static WidgetDef clip(float w, float h, WidgetDef child) {
        return new Sized(Float.toString(w), Float.toString(h), Point.TOP_LEFT, true, child);
    }

    public static WidgetDef panel(String background, float pad, WidgetDef child) {
        return new Box(background, "0", 0, 0, pad, pad, 0, 0, Point.TOP_LEFT, child);
    }

    public static WidgetDef bar(float w, float h, String value, String fill, String back) {
        return new Bar(w, h, value, fill, back, "0", Bar.Direction.RIGHT, false, "0/0", "0");
    }

    public static WidgetDef line(String x0, String y0, String x1, String y1, String width, String color) {
        return new Line(x0, y0, x1, y1, width, color);
    }

    public static WidgetDef arc(String cx, String cy, String radius, String thickness, String start, String sweep,
                                String color) {
        return new Arc(cx, cy, radius, thickness, start, sweep, color);
    }

    public static WidgetDef poly(String color, float... points) {
        return new Poly(points, color);
    }

    public static WidgetDef rotate(String degrees, Point pivot, WidgetDef child) {
        return new Transform("0", "0", degrees, "1", pivot, child);
    }

    public static WidgetDef scale(String scale, WidgetDef child) {
        return new Transform("0", "0", "0", scale, Point.TOP_LEFT, child);
    }

    /** Child in host units (GUI pixels for the HUD) inside a relative layout; its own expressions still read layout units. */
    public static WidgetDef absolute(WidgetDef child) {
        return new Transform("0", "0", "0", "1gu", Point.TOP_LEFT, child);
    }

    public static WidgetDef offset(String dx, String dy, WidgetDef child) {
        return new Transform(dx, dy, "0", "1", Point.TOP_LEFT, child);
    }

    public static WidgetDef grid(String items, int columns, float gap, WidgetDef child) {
        return new Repeat(items, false, gap, Align.START, 0, columns, child);
    }

    public static WidgetDef repeat(String items, boolean vertical, float gap, WidgetDef child) {
        return new Repeat(items, vertical, gap, Align.START, 0, 0, child);
    }
}
