package com.wf.wflib.client.gui;

import com.wf.wflib.drone.DroneProgram;
import com.wf.wflib.drone.DroneTask;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The queue editor: build the list of steps a flight will fly, in order. */
public class DroneProgramScreen extends Screen {

    private static final int W = 300;
    private static final int H = 232;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int BTN_H = 18;
    private static final int BOX_H = 16;
    private static final int ROW_H = 12;
    /** Rows drawn at once; the rest is scrolled to. */
    private static final int VISIBLE_ROWS = 8;

    private final Screen parent;
    private final Consumer<DroneProgram> onDone;
    private final Vec3 defaultAt;

    private DroneProgram program;
    private int kindIndex;
    private int selected = -1;
    private int scroll;

    private final List<EditBox> boxes = new ArrayList<>();
    private EditBox atX;
    private EditBox atY;
    private EditBox atZ;
    private EditBox radiusBox;
    private EditBox secondsBox;
    private Button kindButton;

    private int listX;
    private int listY;

    public DroneProgramScreen(Screen parent, DroneProgram program, Vec3 defaultAt,
                              Consumer<DroneProgram> onDone) {
        super(Component.literal("Flight program"));
        this.parent = parent;
        this.program = program;
        this.defaultAt = defaultAt;
        this.onDone = onDone;
    }

    private DroneTask.Kind kind() {
        DroneTask.Kind[] kinds = DroneTask.Kind.values();
        return kinds[Math.floorMod(kindIndex, kinds.length)];
    }

    private int left() {
        return (this.width - W) / 2;
    }

    private int top() {
        return (this.height - H) / 2;
    }

