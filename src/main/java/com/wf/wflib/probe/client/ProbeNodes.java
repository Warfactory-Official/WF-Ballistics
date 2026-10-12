package com.wf.wflib.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeContext;
import com.wf.wflib.probe.ProbeInfo;
import com.wf.wflib.probe.ProbeNode;
import com.wf.wflib.probe.ProbeRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * In-world probe nodes: dots within a cone of the crosshair, nearest one focused, its panel beside it. Selection,
 * scroll, G and jobs = the crosshair panel's ({@link ProbeSelection}); the use key also activates.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ProbeNodes implements LayeredDraw.Layer {

    private static final double SHOW_COS = Math.cos(Math.toRadians(25));
    private static final double FOCUS_MIN = Math.toRadians(3);
    /** Beyond interaction range (eye -> node), m: nodes sit off surfaces. */
    private static final double REACH_SLACK = 1.5;
    /** Dot -> panel, GUI px. */
    private static final int PANEL_GAP = 8;

    /** GUI-scaled screen point; {@code fade} 1 at the crosshair -> 0 at the cone edge. */
    private record Shown(ProbeNode node, BlockPos pos, int entityId, boolean wantsData, float x, float y,
                         float fade) {
    }

    private static final List<Shown> SHOWN = new ArrayList<>();
    @Nullable
    private static Shown focus;
    @Nullable
    private static ProbeInfo panel;
    /** Use key held since the last node activation: hold-repeat would toggle every 4 ticks. */
    private static boolean held;

    /** A node is focused: the crosshair panel stands down. */
    public static boolean focused() {
        return focus != null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        boolean had = focus != null;
        SHOWN.clear();
        focus = null;
        panel = null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || player.isSpectator() || mc.screen != null) {
            if (had) {
                ProbeSelection.clear();
            }
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        double reach = Math.max(player.entityInteractionRange(), player.blockInteractionRange()) + REACH_SLACK;
        Matrix4f clip = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        float width = mc.getWindow().getGuiScaledWidth();
        float height = mc.getWindow().getGuiScaledHeight();
        HitResult hit = mc.hitResult != null ? mc.hitResult
                : BlockHitResult.miss(eye, Direction.UP, BlockPos.containing(eye));
        AABB box = player.getBoundingBox().inflate(reach);
        List<ProbeNode> nodes = new ArrayList<>();
        Frame frame = new Frame(eye, look, reach, clip, camera, width, height);

        for (Entity entity : level.getEntities(player, box, ProbeRegistry::hasNodes)) {
            BlockPos pos = entity.blockPosition();
            CompoundTag data = ProbeClientData.get(pos, entity.getId());
            nodes.clear();
            ProbeRegistry.collectNodes(nodes, context(player, level, hit, data, partialTick), entity);
            frame.consider(nodes, pos, entity.getId(), ProbeRegistry.wantsEntityData(entity));
        }
        if (ProbeRegistry.anyBlockNodes()) {
            int x0 = (int) Math.floor(box.minX) >> 4, x1 = (int) Math.floor(box.maxX) >> 4;
            int z0 = (int) Math.floor(box.minZ) >> 4, z1 = (int) Math.floor(box.maxZ) >> 4;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        BlockPos pos = blockEntity.getBlockPos();
                        if (!box.contains(pos.getCenter()) || !ProbeRegistry.hasNodes(blockEntity)) {
                            continue;
                        }
                        CompoundTag data = ProbeClientData.get(pos, -1);
                        nodes.clear();
                        ProbeRegistry.collectNodes(nodes, context(player, level, hit, data, partialTick), blockEntity);
                        frame.consider(nodes, pos, -1, ProbeRegistry.wantsBlockData(level, pos,
                                blockEntity.getBlockState(), blockEntity));
                    }
                }
            }
        }
        focus = frame.focus != null ? frame.focus : frame.claimed;
        if (focus == null) {
            if (had) {
                ProbeSelection.clear();
            }
            return;
        }
        if (focus.wantsData) {
            ProbeClientData.request(focus.pos, focus.entityId, level.getGameTime(), ProbeConfig.REFRESH_TICKS.get());
        }
        panel = new ProbeInfo();
        focus.node.fill(panel);
        ProbeSelection.offer(focus.pos, focus.entityId, focus.node.key(), panel.actions());
    }

    private static ProbeContext context(LocalPlayer player, Level level, HitResult hit, CompoundTag data,
                                        float partialTick) {
        return new ProbeContext(player, level, hit, player.getMainHandItem(), player.getOffhandItem(), data,
                partialTick);
    }

    /** Per-frame projection + best-so-far. */
    private static final class Frame {
        private final Vec3 eye;
        private final Vec3 look;
        private final double reach;
        private final Matrix4f clip;
        private final Vec3 camera;
        private final float width;
        private final float height;
        private double best = 1;
        @Nullable
        private Shown focus;
        @Nullable
        private Shown claimed;
        private double claimedDistance = Double.MAX_VALUE;

        Frame(Vec3 eye, Vec3 look, double reach, Matrix4f clip, Vec3 camera, float width, float height) {
            this.eye = eye;
            this.look = look;
            this.reach = reach;
            this.clip = clip;
            this.camera = camera;
            this.width = width;
            this.height = height;
        }

        void consider(List<ProbeNode> nodes, BlockPos pos, int entityId, boolean wantsData) {
            for (ProbeNode node : nodes) {
                Vec3 to = node.at().subtract(eye);
                double distance = to.length();
                double cos = to.dot(look) / distance;
                if (distance > Math.min(reach, node.reach()) || cos < SHOW_COS
                        || node.normal() != null && to.dot(node.normal()) >= 0) {
                    continue;
                }
                Vector4f p = clip.transform(new Vector4f((float) (node.at().x - camera.x),
                        (float) (node.at().y - camera.y), (float) (node.at().z - camera.z), 1));
                if (p.w <= 1e-5F) {
                    continue;
                }
                Shown shown = new Shown(node, pos, entityId, wantsData, (p.x / p.w + 1) * width / 2,
                        (1 - p.y / p.w) * height / 2, (float) ((cos - SHOW_COS) / (1 - SHOW_COS)));
                SHOWN.add(shown);
                double score = Math.acos(Math.min(1, cos)) / Math.max(Math.atan(node.radius() / distance), FOCUS_MIN);
                if (score < best) {
                    best = score;
                    focus = shown;
                }
                if (node.claimed() && distance < claimedDistance) {
                    claimedDistance = distance;
                    claimed = shown;
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (SHOWN.isEmpty() || mc.options.hideGui) {
            return;
        }
        Canvas canvas = Canvas.shared().begin(graphics);
        try {
            for (Shown shown : SHOWN) {
                if (shown == focus) {
                    continue;
                }
                int alpha = (int) (0x30 + 0x90 * shown.fade);
                int x = Math.round(shown.x), y = Math.round(shown.y);
                canvas.fill(x - 1, y - 1, x + 2, y + 2, alpha << 24 | shown.node.colour() & 0xFFFFFF);
            }
            if (focus != null && panel != null) {
                int x = Math.round(focus.x), y = Math.round(focus.y);
                canvas.fill(x - 3, y - 3, x + 4, y + 4, 0xC0000000);
                canvas.fill(x - 2, y - 2, x + 3, y + 3, focus.node.colour());
            }
        } finally {
            canvas.end();
        }
        if (focus == null || panel == null || panel.isEmpty()) {
            return;
        }
        int x = Math.round(focus.x), y = Math.round(focus.y);
        float scale = (float) (double) ProbeConfig.SCALE.get();
        ProbePanel.Size size = ProbePanel.measure(mc, panel, true);
        int screenWidth = (int) (graphics.guiWidth() / scale);
        int screenHeight = (int) (graphics.guiHeight() / scale);
        int px = (int) (x / scale) + PANEL_GAP;
        if (px + size.width() > screenWidth) {
            px = (int) (x / scale) - PANEL_GAP - size.width();
        }
        int py = Math.max(2, Math.min(screenHeight - size.height() - 2, (int) (y / scale) - size.height() / 2));
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0f);
        Canvas panelCanvas = Canvas.shared().begin(graphics);
        try {
            ProbePanel.draw(panelCanvas, graphics, mc, panel, size, px, py, delta.getGameTimeDeltaPartialTick(false), true);
        } finally {
            panelCanvas.end();
        }
        pose.popPose();
    }

    /** Use key on a focused node with a live selected action (or a running job: stops it). Sneak passes. */
    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isUseItem() || focus == null || mc.player == null || mc.player.isShiftKeyDown()) {
            return;
        }
        ProbeAction action = ProbeSelection.current();
        if (!ProbeJobOverlay.running() && (action == null || !action.enabled())) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (held) {
            return;
        }
        held = true;
        ProbeSelection.activate();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (held && !Minecraft.getInstance().options.keyUse.isDown()) {
            held = false;
        }
    }
}
