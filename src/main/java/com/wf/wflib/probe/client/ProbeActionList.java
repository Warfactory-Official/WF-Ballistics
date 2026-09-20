package com.wf.wflib.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wflib.probe.ProbeAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The action list, drawn the way an interaction list reads in Arma Reforger: the chosen action sits at the top of
 * the stack full size and full strength, and the ones behind it fall away: each a little smaller and a little
 * fainter than the last, the furthest one half transparent.
 */
public final class ProbeActionList {

    /** Row size, from the selected row outwards. The array length is also the most rows ever drawn. */
    private static final float[] SCALE = {1.2f, 1.0f, 0.88f, 0.8f, 0.72f};
    /** Row opacity over the same range: full, then fading to half at the last one. */
    private static final float[] ALPHA = {1.0f, 0.9f, 0.75f, 0.62f, 0.5f};
    /** The slot above the list carries on the ramp past its top, and the one below past its bottom. */
    private static final float ABOVE = 1.1f;
    private static final float BELOW = 0.9f;

    private static final int LINE = 9;
    private static final int GAP = 1;
    private static final int ICON_GAP = 3;
    private static final float HINT_SCALE = 0.8f;

    private ProbeActionList() {
    }

    public static int rows(int actions) {
        return Math.min(actions, Math.min(SCALE.length, ProbeConfig.ACTION_ROWS.get()));
    }

    /** Widest the list gets, so the panel can be sized before anything is drawn. */
    public static int width(List<ProbeAction> actions, int selected) {
        Font font = Minecraft.getInstance().font;
        int widest = 0;
        int shown = rows(actions.size());
        for (int row = 0; row < shown; row++) {
            ProbeAction action = actions.get(Math.floorMod(selected + row, actions.size()));
            widest = Math.max(widest, Math.round(rowWidth(font, action) * SCALE[row]));
        }
        return Math.max(widest, hintWidth(font, actions.size()));
    }

    public static int height(List<ProbeAction> actions) {
        int shown = rows(actions.size());
        return top(shown, shown) + Math.round(LINE * HINT_SCALE) + 2;
    }

    /**
     * @param offset how far the drawing lags the selection, in rows; see {@link ProbeSelection#offset}.
     */
    public static void draw(GuiGraphics graphics, List<ProbeAction> actions, int selected, int x, int y,
                            int width, float offset, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int shown = rows(actions.size());

        if (ProbeInput.scrolling()) {
            graphics.fill(x - 2, y - 1, x + width + 2, y + Math.round(LINE * SCALE[0]) + 1, 0x40FFFFFF);
        }

        // One row past each end of the list either way, which is where a row on its way in or out is.
        for (int row = Mth.floor(offset) - 1; row <= Mth.ceil(offset) + shown; row++) {
            float slot = row - offset;
            if (slot <= -1.0f || slot >= shown) {
                continue;
            }
            ProbeAction action = actions.get(Math.floorMod(selected + row, actions.size()));
            float scale = scaleAt(slot, shown);
            float alpha = alphaAt(slot, shown);

            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, y + topAt(slot, shown), 0);
            pose.scale(scale, scale, 1.0f);

            int textX = iconWidth(action);
            if (textX > 0) {
                graphics.setColor(1.0f, 1.0f, 1.0f, alpha);
                ProbeRender.draw(graphics, action.icon(), 0, -1, textX, partialTick);
                graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
                textX += ICON_GAP;
            }
            ProbeText.draw(graphics, font, label(action), textX, 0, colour(action, alpha));
            pose.popPose();
        }

        Component hint = hint(actions.size());
        if (hint != null) {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, y + top(shown, shown) + 1, 0);
            pose.scale(HINT_SCALE, HINT_SCALE, 1.0f);
            ProbeText.draw(graphics, font, hint, 0, 0, 0xC0909090);
            pose.popPose();
        }
    }

    // --- the ramp, sampled ---------------------------------------------------------------------

    private static float scaleAt(float slot, int shown) {
        int low = Mth.floor(slot);
        return Mth.lerp(slot - low, slotScale(low, shown), slotScale(low + 1, shown));
    }

    private static float alphaAt(float slot, int shown) {
        int low = Mth.floor(slot);
        return Mth.lerp(slot - low, slotAlpha(low, shown), slotAlpha(low + 1, shown));
    }

    private static float slotScale(int slot, int shown) {
        if (slot < 0) {
            return SCALE[0] * ABOVE;
        }
        if (slot >= shown) {
            return SCALE[Math.max(0, shown - 1)] * BELOW;
        }
        return SCALE[slot];
    }

    private static float slotAlpha(int slot, int shown) {
        return slot < 0 || slot >= shown ? 0.0f : ALPHA[slot];
    }

    /** Top of a whole slot, relative to the top of the list. */
    private static int top(int slot, int shown) {
        int y = 0;
        for (int i = 0; i < slot; i++) {
            y += Math.round(LINE * slotScale(i, shown)) + GAP;
        }
        for (int i = -1; i >= slot; i--) {
            y -= Math.round(LINE * slotScale(i, shown)) + GAP;
        }
        return y;
    }

    private static float topAt(float slot, int shown) {
        int low = Mth.floor(slot);
        return Mth.lerp(slot - low, (float) top(low, shown), (float) top(low + 1, shown));
    }

    // --- a row ---------------------------------------------------------------------------------

    private static Component label(ProbeAction action) {
        if (action.enabled() || action.note() == null) {
            return action.label();
        }
        return Component.empty().append(action.label()).append(Component.literal(" - "))
                .append(action.note());
    }

    private static int colour(ProbeAction action, float alpha) {
        int rgb = action.enabled() ? 0xFFFFFF : 0x9A9A9A;
        int a = Mth.clamp(Math.round(alpha * 255.0f), 8, 255);
        return (a << 24) | rgb;
    }

    private static int rowWidth(Font font, ProbeAction action) {
        int icon = iconWidth(action);
        return (icon > 0 ? icon + ICON_GAP : 0) + font.width(label(action));
    }

    /** Zero when there is no icon, or when the three-dimensional elements are switched off. */
    private static int iconWidth(ProbeAction action) {
        return action.icon() == null || !ProbeConfig.SHOW_MODELS.get()
                ? 0 : ProbeRender.width(action.icon(), 64);
    }

    private static int hintWidth(Font font, int count) {
        Component hint = hint(count);
        return hint == null ? 0 : Math.round(font.width(hint) * HINT_SCALE);
    }

    /** Names the actual bound keys, because the defaults are the first thing a player rebinds. */
    private static Component hint(int count) {
        if (count == 0) {
            return null;
        }
        Component activate = Component.translatable("probe.wflib.hint.activate",
                ProbeKeys.ACTIVATE.getTranslatedKeyMessage());
        if (count < 2) {
            return activate;
        }
        return Component.translatable("probe.wflib.hint.scroll",
                ProbeKeys.MODIFIER.getTranslatedKeyMessage()).append(Component.literal("  ")).append(activate);
    }
}
