package com.wf.wfballistics.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.direct.DirectRenderer;
import com.wf.wfballistics.client.gui.gallery.GalleryEntry;
import com.wf.wfballistics.client.gui.gallery.ModelGallery;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Looks at every model in the mod, out of every registry that holds one. */
public class ModelGalleryScreen extends Screen {

    private enum Mode {TEXTURED, NORMALS}

    private static final int CELL = 64;
    private static final int LABEL_H = 10;
    private static final int PAD = 10;
    /** Below the button row, where the tabs start. */
    private static final int BAR = 24;
    private static final int CHIP_H = 12;
    private static final int BOTTOM = 8;

    /** How far a grid model is tipped towards the camera, so a flat one is not drawn edge-on. */
    private static final float GRID_PITCH = 12.0f;
    /** One turn of a model on the turntable. The rate the missile item renderer has always spun at. */
    private static final long SPIN_MS = 9000L;
    /** One pass of whichever clip is selected. */
    private static final long CLIP_MS = 2000L;

    private final List<ModelGallery.Category> categories = ModelGallery.build();
    /** Clickable rectangles, collected as they are drawn and consulted on the next click. */
    private final List<Hit> hits = new ArrayList<>();

    private int category;
    private int scrollRow;
    /** Where the arrow keys are in the grid, or -1 until they have been used. */
    private int cursor = -1;
    private int gridTop = BAR + CHIP_H + 12;

    private boolean cull = true;
    private Mode mode = Mode.TEXTURED;

    /** Whether the turntable runs. */
    private boolean turning = true;
    /** Whether the selected clip runs. Stopped, the arrow keys scrub it by hand. */
    private boolean playing = true;
    private float scrub;
    private int clip;
    private int variant;

    @Nullable
    private GalleryEntry focused;
    private float yaw;
    private float pitch;
    private float zoom = 1.0f;

    private record Hit(int x, int y, int width, int height, Runnable action) {

        private boolean covers(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    public ModelGalleryScreen() {
        super(Component.literal("WF Model Gallery"));
    }

    private List<GalleryEntry> entries() {
        return this.categories.isEmpty() ? List.of() : this.categories.get(this.category)
                .entries();
    }

    private int columns() {
        return Math.max(1, (this.width - 2 * PAD) / CELL);
    }

    private int rowStride() {
        return CELL + LABEL_H;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - this.gridTop - BOTTOM) / rowStride());
    }

    private int totalRows() {
        return (entries().size() + columns() - 1) / columns();
    }

    private int maxScrollRow() {
        return Math.max(0, totalRows() - visibleRows());
    }

    private int gridX() {
        return (this.width - columns() * CELL) / 2;
    }

