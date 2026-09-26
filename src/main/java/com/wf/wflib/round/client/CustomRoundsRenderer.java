package com.wf.wflib.round.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wflib.WFLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Entity-less rounds with a {@link RoundRenderer}. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class CustomRoundsRenderer {

    private CustomRoundsRenderer() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || ClientRounds.live().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack stack = event.getPoseStack();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        boolean drew = false;
        for (ClientRounds.Round r : ClientRounds.live()) {
            RoundRenderer renderer = RoundRenderers.get(r.preset.id());
            if (renderer == null) {
                continue;
            }
            Vec3 v = new Vec3(r.vx, r.vy, r.vz);
            Vec3 nose = v.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : v.normalize();
            at.set(r.x, r.y, r.z);
            stack.pushPose();
            stack.translate(r.ix(pt) - cam.x, r.iy(pt) - cam.y, r.iz(pt) - cam.z);
            renderer.render(stack, buffers, LevelRenderer.getLightColor(mc.level, at), nose, false);
            stack.popPose();
            drew = true;
        }
        if (drew) {
            buffers.endBatch();
        }
    }
}
