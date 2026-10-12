package com.wf.wflib.probe.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeElement;
import com.wf.wflib.probe.ProbeInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** One probe panel, box + action list under it, at a given top-left; shared by the crosshair panel and nodes. */
public final class ProbePanel {

    private static final int PADDING = 6;
    private static final int BACKGROUND = 0xC0101014;
    private static final int BORDER = 0x60FFFFFF;
    /** Between the box and the action list under it. */
    private static final int ACTION_GAP = 6;
    private static final int TITLE_GAP = 4;
    /** Between stacked elements; outlined text bleeds 1 px each way. */
    private static final int ELEMENT_GAP = 4;

    /** Whole panel; {@code boxWidth/boxHeight} = framed box only (0 when title and elements are empty). */
    public record Size(int width, int height, int boxWidth, int boxHeight, int actionWidth, int titleHeight,
                       int iconWidth) {
    }

    private ProbePanel() {
    }

    /** @param node drawn at a {@link ProbeNodes} node */
    public static Size measure(Minecraft mc, ProbeInfo info, boolean node) {
        int maxWidth = ProbeConfig.MAX_WIDTH.get() - PADDING * 2;
        List<ProbeElement> elements = info.elements();
        List<ProbeAction> actions = info.actions();

        ProbeElement icon = info.titleIconElement();
        int iconWidth = icon == null ? 0 : ProbeRender.width(icon, maxWidth);
        int iconHeight = icon == null ? 0 : ProbeRender.height(icon, maxWidth);
        int titleHeight = info.titleText() == null ? 0 : Math.max(mc.font.lineHeight, iconHeight) + 3;

        int boxWidth = 0;
        int boxHeight = 0;
        if (info.titleText() != null) {
            boxWidth = Math.min(maxWidth, (iconWidth > 0 ? iconWidth + TITLE_GAP : 0) + mc.font.width(info.titleText()));
            boxHeight = titleHeight;
        }
        for (int i = 0; i < elements.size(); i++) {
            ProbeElement element = elements.get(i);
            boxWidth = Math.max(boxWidth, ProbeRender.width(element, maxWidth));
            boxHeight += ProbeRender.height(element, maxWidth) + (i == 0 && titleHeight == 0 ? 0 : ELEMENT_GAP);
        }

        boolean hasBox = info.titleText() != null || !elements.isEmpty();
        int panelWidth = hasBox ? boxWidth + PADDING * 2 : 0;
        int panelHeight = hasBox ? boxHeight + PADDING * 2 : 0;
        int actionWidth = actions.isEmpty() ? 0 : ProbeActionList.width(actions, ProbeSelection.index(), node);
        int actionHeight = actions.isEmpty() ? 0 : ProbeActionList.height(actions);
        return new Size(Math.max(panelWidth, actionWidth + (hasBox ? PADDING * 2 : 0)),
                panelHeight + (actions.isEmpty() ? 0 : (hasBox ? ACTION_GAP : 0) + actionHeight),
                panelWidth, panelHeight, actionWidth, titleHeight, iconWidth);
    }

    /** Canvas units = the caller's scaled GUI units. */
    public static void draw(Canvas canvas, GuiGraphics graphics, Minecraft mc, ProbeInfo info, Size size, int x, int y,
                            float partialTick, boolean node) {
        int maxWidth = ProbeConfig.MAX_WIDTH.get() - PADDING * 2;
        List<ProbeElement> elements = info.elements();
        List<ProbeAction> actions = info.actions();
        boolean hasBox = size.boxWidth() > 0;
        int panelWidth = size.boxWidth();
        int panelHeight = size.boxHeight();
        int cursorX = x + PADDING;
        int cursorY = y + PADDING;
        if (hasBox) {
            canvas.fill(x, y, x + panelWidth, y + panelHeight, BACKGROUND);
            canvas.frame(x, y, x + panelWidth, y + panelHeight, 1, BORDER);

            if (info.titleText() != null) {
                int textX = cursorX;
                ProbeElement icon = info.titleIconElement();
                if (icon != null) {
                    ProbeRender.draw(canvas, graphics, icon, cursorX, cursorY, maxWidth, partialTick, 1);
                    textX += size.iconWidth() + TITLE_GAP;
                }
                ProbeText.draw(canvas, info.titleText(), textX,
                        cursorY + (size.titleHeight() - 3 - mc.font.lineHeight) / 2, info.titleColour());
                cursorY += size.titleHeight();
            }
            for (int i = 0; i < elements.size(); i++) {
                ProbeElement element = elements.get(i);
                if (i > 0 || info.titleText() != null) {
                    cursorY += ELEMENT_GAP;
                }
                ProbeRender.draw(canvas, graphics, element, cursorX, cursorY, maxWidth, partialTick, 1);
                cursorY += ProbeRender.height(element, maxWidth);
            }
        }

        if (!actions.isEmpty()) {
            ProbeActionList.draw(canvas, graphics, actions, ProbeSelection.index(), x + (hasBox ? PADDING : 0),
                    y + panelHeight + (hasBox ? ACTION_GAP : 0), size.actionWidth(), ProbeSelection.offset(),
                    partialTick, node);
        }
    }
}
