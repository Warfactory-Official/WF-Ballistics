package com.wf.wflib.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.WFLib;
import com.wf.wflib.demolition.ChargeOverlayTracker;
import com.wf.wflib.demolition.MiningChargeBlock;
import com.wf.wflib.demolition.MiningChargeBlockEntity;
import org.joml.Matrix4f;

import java.util.Set;
import java.util.UUID;

/**
 * While the player sneaks, draws a translucent cube around each mining charge they placed, sized to its blast
 * radius, so charges can be spaced without overlap.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ExplosiveOverlayRenderer {

    private ExplosiveOverlayRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !player.isShiftKeyDown()) {
            return;
        }
        Set<BlockPos> positions = ChargeOverlayTracker.positions();
        if (positions.isEmpty()) {
            return;
        }

        UUID self = player.getUUID();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(WFRenderTypes.EXPLOSIVE_OVERLAY);
        Matrix4f pose = poseStack.last().pose();

        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof MiningChargeBlock charge)) {
                ChargeOverlayTracker.remove(pos); // stale (block gone / chunk unloaded); self-heal
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof MiningChargeBlockEntity be) || !self.equals(be.getPlacer())) {
                continue;
            }
            int r = charge.getRadius();
            float x0 = (float) (pos.getX() - r - cam.x);
            float y0 = (float) (pos.getY() - r - cam.y);
            float z0 = (float) (pos.getZ() - r - cam.z);
            float x1 = (float) (pos.getX() + r + 1 - cam.x);
            float y1 = (float) (pos.getY() + r + 1 - cam.y);
            float z1 = (float) (pos.getZ() + r + 1 - cam.z);
            // Tier 2 reads cyan, tier 1 reads danger-red.
            boolean deep = charge.getTier() >= 2;
            float cr = deep ? 0.2F : 1.0F;
            float cg = deep ? 0.8F : 0.25F;
            float cb = deep ? 1.0F : 0.1F;
            box(vc, pose, x0, y0, z0, x1, y1, z1, cr, cg, cb, 0.2F);
        }
        buffers.endBatch(WFRenderTypes.EXPLOSIVE_OVERLAY);
    }

    /** Reusable translucent-box emitter (cull is off, so winding is irrelevant). Cull is off. */
    static void box(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                    float r, float g, float b, float a) {
        // Cull is off, so winding is irrelevant; 6 quads.
        v(vc, m, x0, y0, z0, r, g, b, a);
        v(vc, m, x1, y0, z0, r, g, b, a);
        v(vc, m, x1, y0, z1, r, g, b, a);
        v(vc, m, x0, y0, z1, r, g, b, a); // bottom
        v(vc, m, x0, y1, z0, r, g, b, a);
        v(vc, m, x0, y1, z1, r, g, b, a);
        v(vc, m, x1, y1, z1, r, g, b, a);
        v(vc, m, x1, y1, z0, r, g, b, a); // top
        v(vc, m, x0, y0, z0, r, g, b, a);
        v(vc, m, x0, y1, z0, r, g, b, a);
        v(vc, m, x1, y1, z0, r, g, b, a);
        v(vc, m, x1, y0, z0, r, g, b, a); // north
        v(vc, m, x0, y0, z1, r, g, b, a);
        v(vc, m, x1, y0, z1, r, g, b, a);
        v(vc, m, x1, y1, z1, r, g, b, a);
        v(vc, m, x0, y1, z1, r, g, b, a); // south
        v(vc, m, x0, y0, z0, r, g, b, a);
        v(vc, m, x0, y0, z1, r, g, b, a);
        v(vc, m, x0, y1, z1, r, g, b, a);
        v(vc, m, x0, y1, z0, r, g, b, a); // west
        v(vc, m, x1, y0, z0, r, g, b, a);
        v(vc, m, x1, y1, z0, r, g, b, a);
        v(vc, m, x1, y1, z1, r, g, b, a);
        v(vc, m, x1, y0, z1, r, g, b, a); // east
    }

    private static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z,
                          float r, float g, float b, float a) {
        vc.addVertex(m, x, y, z).setColor(r, g, b, a);
    }
}
