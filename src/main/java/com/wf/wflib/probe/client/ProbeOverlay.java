package com.wf.wflib.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.pda.client.PdaClient;
import com.wf.wflib.probe.ProbeContext;
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

/** The panel itself: what the player is looking at, asked about, laid out and drawn. */
public final class ProbeOverlay implements LayeredDraw.Layer {


    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        ProbeSelection.advance();
        if (!ProbeConfig.ENABLED.get() || mc.options.hideGui || mc.level == null || mc.player == null
                || mc.getDebugOverlay().showDebugScreen() || PdaClient.focused()) {
            ProbeSelection.clear();
            return;
        }
        if (ProbeNodes.focused()) {
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
            if (ProbeRegistry.hasNodes(blockEntity)) {
                ProbeSelection.clear();
                return null;
            }
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
            if (ProbeRegistry.hasNodes(entity)) {
                ProbeSelection.clear();
                return null;
            }
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

    /** Places the panel at the configured anchor and draws it. */
    private void paint(GuiGraphics graphics, Minecraft mc, ProbeInfo info, float partialTick) {
        ProbePanel.Size size = ProbePanel.measure(mc, info, false);
        float scale = (float) (double) ProbeConfig.SCALE.get();
        int screenWidth = (int) (mc.getWindow().getGuiScaledWidth() / scale);
        int screenHeight = (int) (mc.getWindow().getGuiScaledHeight() / scale);
        int totalWidth = size.width();
        int totalHeight = size.height();

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
        Canvas canvas = Canvas.shared().begin(graphics);
        try {
            ProbePanel.draw(canvas, graphics, mc, info, size, x, y, partialTick, false);
        } finally {
            canvas.end();
        }
        pose.popPose();
    }
}
