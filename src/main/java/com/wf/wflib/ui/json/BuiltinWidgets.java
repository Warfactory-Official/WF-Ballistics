package com.wf.wflib.ui.json;

import com.wf.wflib.WFLib;
import com.wf.wflib.ui.widget.Align;
import com.wf.wflib.ui.widget.Anchor;
import com.wf.wflib.ui.widget.Arc;
import com.wf.wflib.ui.widget.Bar;
import com.wf.wflib.ui.widget.Box;
import com.wf.wflib.ui.widget.Dial;
import com.wf.wflib.ui.widget.Feed;
import com.wf.wflib.ui.widget.Flow;
import com.wf.wflib.ui.widget.Image;
import com.wf.wflib.ui.widget.Ladder;
import com.wf.wflib.ui.widget.Line;
import com.wf.wflib.ui.widget.Place;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Poly;
import com.wf.wflib.ui.widget.Rect;
import com.wf.wflib.ui.widget.Repeat;
import com.wf.wflib.ui.widget.Sized;
import com.wf.wflib.ui.widget.Stack;
import com.wf.wflib.ui.widget.Tape;
import com.wf.wflib.ui.widget.Text;
import com.wf.wflib.ui.widget.Transform;
import com.wf.wflib.ui.widget.When;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON readers for wflib's widgets; property names = record components in snake_case. */
final class BuiltinWidgets {
    private static final String WHITE = "#ffffffff";

    private BuiltinWidgets() {
    }

    static void register() {
        add("stack", p -> new Stack(p.widgets("children")));
        add("row", p -> flow(p, false));
        add("column", p -> flow(p, true));
        add("anchor", p -> new Anchor(p.choice("at", Point.class, Point.TOP_LEFT), p.number("margin", 0),
                p.expr("dx", "0"), p.expr("dy", "0"), p.widget("child")));
        add("place", p -> new Place(p.expr("x", "0"), p.expr("y", "0"), p.choice("pivot", Point.class, Point.TOP_LEFT),
                p.widget("child")));
        add("text", p -> new Text(p.template("text"), p.expr("color", WHITE), p.flag("shadow", false),
                p.expr("scale", "1"), p.expr("max_width", "0")));
        add("when", p -> new When(p.expr("if"), p.widget("then"), p.widgetOrNull("else")));
        add("rect", p -> new Rect(p.expr("w"), p.expr("h"), p.expr("color", WHITE), p.number("radius", 0),
                p.number("stroke", 0)));
        add("sized", p -> new Sized(p.expr("w"), p.expr("h"), p.choice("align", Point.class, Point.TOP_LEFT),
                p.flag("clip", false), p.widgetOrNull("child")));
        add("box", p -> {
            float pad = p.number("pad", 0);
            return new Box(p.expr("background", "0"), p.expr("border", "0"), p.number("border_width", 1),
                    p.number("radius", 0), p.number("pad_x", pad), p.number("pad_y", pad), p.number("min_width", 0),
                    p.number("min_height", 0), p.choice("align", Point.class, Point.TOP_LEFT), p.widgetOrNull("child"));
        });
        add("bar", p -> new Bar(p.number("w"), p.number("h"), p.expr("value"), p.expr("fill", WHITE),
                p.expr("back", "0"), p.expr("border", "0"), p.choice("direction", Bar.Direction.class, Bar.Direction.RIGHT),
                p.flag("bipolar", false), p.expr("marker", "0/0"), p.expr("marker_color", "0"),
                p.integer("divisions", 0), p.expr("tick_color", "0"), p.number("inset", 0),
                p.number("marker_overhang", 0)));
        add("line", p -> new Line(p.expr("x0"), p.expr("y0"), p.expr("x1"), p.expr("y1"), p.expr("width", "1"),
                p.expr("color", WHITE)));
        add("arc", p -> new Arc(p.expr("cx", "0"), p.expr("cy", "0"), p.expr("radius"), p.expr("thickness", "0"),
                p.expr("start", "0"), p.expr("sweep", "360"), p.expr("color", WHITE)));
        add("poly", p -> {
            float[] points = p.numbers("points", null);
            if (points == null || points.length < 6 || points.length % 2 != 0) {
                throw p.error("points", "needs x, y pairs, at least 3");
            }
            return new Poly(points, p.expr("color", WHITE));
        });
        add("transform", p -> new Transform(p.expr("dx", "0"), p.expr("dy", "0"), p.expr("rotate", "0"),
                p.expr("scale", "1"), p.choice("pivot", Point.class, Point.TOP_LEFT), p.widget("child")));
        add("absolute", p -> new Transform("0", "0", "0", "1gu", Point.TOP_LEFT, p.widget("child")));
        add("repeat", p -> new Repeat(p.expr("items"), p.flag("vertical", false), p.number("gap", 0),
                p.choice("align", Align.class, Align.START), p.integer("max", 0), p.integer("columns", 0),
                p.widget("child")));
        add("tape", BuiltinWidgets::tape);
        add("ladder", p -> new Ladder(p.expr("pitch"), p.number("ppd"), p.number("step"), p.number("range"),
                p.integer("dashes", 1), p.number("dash"), p.number("dash_gap", 0), p.number("centre_gap", 0),
                p.number("thickness", 1), p.number("label_offset", 0), p.expr("color", WHITE)));
        add("image", p -> {
            float[] uv = p.numbers("uv", new float[]{0, 0, 1, 1});
            if (uv.length != 4) {
                throw p.error("uv", "needs [u0, v0, u1, v1]");
            }
            return new Image(p.id("texture"), p.number("w"), p.number("h"), uv[0], uv[1], uv[2], uv[3],
                    p.expr("tint", WHITE), p.expr("rotate", "0"));
        });
        add("feed", p -> new Feed(p.expr("feed"), p.expr("w"), p.expr("h")));
        add("dial", BuiltinWidgets::dial);
    }

