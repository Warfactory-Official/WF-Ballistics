package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import net.minecraft.resources.ResourceLocation;

/**
 * Texture region (u, v normalised) stretched to {@code w x h}, tinted, rotated {@code rotate}
 * degrees about its centre.
 */
public record Image(ResourceLocation texture, float w, float h, float u0, float v0, float u1, float v1, String tint,
                    String rotate) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        ColorProp colour = binder.color(tint);
        NumProp rot = binder.num(rotate);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = w;
                height = h;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                int argb = colour.get(ev);
                if ((argb >>> 24) == 0) {
                    return;
                }
                Canvas canvas = ev.canvas;
                double degrees = rot.get(ev);
                if (degrees == 0 || Double.isNaN(degrees)) {
                    canvas.sprite(texture, x, y, x + w, y + h, u0, v0, u1, v1, argb);
                    return;
                }
                canvas.push().translate(x + w * 0.5f, y + h * 0.5f).rotate((float) Math.toRadians(degrees));
                canvas.sprite(texture, -w * 0.5f, -h * 0.5f, w * 0.5f, h * 0.5f, u0, v0, u1, v1, argb);
                canvas.pop();
            }
        };
    }
}
