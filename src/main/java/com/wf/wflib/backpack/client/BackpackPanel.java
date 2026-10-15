package com.wf.wflib.backpack.client;

import com.wf.wflib.backpack.BackpackItem;
import com.wf.wflib.backpack.BackpackPackets;
import com.wf.wflib.backpack.Backpacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/** Owner's worn backpack left of the survival inventory; hidden under the recipe book. */
final class BackpackPanel {
    static final int PAD = 4;
    static final int HEAD = 14;
    static final int FOOT = 12;
    static final int WIDTH = PAD * 2 + BackpackGrid.WIDTH;
    static final int GAP = 2;
    static final int LOCK = 9;

    static final int SURFACE = 0xCC48494A;
    static final int HEADER = 0xD1313233;
    static final int FOOTER = 0xC73A3B3D;
    static final int OUTLINE = 0x33FFFFFF;
    static final int MUTED = 0xFF9DA0A4;
    static final int LOCKED = 0xFFE0B040;

    private static final BackpackGrid GRID = new BackpackGrid();

    private BackpackPanel() {
    }

    static boolean shown() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen instanceof InventoryScreen s && !s.getRecipeBookComponent().isVisible() && mc.player != null
                && Backpacks.worn(mc.player).getItem() instanceof BackpackItem;
    }

    private static int x0() {
        return -WIDTH - GAP;
    }

    static void background(ContainerScreenEvent.Render.Background e) {
        if (!(e.getContainerScreen() instanceof InventoryScreen screen) || !shown()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        ItemStack worn = Backpacks.worn(player);
        int size = ((BackpackItem) worn.getItem()).spec().slots();
        GRID.layout(screen.getMenu().slots, Backpacks.FIRST_SLOT, size, x0() + PAD, HEAD);
        GuiGraphics g = e.getGuiGraphics();
        int ox = screen.getGuiLeft();
        int oy = screen.getGuiTop();
        int x = ox + x0();
        int h = HEAD + GRID.height() + FOOT;
        g.fill(x, oy, x + WIDTH, oy + h, SURFACE);
        g.fill(x, oy, x + WIDTH, oy + HEAD, HEADER);
        g.fill(x, oy + h - FOOT, x + WIDTH, oy + h, FOOTER);
        outline(g, x, oy, WIDTH, h);
        Font font = Minecraft.getInstance().font;
        g.drawString(font, clip(font, worn.getHoverName(), WIDTH - PAD * 3 - LOCK), x + PAD, oy + 3,
                BackpackGrid.TEXT, false);
        lock(g, lockX(ox), oy + 2, Backpacks.locked(worn));
        GRID.render(g, ox, oy, e.getMouseX(), e.getMouseY());
        var spec = ((BackpackItem) worn.getItem()).spec();
        String load = String.format(Locale.ROOT, "%d/%d", Backpacks.used(worn), size)
                + (spec.maxLoadKg() > 0 ? String.format(Locale.ROOT, "  %.1f/%.0f kg", Backpacks.loadKg(worn), spec.maxLoadKg())
                : String.format(Locale.ROOT, "  %.1f kg", Backpacks.loadKg(worn)));
        g.drawString(font, load, x + PAD, oy + h - FOOT + 2, MUTED, false);
    }

    static void foreground(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof InventoryScreen screen) || !shown() || !onLock(screen, e.getMouseX(), e.getMouseY())) {
            return;
        }
        boolean locked = Backpacks.locked(Backpacks.worn(Minecraft.getInstance().player));
        e.getGuiGraphics().renderTooltip(Minecraft.getInstance().font,
                Component.translatable(locked ? "wflib.backpack.lock.on" : "wflib.backpack.lock.off"),
                e.getMouseX(), e.getMouseY());
    }

    private static int lockX(int ox) {
        return ox + x0() + WIDTH - PAD - LOCK;
    }

    private static boolean onLock(InventoryScreen screen, double mx, double my) {
        int x = lockX(screen.getGuiLeft());
        int y = screen.getGuiTop() + 2;
        return mx >= x && mx < x + LOCK && my >= y && my < y + LOCK + 1;
    }

    static void scrolled(ScreenEvent.MouseScrolled.Pre e) {
        if (e.getScreen() instanceof InventoryScreen s && shown() && GRID.scrolled(e.getMouseX() - s.getGuiLeft(),
                e.getMouseY() - s.getGuiTop(), e.getScrollDeltaY(), Screen.hasShiftDown())) {
            e.setCanceled(true);
        }
    }

    static void pressed(ScreenEvent.MouseButtonPressed.Pre e) {
        if (!(e.getScreen() instanceof InventoryScreen s) || !shown() || e.getButton() != 0) {
            return;
        }
        if (onLock(s, e.getMouseX(), e.getMouseY())) {
            PacketDistributor.sendToServer(new BackpackPackets.Action(BackpackPackets.Action.Kind.TOGGLE_LOCK));
            e.setCanceled(true);
        } else if (GRID.pressed(e.getMouseX() - s.getGuiLeft(), e.getMouseY() - s.getGuiTop())) {
            e.setCanceled(true);
        }
    }

    static void dragged(ScreenEvent.MouseDragged.Pre e) {
        if (e.getScreen() instanceof InventoryScreen s && GRID.dragged(e.getMouseY() - s.getGuiTop())) {
            e.setCanceled(true);
        }
    }

    static void released(ScreenEvent.MouseButtonReleased.Pre e) {
        if (e.getScreen() instanceof InventoryScreen && GRID.released()) {
            e.setCanceled(true);
        }
    }

    static void outline(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, OUTLINE);
        g.fill(x, y + h - 1, x + w, y + h, OUTLINE);
        g.fill(x, y, x + 1, y + h, OUTLINE);
        g.fill(x + w - 1, y, x + w, y + h, OUTLINE);
    }

    /** Padlock 9x10: shackle closed when locked, raised + open right post when not. */
    static void lock(GuiGraphics g, int x, int y, boolean locked) {
        int c = locked ? LOCKED : MUTED;
        int lift = locked ? 0 : 1;
        g.fill(x, y + 5, x + 9, y + 10, c);
        g.fill(x + 1, y + 1 - lift, x + 8, y + 2 - lift, c);
        g.fill(x + 1, y + 1 - lift, x + 2, y + 5, c);
        g.fill(x + 7, y + 1 - lift, x + 8, y + (locked ? 5 : 2), c);
    }

    static Component clip(Font font, Component text, int room) {
        if (font.width(text) <= room) {
            return text;
        }
        return Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(0, room - font.width("..."))) + "...");
    }
}
