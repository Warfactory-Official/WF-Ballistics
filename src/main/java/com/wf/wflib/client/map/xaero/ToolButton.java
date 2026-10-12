package com.wf.wflib.client.map.xaero;

import com.wf.wflib.client.map.MapButton;
import com.wf.wflib.client.map.MapTool;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** {@link MapButton} as a toggle; Xaero has no toolbar to add to. */
final class ToolButton extends AbstractButton {

    private static final int ARMED = 0xC04FC3F7;
    private static final int IDLE = 0x80000000;
    private static final int ICON = 16;

    private final MapTool tool;
    private final MapButton spec;
    private boolean shownArmed;

    ToolButton(int x, int y, int size, MapTool tool, MapButton spec) {
        super(x, y, size, size, Component.literal(spec.offLabel()));
        this.tool = tool;
        this.spec = spec;
        this.shownArmed = !tool.armed();
    }

    MapTool tool() {
        return this.tool;
    }

    @Override
    public void onPress() {
        this.tool.setArmed(!this.tool.armed());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean armed = this.tool.armed();
        if (armed != this.shownArmed) {
            this.shownArmed = armed;
            String label = armed ? this.spec.onLabel() : this.spec.offLabel();
            setMessage(Component.literal(label));
            setTooltip(Tooltip.create(Component.literal(label + "\n" + String.join("\n", this.spec.tooltip()))));
        }
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + this.width, y + this.height, armed ? ARMED : IDLE);
        if (isHoveredOrFocused()) {
            graphics.renderOutline(x, y, this.width, this.height, 0xFFFFFFFF);
        }
        int inset = (this.width - ICON) / 2;
        // Whole texture, any size: UV in units of itself.
        graphics.blit(this.spec.icon(), x + inset, y + inset, ICON, ICON, 0.0f, 0.0f, 1, 1, 1, 1);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
