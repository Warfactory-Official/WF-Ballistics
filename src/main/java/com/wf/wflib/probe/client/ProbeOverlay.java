package com.wf.wflib.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeContext;
import com.wf.wflib.probe.ProbeElement;
import com.wf.wflib.probe.ProbeInfo;
import com.wf.wflib.probe.ProbeRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/** The panel itself: what the player is looking at, asked about, laid out and drawn. */
public final class ProbeOverlay implements LayeredDraw.Layer {

    private static final int PADDING = 4;
    private static final int BACKGROUND = 0xC0101014;
    private static final int BORDER = 0x60FFFFFF;
    /** Between the box and the action list under it. */
    private static final int ACTION_GAP = 3;
    private static final int TITLE_GAP = 3;

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        ProbeSelection.advance();
        if (!ProbeConfig.ENABLED.get() || mc.options.hideGui || mc.level == null || mc.player == null
                || mc.getDebugOverlay().showDebugScreen()) {
            ProbeSelection.clear();
            return;
        }

        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            ProbeClientData.clear();
            ProbeSelection.clear();
            return;
        }

        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        ProbeInfo info = gather(mc, mc.player, mc.level, hit, partialTick);
        if (info == null || info.isEmpty()) {
            return;
        }
        paint(graphics, mc, info, partialTick);
    }

    private ProbeInfo gather(Minecraft mc, Player player, Level level, HitResult hit, float partialTick) {
        ProbeInfo info = new ProbeInfo();
        long gameTime = level.getGameTime();
        int refresh = ProbeConfig.REFRESH_TICKS.get();

        if (hit instanceof BlockHitResult block) {
            BlockPos pos = block.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                ProbeClientData.clear();
                return null;
            }
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            if (ProbeRegistry.wantsBlockData(level, pos, state, blockEntity)) {
                ProbeClientData.request(pos, -1, gameTime, refresh);
            }
            CompoundTag data = ProbeClientData.get(pos, -1);
            boolean supported = ProbeRegistry.collect(info, context(player, level, hit, data, partialTick),
                    state, pos, blockEntity);
            if (!supported && !ProbeConfig.SHOW_EVERYTHING.get()) {
                ProbeSelection.clear();
                return null;
            }
            ProbeSelection.offer(pos, -1, info.actions());
            return info;
        }

        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (ProbeRegistry.wantsEntityData(entity)) {
                ProbeClientData.request(entity.blockPosition(), entity.getId(), gameTime, refresh);
            }
            CompoundTag data = ProbeClientData.get(entity.blockPosition(), entity.getId());
            boolean supported = ProbeRegistry.collect(info,
                    context(player, level, hit, data, partialTick), entity);
            if (!supported && !ProbeConfig.SHOW_EVERYTHING.get()) {
                ProbeSelection.clear();
                return null;
            }
            ProbeSelection.offer(entity.blockPosition(), entity.getId(), info.actions());
            return info;
        }

        return null;
    }

    private static ProbeContext context(Player player, Level level, HitResult hit, CompoundTag data,
                                        float partialTick) {
        return new ProbeContext(player, level, hit, player.getMainHandItem(), player.getOffhandItem(),
                data, partialTick);
    }

    /** Draws the panel, and the action list under it. */
    private void paint(GuiGraphics graphics, Minecraft mc, ProbeInfo info, float partialTick) {
        int maxWidth = ProbeConfig.MAX_WIDTH.get() - PADDING * 2;
        List<ProbeElement> elements = info.elements();
        List<ProbeAction> actions = info.actions();

        ProbeElement icon = info.titleIconElement();
        int iconWidth = icon == null ? 0 : ProbeRender.width(icon, maxWidth);
        int iconHeight = icon == null ? 0 : ProbeRender.height(icon, maxWidth);
        int titleHeight = info.titleText() == null ? 0
                : Math.max(mc.font.lineHeight, iconHeight) + 2;

        int boxWidth = 0;
        int boxHeight = 0;
        if (info.titleText() != null) {
            boxWidth = Math.min(maxWidth,
                    (iconWidth > 0 ? iconWidth + TITLE_GAP : 0) + mc.font.width(info.titleText()));
            boxHeight = titleHeight;
        }
        for (int i = 0; i < elements.size(); i++) {
            ProbeElement element = elements.get(i);
            boxWidth = Math.max(boxWidth, ProbeRender.width(element, maxWidth));
            boxHeight += ProbeRender.height(element, maxWidth) + (i == 0 && titleHeight == 0 ? 0 : 2);
        }

        boolean hasBox = info.titleText() != null || !elements.isEmpty();
        int panelWidth = hasBox ? boxWidth + PADDING * 2 : 0;
        int panelHeight = hasBox ? boxHeight + PADDING * 2 : 0;

        int actionWidth = actions.isEmpty() ? 0
                : ProbeActionList.width(actions, ProbeSelection.index());
        int actionHeight = actions.isEmpty() ? 0 : ProbeActionList.height(actions);

        int totalWidth = Math.max(panelWidth, actionWidth + (hasBox ? PADDING * 2 : 0));
        int totalHeight = panelHeight + (actions.isEmpty() ? 0 : ACTION_GAP + actionHeight);

        float scale = (float) (double) ProbeConfig.SCALE.get();
        int screenWidth = (int) (mc.getWindow().getGuiScaledWidth() / scale);
        int screenHeight = (int) (mc.getWindow().getGuiScaledHeight() / scale);

        int x;
        int y;
        switch (ProbeConfig.ANCHOR.get()) {
            case CROSSHAIR_LEFT -> {
                x = screenWidth / 2 - 8 - totalWidth;
                y = screenHeight / 2 - totalHeight / 2;
            }
            case TOP_LEFT -> {
                x = 10;
                y = 10;
            }
            case TOP_RIGHT -> {
                x = screenWidth - totalWidth - 10;
                y = 10;
            }
            case BOTTOM_LEFT -> {
                x = 10;
                y = screenHeight - totalHeight - 10;
            }
            case BOTTOM_RIGHT -> {
                x = screenWidth - totalWidth - 10;
                y = screenHeight - totalHeight - 10;
            }
            case TOP_CENTRE -> {
                x = (screenWidth - totalWidth) / 2;
                y = 12;
            }
            default -> {
                x = screenWidth / 2 + 8;
                y = screenHeight / 2 - totalHeight / 2;
            }
        }
        x += ProbeConfig.OFFSET_X.get();
        y += ProbeConfig.OFFSET_Y.get();

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0f);

        int cursorX = x + PADDING;
        int cursorY = y + PADDING;
        if (hasBox) {
            graphics.fill(x, y, x + panelWidth, y + panelHeight, BACKGROUND);
            graphics.fill(x, y, x + panelWidth, y + 1, BORDER);
            graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, BORDER);
            graphics.fill(x, y, x + 1, y + panelHeight, BORDER);
            graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, BORDER);

            if (info.titleText() != null) {
                int textX = cursorX;
                if (icon != null) {
                    ProbeRender.draw(graphics, icon, cursorX, cursorY, maxWidth, partialTick);
                    textX += iconWidth + TITLE_GAP;
                }
                ProbeText.draw(graphics, mc.font, info.titleText(), textX,
                        cursorY + (titleHeight - 2 - mc.font.lineHeight) / 2, info.titleColour());
                cursorY += titleHeight;
            }
            for (int i = 0; i < elements.size(); i++) {
                ProbeElement element = elements.get(i);
                if (i > 0 || info.titleText() != null) {
                    cursorY += 2;
                }
                ProbeRender.draw(graphics, element, cursorX, cursorY, maxWidth, partialTick);
                cursorY += ProbeRender.height(element, maxWidth);
            }
        }

        if (!actions.isEmpty()) {
            ProbeActionList.draw(graphics, actions, ProbeSelection.index(), x + (hasBox ? PADDING : 0),
                    y + panelHeight + (hasBox ? ACTION_GAP : 0), actionWidth, ProbeSelection.offset(),
                    partialTick);
        }

        pose.popPose();
    }
}
