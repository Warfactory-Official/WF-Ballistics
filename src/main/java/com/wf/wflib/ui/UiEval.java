package com.wf.wflib.ui;

import com.wf.gemrender.ui.Canvas;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Per-instance runtime state handed to every widget call. */
public final class UiEval {
    final LayoutPlan plan;
    public final double[] values;
    public final double[] state;
    public final CharSequence[] texts;
    public final List<?>[] lists;
    final Object[] subjects;

    public Canvas canvas;
    /**
     * HUD pass only (null when drawing into a texture): for widgets that must hand off to vanilla
     * rendering; {@link Canvas#suspend()} first, {@link Canvas#resume()} after.
     */
    public GuiGraphics graphics;
    /** Host box in layout units. */
    public float width;
    public float height;
    /** Host units per layout unit (see {@link UiLayout#reference()}); 1 = absolute. */
    public float scale = 1;
    /** One framebuffer pixel in layout units. */
    public float pixel = 1;
    public float partialTick;

    UiEval(LayoutPlan plan, double[] values, double[] state, CharSequence[] texts, List<?>[] lists, Object[] subjects) {
        this.plan = plan;
        this.values = values;
        this.state = state;
        this.texts = texts;
        this.lists = lists;
        this.subjects = subjects;
    }

    /**
     * Host coordinate (GUI pixels for the HUD, e.g. {@link ScreenProjection} output) => layout units at
     * the layout root.
     */
    public float fromHost(float hostUnits) {
        return hostUnits / scale;
    }

    public double time() {
        return values[LayoutPlan.TIME];
    }

    public double dt() {
        return values[LayoutPlan.DT];
    }

    /** Binds element {@code index} of {@code count} as the item of {@code items} and evaluates its scope. */
    public void enterItem(ItemsRef<?> items, RepeatScope scope, Object element, int index, int count) {
        subjects[items.itemIndex()] = element;
        values[scope.indexSlot()] = index;
        values[scope.countSlot()] = count;
        plan.eval(scope.id(), this);
    }

    public Object saveItem(ItemsRef<?> items) {
        return subjects[items.itemIndex()];
    }

    public void restoreItem(ItemsRef<?> items, Object saved) {
        subjects[items.itemIndex()] = saved;
    }
}
