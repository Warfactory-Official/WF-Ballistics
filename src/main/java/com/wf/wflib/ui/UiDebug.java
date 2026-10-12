package com.wf.wflib.ui;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.wf.wflib.WFLib;
import com.wf.wflib.ui.widget.Align;
import com.wf.wflib.ui.widget.Bar;
import com.wf.wflib.ui.widget.Box;
import com.wf.wflib.ui.widget.Image;
import com.wf.wflib.ui.widget.Ladder;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import com.wf.wflib.ui.widget.Tape;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

import static com.wf.wflib.ui.Ui.*;

/** {@code /wfui demo}: every built-in widget on the HUD; {@code /wfui stats}: {@link UiStats}. */
public final class UiDebug {
    private static UiInstance demo;
    private static UiInstance surfaceView;
    private static boolean surface;
    private static boolean shown;
    private static boolean registered;

    private UiDebug() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("wfui")
                .then(Commands.literal("demo").executes(ctx -> {
                    shown = !shown;
                    if (!registered) {
                        registered = true;
                        UiHud.addSource(UiHud.Layer.MAIN, UiDebug::collect);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("wfui demo " + (shown ? "on" : "off")), false);
                    return 1;
                }))
                .then(Commands.literal("surface").executes(ctx -> {
                    surface = !surface;
                    if (!registered) {
                        registered = true;
                        UiHud.addSource(UiHud.Layer.MAIN, UiDebug::collect);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("wfui surface " + (surface ? "on" : "off")), false);
                    return 1;
                }))
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource(), false))
                        .then(Commands.literal("detail").executes(ctx -> stats(ctx.getSource(), true))));
    }

    private static int stats(CommandSourceStack source, boolean detail) {
        boolean on = UiStats.toggle(detail);
        source.sendSuccess(() -> Component.literal("wfui stats " + (on ? "on" : "off")), false);
        return 1;
    }

    private static void collect(UiHud.Collector out) {
        if (shown) {
            if (demo == null) {
                demo = demoLayout().instantiate();
            }
            out.add(demo);
        }
        if (surface) {
            if (surfaceView == null) {
                UiLayout demoLayout = demoLayout();
                surfaceView = layout(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "demo_surface"),
                        new SurfaceImage(demoLayout)).instantiate();
            }
            out.add(surfaceView);
        }
    }

    /** The demo drawn into a 640 x 360 surface (320 x 180 units) and shown as a texture, top-left. */
    private record SurfaceImage(UiLayout source) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                }

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    ResourceLocation texture = UiSurfaces.request(SurfaceImage.this, source, UiContext.EMPTY, 640, 360,
                            320, 180, 30, 0x60000000);
                    ev.canvas.frame(7, 39, 169, 131, 1, 0xFFFFFFFF);
                    ev.canvas.sprite(texture, 8, 40, 168, 130, 0xFFFFFFFF);
                }
            };
        }
    }

    static UiLayout demoLayout() {
        String green = "#33ff33";
        return layout(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "demo"), stack(
                place("ui.width / 2", "12", Point.TOP, new Tape("camera.yaw", false, false, false, 4, 5, 3, "120", 6, 8, 1,
                        true, 12, 0, -180, 180, Map.of(0f, "S", 90f, "W", -180f, "N", -90f, "E"), green, green)),
                place("ui.width / 2", "41", Point.TOP, poly(green, 0, 0, -3, 6, 3, 6)),
                place("ui.width / 2", "ui.height / 2", Point.CENTER, new Sized("120", "70", Point.CENTER, true,
                        rotate("sin(ui.time) * 20", Point.CENTER, new Sized("0", "0", Point.CENTER, false,
                                new Ladder("camera.pitch * -1", 2, 5, 20, 3, 4, 1, 3, 1, 32, green))))),
                anchor(Point.BOTTOM_RIGHT, 6, new Box("#c0101014", "#60ffffff", 1, 3, 6, 5, 0, 0, Point.TOP_LEFT,
                        column(3,
                                shadowed("{tr:menu.game}  t={ui.time|.1}s", "#ffffff"),
                                text("HP {player.health|.1} / {player.max_health}", "player.health < 6 ? #ff4040 : " + green),
                                new Bar(120, 5, "player.health / player.max_health", "mixc(#ff3030, #30ff30, player.health / player.max_health)",
                                        "#40000000", "#80ffffff", Bar.Direction.RIGHT, false, "0.5", "#ffffff"),
                                new Bar(120, 5, "sin(ui.time)", green, "#40000000", "0", Bar.Direction.RIGHT, true, "0/0", "0"),
                                row(4, Align.CENTER,
                                        new Image(ResourceLocation.withDefaultNamespace("textures/item/diamond.png"), 16, 16,
                                                0, 0, 1, 1, "#ffffffff", "ui.time * 90"),
                                        text("yaw {camera.yaw|+.1}  pitch {camera.pitch|03}", "#ffffff"),
                                        when("blink(1)", text("BLINK", "#ffcc00"))),
                                row(6,
                                        sized(24, 24, stack(
                                                arc("12", "12", "10", "2", "0", "360", "#40ffffff"),
                                                arc("12", "12", "10", "2", "-90", "wrap(ui.time * 120, 0, 360)", green))),
                                        sized(24, 24, arc("12", "12", "11", "0", "-90", "smooth(abs(sin(ui.time)) * 360, 0.3)", "#8040a0ff")),
                                        sized(40, 24, stack(
                                                line("0", "12", "40", "12 + sin(ui.time * 3) * 10", "1", "#ffffff"),
                                                line("0", "20", "40", "4", "0.5", "#ff8080"))),
                                        new Box("#00000000", "#ffff00", 1.5f, 6, 4, 4, 30, 24, Point.CENTER,
                                                text("{ui.dt * 1000|.1}", "#ffff00"))))))));
    }
}
