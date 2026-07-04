package com.wf.wfballistics.client.wiaj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;


@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class DebrisManager {

    /**
     * Safety cap so a barrage of explosions can't pile up unbounded debris.
     */
    private static final int MAX_ACTIVE = 256;

    /**
     * Max chunks to tessellate per frame — spreads a burst of debris over a few frames instead of one hitch.
     */
    private static final int BAKE_BUDGET_PER_FRAME = 4;

    private static final List<Debris> ACTIVE = new ArrayList<>();
    private static final List<Debris> PENDING_CLOSE = new ArrayList<>();

    private DebrisManager() {
    }

    public static void add(Debris debris) {
        if (ACTIVE.size() < MAX_ACTIVE) {
            ACTIVE.add(debris);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.level == null) {
            return;
        }
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Debris debris = ACTIVE.get(i);
            debris.tick(mc.level);
            if (debris.isExpired()) {
                ACTIVE.remove(i);
                PENDING_CLOSE.add(debris); // GL buffer closed on the render thread next frame
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (!PENDING_CLOSE.isEmpty()) {
            for (Debris debris : PENDING_CLOSE) {
                debris.close();
            }
            PENDING_CLOSE.clear();
        }
        if (ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Matrix4f projection = event.getProjectionMatrix();
        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();

        // Opaque terrain chunks: one solid-render-state setup for the whole batch.
        RenderType renderType = RenderType.solid();
        renderType.setupRenderState();
        int bakeBudget = BAKE_BUDGET_PER_FRAME;
        for (Debris debris : ACTIVE) {
            if (!debris.isBaked()) {
                if (bakeBudget <= 0) {
                    continue; // defer this chunk's tessellation to a later frame (it just appears a tick late)
                }
                debris.bake();
                bakeBudget--;
            }
            debris.render(poseStack, projection, cam, partialTick);
        }
        VertexBuffer.unbind();
        renderType.clearRenderState();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        // Defer the actual GL disposal to the next render pass (render thread).
        PENDING_CLOSE.addAll(ACTIVE);
        ACTIVE.clear();
    }
}
