package com.wf.wfballistics.probe.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Where the probe panel sits, how loud it is, and whether it shows at all. */
public final class ProbeConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.EnumValue<Anchor> ANCHOR;
    public static final ModConfigSpec.IntValue OFFSET_X;
    public static final ModConfigSpec.IntValue OFFSET_Y;
    public static final ModConfigSpec.DoubleValue SCALE;
    public static final ModConfigSpec.BooleanValue SHOW_EVERYTHING;
    public static final ModConfigSpec.BooleanValue SHOW_MODELS;
    public static final ModConfigSpec.IntValue REFRESH_TICKS;
    public static final ModConfigSpec.IntValue MAX_WIDTH;
    public static final ModConfigSpec.IntValue ACTION_ROWS;
    public static final ModConfigSpec.IntValue ACTION_SLIDE_MILLIS;
    public static final ModConfigSpec.BooleanValue TEXT_OUTLINE;

    /** The four places NTM's own info panel could sit, plus the two that were missing. */
    public enum Anchor {
        CROSSHAIR_RIGHT,
        CROSSHAIR_LEFT,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        TOP_CENTRE
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("The look-at probe panel.").push("probe");

        ENABLED = b.comment("Draw the probe panel at all.").define("enabled", true);
        ANCHOR = b.comment("Where the panel sits on screen.").defineEnum("anchor", Anchor.CROSSHAIR_RIGHT);
        OFFSET_X = b.comment("Nudges the panel sideways from its anchor, in GUI pixels.")
                .defineInRange("offsetX", 0, -512, 512);
        OFFSET_Y = b.comment("Nudges the panel up or down from its anchor, in GUI pixels.")
                .defineInRange("offsetY", 0, -512, 512);
        SCALE = b.comment("Scales the whole panel.").defineInRange("scale", 1.0, 0.25, 4.0);
        SHOW_EVERYTHING = b
                .comment("Show a panel for any block or entity, not only supported ones. Supported means",
                        "the target carries a probe capability or something registered a provider for it;",
                        "on, every block in the world gets a name card, which is a lot of panel for very",
                        "little said.")
                .define("showEverything", false);
        SHOW_MODELS = b
                .comment("Draw the three-dimensional elements: item, block and mesh previews.",
                        "Off draws everything else, which is the cheap path on a weak machine.")
                .define("showModels", true);
        REFRESH_TICKS = b
                .comment("How often to re-ask the server about the thing being looked at. Only sent at",
                        "all for targets something registered a server-side data provider for.")
                .defineInRange("refreshTicks", 10, 1, 200);
        MAX_WIDTH = b.comment("Widest the panel may get, in GUI pixels, before text wraps.")
                .defineInRange("maxWidth", 200, 60, 480);
        ACTION_ROWS = b
                .comment("How many actions the list shows at once. The selected one is drawn full size",
                        "and the rest fall away behind it, so more rows means a longer tail rather than",
                        "more that can be reached: the list wraps either way.")
                .defineInRange("actionRows", 5, 1, 5);
        ACTION_SLIDE_MILLIS = b
                .comment("How long a scrolled list takes to settle, in milliseconds. The rows slide",
                        "between sizes and cross-fade rather than snapping to the new arrangement.",
                        "Zero moves the list the instant the wheel does.")
                .defineInRange("actionSlideMillis", 180, 0, 1000);

        TEXT_OUTLINE = b
                .comment("Outline every glyph instead of giving it a drop shadow. The action list is drawn",
                        "outside the panel, over the world, where a shadow on one side does not carry.")
                .define("textOutline", true);

        b.pop();
        SPEC = b.build();
    }

    private ProbeConfig() {
    }
}
