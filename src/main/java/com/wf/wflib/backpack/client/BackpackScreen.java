package com.wf.wflib.backpack.client;

import com.wf.wflib.backpack.BackpackMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.NotNull;

/** Another player's backpack: scrolling grid over the viewer's inventory. */
public class BackpackScreen extends AbstractContainerScreen<BackpackMenu> {
    private static final int SLOT = BackpackGrid.SLOT;
    private static final int INV_W = 9 * SLOT;
    private static final int PAD = 7;

    private final BackpackGrid grid = new BackpackGrid();
    private int invTop;
    private int used;

    public BackpackScreen(BackpackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        grid.layout(menu.slots, 0, menu.size(), 0, 0);
        imageWidth = INV_W + PAD * 2;
        invTop = BackpackPanel.HEAD + grid.height() + 14;
        imageHeight = invTop + 3 * SLOT + 4 + SLOT + PAD;
        titleLabelX = PAD;
        titleLabelY = 3;
        inventoryLabelX = PAD;
        inventoryLabelY = invTop - 11;
        super.init();
        for (int i = 0; i < 36; i++) {
            Slot slot = menu.slots.get(BackpackMenu.INV + i);
            slot.x = PAD + (i % 9) * SLOT + 1;
            slot.y = i < 27 ? invTop + (i / 9) * SLOT + 1 : invTop + 3 * SLOT + 4 + 1;
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int n = 0;
        for (int i = 0; i < menu.size(); i++) {
            n += menu.slots.get(i).hasItem() ? 1 : 0;
        }
        used = n;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        grid.layout(menu.slots, 0, menu.size(), (imageWidth - BackpackGrid.WIDTH) / 2, BackpackPanel.HEAD);
        int l = leftPos;
        int t = topPos;
        g.fill(l, t, l + imageWidth, t + imageHeight, BackpackPanel.SURFACE);
        g.fill(l, t, l + imageWidth, t + BackpackPanel.HEAD, BackpackPanel.HEADER);
        BackpackPanel.outline(g, l, t, imageWidth, imageHeight);
        grid.render(g, l, t, mouseX, mouseY);
        for (int i = 0; i < 36; i++) {
            Slot slot = menu.slots.get(BackpackMenu.INV + i);
            int x = l + slot.x - 1;
            int y = t + slot.y - 1;
            g.fill(x, y, x + SLOT, y + SLOT, BackpackGrid.WELL_EDGE);
            g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, BackpackGrid.WELL);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics g, int mouseX, int mouseY) {
        String count = used + "/" + menu.size();
        int countX = imageWidth - PAD - font.width(count);
        g.drawString(font, count, countX, titleLabelY, BackpackPanel.MUTED, false);
        g.drawString(font, BackpackPanel.clip(font, title, countX - titleLabelX - 6), titleLabelX, titleLabelY,
                BackpackGrid.TEXT, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, BackpackGrid.TEXT, false);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return grid.scrolled(mouseX - leftPos, mouseY - topPos, scrollY, hasShiftDown())
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return button == 0 && grid.pressed(mouseX - leftPos, mouseY - topPos) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return grid.dragged(mouseY - topPos) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return grid.released() || super.mouseReleased(mouseX, mouseY, button);
    }
}