    @Override
    protected void init() {
        boxes.clear();
        int x = left() + PAD;
        int y = top() + 20;

        // --- the step being composed ---
        kindButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            kindIndex++;
            // The fields a step needs depend on its kind, so rebuild rather than toggling widgets around.
            this.rebuildWidgets();
        }).bounds(x, y, 92, BTN_H).build());

        int fieldX = x + 92 + GAP;
        int third = (W - 2 * PAD - 92 - GAP - 2 * GAP) / 3;
        boolean placed = kind() != DroneTask.Kind.EXFIL;
        if (placed) {
            atX = makeBox(fieldX, y + 1, third, str(defaultAt.x));
            atY = makeBox(fieldX + third + GAP, y + 1, third, str(defaultAt.y));
            atZ = makeBox(fieldX + 2 * (third + GAP), y + 1, third, str(defaultAt.z));
        } else {
            atX = null;
            atY = null;
            atZ = null;
        }
        y += BTN_H + GAP;

        if (kind() == DroneTask.Kind.LOITER) {
            radiusBox = makeBox(x, y + 10, 92, str(DroneTask.Loiter.DEFAULT_RADIUS));
            secondsBox = makeBox(x + 92 + GAP, y + 10, 92, "0");
            y += BOX_H + 10 + GAP;
        } else {
            radiusBox = null;
            secondsBox = null;
        }

        addRenderableWidget(Button.builder(Component.literal("Add step"), b -> addStep())
                .bounds(x, y, 92, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
            program = program.removing(selected);
            selected = Math.min(selected, program.tasks().size() - 1);
            clampScroll();
        }).bounds(x + 92 + GAP, y, 62, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Up"), b -> move(-1))
                .bounds(x + 92 + 62 + 2 * GAP, y, 40, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Down"), b -> move(1))
                .bounds(x + 92 + 62 + 40 + 3 * GAP, y, 44, BTN_H).build());
        y += BTN_H + GAP + 4;

        listX = x;
        listY = y;
        y += VISIBLE_ROWS * ROW_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            program = DroneProgram.EMPTY;
            selected = -1;
            scroll = 0;
        }).bounds(x, top() + H - PAD - BTN_H, 80, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left() + W - PAD - 80, top() + H - PAD - BTN_H, 80, BTN_H).build());

        refreshKindLabel();
    }

    private EditBox makeBox(int x, int y, int width, String value) {
        EditBox box = new EditBox(this.font, x, y, width, BOX_H, Component.empty());
        box.setMaxLength(12);
        box.setValue(value);
        addRenderableWidget(box);
        boxes.add(box);
        return box;
    }

    private void refreshKindLabel() {
        kindButton.setMessage(Component.literal(kind().id()));
    }

    private static String str(double v) {
        return Long.toString(Math.round(v));
    }

    private static double parse(EditBox box, double fallback) {
        if (box == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void addStep() {
        Vec3 at = new Vec3(parse(atX, defaultAt.x), parse(atY, defaultAt.y), parse(atZ, defaultAt.z));
        DroneTask task = kind() == DroneTask.Kind.LOITER
                ? new DroneTask.Loiter(at, parse(radiusBox, DroneTask.Loiter.DEFAULT_RADIUS),
                        (int) Math.round(parse(secondsBox, 0.0)) * 20)
                : kind().at(at);
        int before = program.tasks().size();
        program = program.plus(task);
        if (program.tasks().size() > before) {
            selected = program.tasks().size() - 1;
            clampScroll();
        }
    }

    private void move(int delta) {
        int to = selected + delta;
        DroneProgram moved = program.moved(selected, delta);
        if (moved != program) {
            program = moved;
            selected = to;
            clampScroll();
        }
    }

    /** Keep the selected row on screen, and the window inside the list. */
    private void clampScroll() {
        int max = Math.max(0, program.tasks().size() - VISIBLE_ROWS);
        if (selected >= 0) {
            scroll = Math.min(Math.max(scroll, selected - VISIBLE_ROWS + 1), selected);
        }
        scroll = Math.min(Math.max(0, scroll), max);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int row = (int) ((mouseY - listY) / ROW_H);
        if (mouseX >= listX && mouseX <= listX + W - 2 * PAD && row >= 0 && row < VISIBLE_ROWS) {
            int index = scroll + row;
            selected = index < program.tasks().size() ? index : -1;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int max = Math.max(0, program.tasks().size() - VISIBLE_ROWS);
        scroll = Math.min(Math.max(0, scroll - (int) Math.signum(deltaY)), max);
        return true;
    }

    /** The panel, drawn as part of the background so that the widgets on it land on top of it. */
    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gg, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        gg.fill(x, y, x + W, y + H, 0xF0101018);
        gg.fill(x, y, x + W, y + 14, 0xFF2B2B44);
        gg.renderOutline(x, y, W, H, 0xFF404060);
        gg.drawString(this.font, this.title, x + PAD, y + 4, 0xE0E0F0, false);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);

        if (radiusBox != null) {
            gg.drawString(this.font, "Orbit radius (0 = hold)", radiusBox.getX(), radiusBox.getY() - 9,
                    0x9090A8, false);
            gg.drawString(this.font, "Seconds (0 = until low)", secondsBox.getX(), secondsBox.getY() - 9,
                    0x9090A8, false);
        }

        List<DroneTask> tasks = program.tasks();
        if (tasks.isEmpty()) {
            gg.drawString(this.font, "No steps. The flight will use the pad's destination instead.",
                    listX, listY + 2, 0x8080A0, false);
        }
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scroll + row;
            if (index >= tasks.size()) {
                break;
            }
            int rowY = listY + row * ROW_H;
            if (index == selected) {
                gg.fill(listX - 2, rowY - 1, listX + W - 2 * PAD, rowY + ROW_H - 2, 0x60406080);
            }
            gg.drawString(this.font, (index + 1) + ". " + tasks.get(index).label(), listX, rowY + 1,
                    index == selected ? 0xFFFFFF : 0xC0C0D0, false);
        }

        int route = (int) program.routeLength(defaultAt, defaultAt);
        String summary = tasks.size() + "/" + DroneTask.MAX_STEPS + " steps"
                + (tasks.isEmpty() ? "" : ", ~" + route + "m of route");
        gg.drawString(this.font, summary, listX, top() + H - PAD - BTN_H - 12, 0x9090A8, false);
    }

    @Override
    public void onClose() {
        onDone.accept(program);
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        for (EditBox box : boxes) {
            if (box.isFocused()) {
                box.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        for (EditBox box : boxes) {
            if (box.isFocused()) {
                return box.charTyped(c, modifiers);
            }
        }
        return super.charTyped(c, modifiers);
    }
}