    private static void add(String name, WidgetReader reader) {
        UiWidgetTypes.register(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, name), reader);
    }

    private static Flow flow(JsonProps p, boolean vertical) {
        return new Flow(vertical, p.number("gap", 0), p.choice("align", Align.class, Align.START), p.widgets("children"));
    }

    private static Tape tape(JsonProps p) {
        float[] wrap = p.numbers("wrap", new float[]{Float.NaN, Float.NaN});
        if (wrap.length != 2) {
            throw p.error("wrap", "needs [lo, hi]");
        }
        Map<Float, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : p.strings("names").entrySet()) {
            try {
                names.put(Float.parseFloat(e.getKey()), e.getValue());
            } catch (NumberFormatException ex) {
                throw p.error("names/" + e.getKey(), "key needs to be a number");
            }
        }
        String color = p.expr("color", WHITE);
        String labelColor = p.expr("label_color", color);
        return new Tape(p.expr("value"), p.flag("vertical", false), p.flag("flip", false), p.flag("descending", false),
                p.number("ppu"), p.number("step"), p.integer("major_every", 0), p.expr("extent"),
                p.number("minor_length"), p.number("major_length"), p.number("thickness", 1), p.flag("labels", false),
                p.number("label_offset", 0), p.integer("label_decimals", 0), wrap[0], wrap[1], names, color,
                labelColor, p.number("label_scale", 1), p.integer("label_pad", 0), p.expr("name_color", labelColor),
                p.expr("major_color", color));
    }

    private static Dial dial(JsonProps p) {
        float dot = p.number("dot", 1);
        List<Dial.Needle> needles = p.objects("needles", n -> new Dial.Needle(n.expr("value"), n.number("inner"),
                n.number("outer"), n.number("size", dot), n.expr("color", WHITE)));
        String color = p.expr("color", WHITE);
        return new Dial(p.number("radius"), p.number("from", 0), p.number("to", 90), dot, p.number("minor_step", 0),
                p.expr("minor_color", color), p.number("major_step", 0), p.number("major_length", 0),
                p.expr("major_color", color), p.number("accent_step", 0), p.expr("accent_color", color),
                p.expr("band_from", "0/0"), p.expr("band_to", "0/0"), p.number("band_offset", 0),
                p.number("band_size", dot), p.expr("band_color", "0"), needles);
    }
}
