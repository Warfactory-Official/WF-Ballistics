package com.wf.wflib.ui;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.input.UiInput;
import net.minecraft.client.gui.GuiGraphics;

/** A bound layout with its own values, state and widget tree. Render thread only. */
public final class UiInstance {
    private final LayoutPlan plan;
    private final Widget root;
    private final UiEval ev;
    private UiContext context = UiContext.EMPTY;
    private double lastTime = Double.NaN;
    private int generation = UiLayouts.generation();

    UiInstance(LayoutPlan plan, Widget root, UiEval ev) {
        this.plan = plan;
        this.root = root;
        this.ev = ev;
    }

    public UiLayout layout() {
        return plan.layout;
    }

    /** False once {@link UiLayouts} resolves this id to another layout (resource reload); re-instantiate then. */
    public boolean current() {
        int now = UiLayouts.generation();
        if (generation == now) {
            return true;
        }
        if (UiLayouts.find(plan.layout.id()) != plan.layout) {
            return false;
        }
        generation = now;
        return true;
    }

    /** Enables pointer input; host feeds {@link UiInput#pointer} in the render box's units. */
    public UiInput input() {
        if (ev.input == null) {
            ev.input = new UiInput();
        }
        return ev.input;
    }

    public UiInstance context(UiContext context) {
        this.context = context;
        return this;
    }

    public UiContext context() {
        return context;
    }

    /** Evaluates signals, lays out and records into {@code canvas} (already begun) within the box. */
    public void render(Canvas canvas, float x, float y, float width, float height, float partialTick) {
        render(canvas, null, x, y, width, height, partialTick);
    }

    public void render(Canvas canvas, GuiGraphics graphics, float x, float y, float width, float height,
                       float partialTick) {
        float reference = plan.layout.reference();
        float scale = reference > 0 ? height / reference : 1;
        if (!(scale > 0)) {
            return;
        }
        ev.graphics = graphics;
        double now = UiClock.seconds();
        ev.values[LayoutPlan.TIME] = now;
        ev.values[LayoutPlan.DT] = Double.isNaN(lastTime) ? 0 : now - lastTime;
        lastTime = now;
        ev.canvas = canvas;
        ev.scale = scale;
        ev.width = width / scale;
        ev.height = height / scale;
        ev.partialTick = partialTick;
        canvas.push().translate(x, y).scale(scale);
        UiInput input = ev.input;
        if (input != null) {
            input.dispatch();
            input.begin();
            input.translate(x, y).scale(scale);
        }
        try {
            ev.pixel = 1 / canvas.pixelsPerUnit();
            plan.refreshSubjects(ev, context);
            plan.eval(0, ev);
            UiStats.mark(UiStats.EVAL);
            root.measure(ev);
            UiStats.mark(UiStats.MEASURE);
            root.draw(ev, 0, 0, ev.width, ev.height);
        } finally {
            if (input != null) {
                input.end();
            }
            canvas.pop();
            UiStats.mark(UiStats.DRAW);
            ev.graphics = null;
        }
    }
}
