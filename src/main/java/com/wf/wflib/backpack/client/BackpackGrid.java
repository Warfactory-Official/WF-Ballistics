package com.wf.wflib.backpack.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/** Scrolling backpack slot window (menu-relative coords); slots outside the window are moved, {@code isActive} hides them. */
final class BackpackGrid {
    static final int SLOT = 18;
    static final int COLS = 5;
    static final int MAX_ROWS = 8;
    static final int BAR = 4;
    static final int WIDTH = COLS * SLOT + 2 + BAR;

    static final int WELL_EDGE = 0x30000000;
    static final int WELL = 0x50000000;
    static final int LIGHT = 0xD6E6E8EB;
    static final int TEXT = 0xFFE6E8EB;
    private static final int MIN_KNOB = 12;

    int left;
    int top;
    private int size;
    private int scrollRow;
    private boolean dragging;

    int rows() {
        return (size + COLS - 1) / COLS;
    }

    int windowRows() {
        return Math.min(rows(), MAX_ROWS);
    }

    int height() {
        return windowRows() * SLOT;
    }

    private int maxScroll() {
        return rows() - windowRows();
    }

    /** {@code slots.get(first + i)} = backpack index {@code i}. */
    void layout(List<Slot> slots, int first, int size, int left, int top) {
        this.size = size;
        this.left = left;
        this.top = top;
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
        for (int i = 0; i < size; i++) {
            Slot slot = slots.get(first + i);
            int row = i / COLS - scrollRow;
            boolean shown = row >= 0 && row < windowRows();
            slot.x = shown ? left + (i % COLS) * SLOT + 1 : -10000;
            slot.y = shown ? top + row * SLOT + 1 : -10000;
        }
    }

    void render(GuiGraphics g, int ox, int oy, double mouseX, double mouseY) {
        for (int r = 0; r < windowRows(); r++) {
            for (int c = 0; c < COLS; c++) {
                int x = ox + left + c * SLOT;
                int y = oy + top + r * SLOT;
                if ((scrollRow + r) * COLS + c < size) {
                    g.fill(x, y, x + SLOT, y + SLOT, WELL_EDGE);
                    g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, WELL);
                }
            }
        }
        if (maxScroll() > 0) {
            int x = ox + left + COLS * SLOT + 2;
            int y = oy + top;
            g.fill(x, y, x + BAR, y + height(), WELL);
            int knob = knob();
            int at = Math.round((float) (height() - knob) * scrollRow / maxScroll());
            g.fill(x, y + at, x + BAR, y + at + knob, dragging || onBar(mouseX - ox, mouseY - oy) ? TEXT : LIGHT);
        }
    }

    private int knob() {
        return Mth.clamp(height() * windowRows() / Math.max(1, rows()), MIN_KNOB, height());
    }

    /** Menu-relative mouse. */
    boolean contains(double x, double y) {
        return x >= left && x < left + WIDTH && y >= top && y < top + height();
    }

    private boolean onBar(double x, double y) {
        int bx = left + COLS * SLOT + 2;
        return maxScroll() > 0 && x >= bx && x < bx + BAR && y >= top && y < top + height();
    }

    boolean scrolled(double x, double y, double delta, boolean page) {
        if (!contains(x, y) || maxScroll() == 0 || delta == 0) {
            return false;
        }
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(delta) * (page ? windowRows() : 1), 0, maxScroll());
        return true;
    }

    boolean pressed(double x, double y) {
        if (!onBar(x, y)) {
            return false;
        }
        dragging = true;
        dragTo(y);
        return true;
    }

    boolean dragged(double y) {
        if (dragging) {
            dragTo(y);
        }
        return dragging;
    }

    /** Any button ends a drag; consumed so the release never reaches slot logic (would drop the carried stack). */
    boolean released() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    private void dragTo(double y) {
        int travel = height() - knob();
        scrollRow = travel <= 0 ? 0 : Mth.clamp(Math.round((float) ((y - top - knob() / 2.0) / travel * maxScroll())),
                0, maxScroll());
    }
}
