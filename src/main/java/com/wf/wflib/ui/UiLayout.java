package com.wf.wflib.ui;

import net.minecraft.resources.ResourceLocation;

/**
 * A composed screen: {@code root} is drawn into the host's box (the whole GUI for the HUD).
 *
 * <p>Scaling: {@code reference > 0} => the box is {@code reference / zoom} layout units tall whatever its size in
 * host units (GUI pixels for the HUD), so the layout keeps its proportion of the screen at any resolution;
 * HUD zoom = GUI scale / auto GUI scale. {@code ui.width} / {@code ui.height} read layout units. {@code reference = 0}
 * (absolute) => layout units = host units. Host-unit or pixel-exact values inside a relative layout:
 * expression suffixes {@code gu} / {@code px}.
 */
public final class UiLayout {
    /** 1 layout unit = 1 GUI pixel at 1080p, auto GUI scale. */
    public static final float DEFAULT_REFERENCE = 270;

    private final ResourceLocation id;
    private final WidgetDef root;
    private final float reference;
    private LayoutPlan plan;

    public UiLayout(ResourceLocation id, WidgetDef root) {
        this(id, root, DEFAULT_REFERENCE);
    }

    public UiLayout(ResourceLocation id, WidgetDef root, float reference) {
        if (!(reference >= 0)) {
            throw new IllegalArgumentException("reference " + reference + " for " + id);
        }
        this.id = id;
        this.root = root;
        this.reference = reference;
    }

    /** Same tree in host units. */
    public UiLayout absolute() {
        return new UiLayout(id, root, 0);
    }

    public ResourceLocation id() {
        return id;
    }

    public WidgetDef root() {
        return root;
    }

    /** Layout units per box height; 0 = absolute. */
    public float reference() {
        return reference;
    }

    /** Compiles on first call; a bad expression throws here, naming the layout. */
    public UiInstance instantiate() {
        if (plan == null) {
            plan = new LayoutPlan(this);
        }
        return plan.instantiate();
    }

    @Override
    public String toString() {
        return "UiLayout[" + id + "]";
    }
}