    @Override
    protected void init() {
        int h = 16;
        int right = this.width - PAD;
        addRenderableWidget(Button.builder(modeLabel(), b -> {
            this.mode = this.mode == Mode.TEXTURED ? Mode.NORMALS : Mode.TEXTURED;
            b.setMessage(modeLabel());
        }).bounds(right - 110, 6, 110, h).build());
        addRenderableWidget(Button.builder(cullLabel(), b -> {
            this.cull = !this.cull;
            b.setMessage(cullLabel());
        }).bounds(right - 110 - 6 - 74, 6, 74, h).build());
        if (this.focused != null) {
            addRenderableWidget(Button.builder(Component.literal("< Back"), b -> back()).bounds(PAD, 6, 60, h)
                    .build());
        }
        this.gridTop = tabBottom() + 12;
        this.scrollRow = Math.min(this.scrollRow, maxScrollRow());
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + (this.mode == Mode.NORMALS ? "normals" : "textured"));
    }

    private Component cullLabel() {
        return Component.literal("Cull: " + (this.cull ? "on" : "off"));
    }

    // --- layout ------------------------------------------------------------------------------------

    /** Lays the tab chips out left to right, wrapping, and returns the y just below the last row. */
    private int tabBottom() {
        int x = PAD;
        int y = BAR;
        for (ModelGallery.Category tab : this.categories) {
            int w = chipWidth(tabText(tab));
            if (x > PAD && x + w > this.width - PAD) {
                x = PAD;
                y += CHIP_H + 2;
            }
            x += w + 4;
        }
        return y + CHIP_H;
    }

    private String tabText(ModelGallery.Category tab) {
        return tab.name() + " " + tab.entries()
                .size();
    }

    private int chipWidth(String text) {
        return this.font.width(text) + 10;
    }

    /** @return the text, shortened with an ellipsis until it fits in {@code width} pixels. */
    private String clip(String text, int width) {
        if (this.font.width(text) <= width) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, width - this.font.width("...")) + "...";
    }

    // --- render ------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);

        this.hits.clear();
        int titleFrom = this.focused == null ? PAD : PAD + 66;
        int titleTo = this.width - PAD - 110 - 6 - 74 - 6;
        gg.drawCenteredString(this.font, this.title, (titleFrom + titleTo) / 2, 8, 0xFFFFFFFF);

        if (this.categories.isEmpty()) {
            gg.drawCenteredString(this.font, "no model registry has anything in it", this.width / 2,
                    this.height / 2, 0xFFFF6060);
            return;
        }

        RenderSystem.enableDepthTest();
        Lighting.setupFor3DItems();
        MultiBufferSource.BufferSource buffers = gg.bufferSource();

        GalleryEntry hovered = this.focused == null ? drawGrid(gg, buffers, mouseX, mouseY) : null;
        if (this.focused != null) {
            drawInspect(gg, buffers);
        }

        DirectRenderer.flush(DirectPass.GUI);
        buffers.endBatch();
        Lighting.setupForFlatItems();

        if (this.focused == null) {
            drawTabs(gg, mouseX, mouseY);
            drawGridLabels(gg);
            gg.drawString(this.font, entries().size() + " models  |  rows " + (this.scrollRow + 1) + "-"
                    + Math.min(totalRows(), this.scrollRow + visibleRows()) + "/" + totalRows()
                    + "  |  click or arrows + enter to inspect, scroll to page", PAD, this.gridTop - 11,
                    0xFF9090A8, false);
            gg.drawString(this.font, footer(), PAD, this.height - 10, 0xFF707088, false);
        } else {
            drawInspectChrome(gg, mouseX, mouseY);
        }

        if (hovered != null) {
            gg.renderComponentTooltip(this.font, hovered.info(), mouseX, mouseY);
        } else if (this.focused == null && this.cursor >= 0 && this.cursor < entries().size()) {
            int[] cell = cellPos(this.cursor);
            if (cell != null) {
                gg.renderComponentTooltip(this.font, entries().get(this.cursor)
                        .info(), cell[0] + CELL, cell[1] + CELL);
            }
        }
    }

    /** @return where a grid index is drawn, or null if it is on a page that is not showing. */
    @Nullable
    private int[] cellPos(int index) {
        int cols = columns();
        int row = index / cols - this.scrollRow;
        if (row < 0 || row >= visibleRows()) {
            return null;
        }
        return new int[]{gridX() + index % cols * CELL, this.gridTop + row * rowStride()};
    }

    private String footer() {
        return "tab changes registry  |  N normals, C cull, A clip, V variant, P "
                + (this.playing ? "hold clip" : "run clip");
    }

    @Nullable
    private GalleryEntry drawGrid(GuiGraphics gg, MultiBufferSource.BufferSource buffers, int mouseX,
                                  int mouseY) {
        List<GalleryEntry> entries = entries();
        int cols = columns();
        int gridX = gridX();
        int rows = visibleRows();
        GalleryEntry hovered = null;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (this.scrollRow + r) * cols + c;
                if (index >= entries.size()) {
                    continue;
                }
                GalleryEntry entry = entries.get(index);
                int x = gridX + c * CELL;
                int y = this.gridTop + r * rowStride();
                boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                boolean picked = hover || index == this.cursor;
                gg.fill(x, y, x + CELL, y + CELL, picked ? 0x40FFFFFF : 0x30000000);
                gg.renderOutline(x, y, CELL, CELL, picked ? 0xFF6688FF : 0xFF404050);
                if (entry.ready()) {
                    entry.draw(new GalleryEntry.Request(gg, buffers, x + CELL / 2.0f, y + CELL / 2.0f,
                            CELL * 0.8f, GRID_PITCH, spin(), this.clip, phase(), this.variant,
                            this.mode == Mode.NORMALS, this.cull));
                }
                if (hover) {
                    hovered = entry;
                }
            }
        }
        return hovered;
    }

    private void drawGridLabels(GuiGraphics gg) {
        List<GalleryEntry> entries = entries();
        int cols = columns();
        int gridX = gridX();
        int rows = visibleRows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (this.scrollRow + r) * cols + c;
                if (index >= entries.size()) {
                    continue;
                }
                GalleryEntry entry = entries.get(index);
                int x = gridX + c * CELL;
                int y = this.gridTop + r * rowStride();
                String label = clip(entry.label(), CELL - 2);
                int colour = entry.ready() ? 0xFFC0C0D0 : 0xFFB05050;
                gg.drawString(this.font, label, x + (CELL - this.font.width(label)) / 2, y + CELL + 1,
                        colour, false);
            }
        }
    }

    private void drawTabs(GuiGraphics gg, int mouseX, int mouseY) {
        int x = PAD;
        int y = BAR;
        for (int i = 0; i < this.categories.size(); i++) {
            int index = i;
            String text = tabText(this.categories.get(i));
            int w = chipWidth(text);
            if (x > PAD && x + w > this.width - PAD) {
                x = PAD;
                y += CHIP_H + 2;
            }
            chip(gg, text, x, y, w, i == this.category, mouseX, mouseY, () -> select(index));
            x += w + 4;
        }
    }

    private void drawInspect(GuiGraphics gg, MultiBufferSource.BufferSource buffers) {
        GalleryEntry entry = this.focused;
        if (entry == null || !entry.ready()) {
            return;
        }
        float size = Math.min(this.width, this.height) * 0.55f * this.zoom;
        entry.draw(new GalleryEntry.Request(gg, buffers, this.width / 2.0f, this.height / 2.0f, size,
                this.pitch, spin(), this.clip, phase(), this.variant, this.mode == Mode.NORMALS,
                this.cull));
    }

    private void drawInspectChrome(GuiGraphics gg, int mouseX, int mouseY) {
        GalleryEntry entry = this.focused;
        if (entry == null) {
            return;
        }
        int y = BAR;
        for (Component line : entry.info()) {
            gg.drawString(this.font, line, PAD, y, 0xFFFFFFFF, false);
            y += 10;
        }
        if (!entry.ready()) {
            gg.drawString(this.font, "still loading, or it failed to", PAD, y, 0xFFFF6060, false);
        }

        int row = this.height - 24 - CHIP_H;
        row = chips(gg, entry.clips()
                .stream()
                .map(GalleryEntry.Clip::name)
                .toList(), row, this.clip, index -> this.clip = index, mouseX, mouseY);
        chips(gg, entry.variants()
                .stream()
                .map(GalleryEntry.Variant::name)
                .toList(), row, this.variant, index -> this.variant = index, mouseX, mouseY);

        gg.drawString(this.font, "drag or shift+arrows to turn  |  R " + (this.turning ? "hold" : "spin")
                        + "  |  scroll to zoom  |  arrows scrub  |  ESC to return",
                PAD, this.height - 20, 0xFF707088, false);
        gg.drawString(this.font, footer(), PAD, this.height - 10, 0xFF707088, false);
    }

    /** Draws one row of chips and returns the y of the row above it, so rows stack upwards. */
    private int chips(GuiGraphics gg, List<String> names, int y, int selected,
                      java.util.function.IntConsumer pick, int mouseX, int mouseY) {
        if (names.isEmpty()) {
            return y;
        }
        int x = PAD;
        for (int i = 0; i < names.size(); i++) {
            int index = i;
            int w = chipWidth(names.get(i));
            if (x > PAD && x + w > this.width - PAD) {
                break;
            }
            chip(gg, names.get(i), x, y, w, Math.floorMod(selected, names.size()) == i, mouseX, mouseY,
                    () -> pick.accept(index));
            x += w + 4;
        }
        return y - CHIP_H - 2;
    }

    private void chip(GuiGraphics gg, String text, int x, int y, int width, boolean active, int mouseX,
                      int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + CHIP_H;
        gg.fill(x, y, x + width, y + CHIP_H, active ? 0xFF2A3A66 : hover ? 0x40FFFFFF : 0x40000000);
        gg.renderOutline(x, y, width, CHIP_H, active ? 0xFF6688FF : 0xFF404050);
        gg.drawString(this.font, text, x + 5, y + 2, active ? 0xFFFFFFFF : 0xFFA0A0B8, false);
        this.hits.add(new Hit(x, y, width, CHIP_H, action));
    }

    // --- animation ---------------------------------------------------------------------------------

    private float spin() {
        return this.turning ? (float) (Util.getMillis() % SPIN_MS) / SPIN_MS * 360.0f : this.yaw;
    }

    private float phase() {
        return this.playing ? (float) (Util.getMillis() % CLIP_MS) / CLIP_MS : this.scrub;
    }

    // --- input -------------------------------------------------------------------------------------

    private void select(int index) {
        if (this.categories.isEmpty()) {
            return;
        }
        this.category = Math.floorMod(index, this.categories.size());
        this.scrollRow = 0;
        this.cursor = -1;
        this.clip = 0;
        this.variant = 0;
        this.focused = null;
        rebuildWidgets();
    }

    private void focus(GalleryEntry entry) {
        this.focused = entry;
        this.turning = true;
        this.yaw = 25.0f;
        this.pitch = -10.0f;
        this.zoom = 1.0f;
        rebuildWidgets();
    }

    /** Walks the grid, paging it so the cell stays on screen. */
    private void moveCursor(int dx, int dy) {
        List<GalleryEntry> entries = entries();
        if (entries.isEmpty()) {
            return;
        }
        int cols = columns();
        int next = this.cursor < 0 ? this.scrollRow * cols : this.cursor + dx + dy * cols;
        this.cursor = Mth.clamp(next, 0, entries.size() - 1);
        int row = this.cursor / cols;
        if (row < this.scrollRow) {
            this.scrollRow = row;
        } else if (row >= this.scrollRow + visibleRows()) {
            this.scrollRow = row - visibleRows() + 1;
        }
        this.scrollRow = Mth.clamp(this.scrollRow, 0, maxScrollRow());
    }

    private void back() {
        this.focused = null;
        rebuildWidgets();
    }

    @Nullable
    private GalleryEntry cellAt(double mouseX, double mouseY) {
        List<GalleryEntry> entries = entries();
        int cols = columns();
        int gridX = gridX();
        if (mouseX < gridX || mouseY < this.gridTop) {
            return null;
        }
        int c = (int) ((mouseX - gridX) / CELL);
        int r = (int) ((mouseY - this.gridTop) / rowStride());
        if (c < 0 || c >= cols || r < 0 || r >= visibleRows()) {
            return null;
        }
        if ((mouseY - this.gridTop) % rowStride() >= CELL) {
            return null;
        }
        int index = (this.scrollRow + r) * cols + c;
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            // Walked backwards so the chip drawn last (the one on top, if two ever overlap) wins.
            for (int i = this.hits.size() - 1; i >= 0; i--) {
                Hit hit = this.hits.get(i);
                if (hit.covers(mouseX, mouseY)) {
                    hit.action()
                            .run();
                    return true;
                }
            }
        }
        if (this.focused == null && button == 0) {
            GalleryEntry entry = cellAt(mouseX, mouseY);
            if (entry != null) {
                focus(entry);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.focused != null && button == 0) {
            if (this.turning) {
                this.yaw = spin();
                this.turning = false;
            }
            this.yaw += (float) dragX;
            this.pitch = Mth.clamp(this.pitch + (float) dragY, -90.0f, 90.0f);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.focused != null) {
            this.zoom = Mth.clamp(this.zoom * (scrollY > 0 ? 1.1f : 0.9f), 0.2f, 6.0f);
            return true;
        }
        this.scrollRow = Math.max(0, Math.min(maxScrollRow(),
                this.scrollRow - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (this.focused != null) {
                    back();
                    return true;
                }
            }
            case GLFW.GLFW_KEY_TAB -> {
                select(this.category + (hasShiftDown() ? -1 : 1));
                return true;
            }
            case GLFW.GLFW_KEY_C -> {
                this.cull = !this.cull;
                rebuildWidgets();
                return true;
            }
            case GLFW.GLFW_KEY_N -> {
                this.mode = this.mode == Mode.TEXTURED ? Mode.NORMALS : Mode.TEXTURED;
                rebuildWidgets();
                return true;
            }
            case GLFW.GLFW_KEY_A -> {
                this.clip++;
                return true;
            }
            case GLFW.GLFW_KEY_V -> {
                this.variant++;
                return true;
            }
            case GLFW.GLFW_KEY_P -> {
                // Stopping the clock keeps whatever it was showing, so pausing never jumps the pose.
                if (this.playing) {
                    this.scrub = phase();
                }
                this.playing = !this.playing;
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                if (this.turning) {
                    this.yaw = spin();
                    this.turning = false;
                } else {
                    this.turning = true;
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                List<GalleryEntry> entries = entries();
                if (this.focused == null && this.cursor >= 0 && this.cursor < entries.size()) {
                    focus(entries.get(this.cursor));
                    return true;
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                return this.focused == null ? move(-1, 0)
                        : hasShiftDown() ? turn(-15.0f, 0.0f) : scrubBy(-0.02f);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                return this.focused == null ? move(1, 0)
                        : hasShiftDown() ? turn(15.0f, 0.0f) : scrubBy(0.02f);
            }
            case GLFW.GLFW_KEY_UP -> {
                return this.focused == null ? move(0, -1)
                        : hasShiftDown() ? turn(0.0f, -15.0f) : scrubBy(0.1f);
            }
            case GLFW.GLFW_KEY_DOWN -> {
                return this.focused == null ? move(0, 1)
                        : hasShiftDown() ? turn(0.0f, 15.0f) : scrubBy(-0.1f);
            }
            default -> {
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean turn(float yawBy, float pitchBy) {
        if (this.turning) {
            // Take over from wherever the turntable had got to rather than snapping back to the start.
            this.yaw = spin();
            this.turning = false;
        }
        this.yaw += yawBy;
        this.pitch = Mth.clamp(this.pitch + pitchBy, -90.0f, 90.0f);
        return true;
    }

    private boolean move(int dx, int dy) {
        moveCursor(dx, dy);
        return true;
    }

    private boolean scrubBy(float delta) {
        this.playing = false;
        this.scrub = Mth.clamp(this.scrub + delta, 0.0f, 1.0f);
        return true;
    }
}
