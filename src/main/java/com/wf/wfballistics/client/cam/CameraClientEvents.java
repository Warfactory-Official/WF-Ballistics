package com.wf.wfballistics.client.cam;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Where a drone feed is rendered, and when it is thrown away. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class CameraClientEvents {

    private CameraClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        CameraFeedCache.renderFrame(event.getPartialTick());
    }

    /** The far side of the bisect. */
    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        FeedAudit.frameEnd();
    }

    /** Close the fog in to the edge of the streamed bubble while a feed is being drawn. */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!FeedPass.active()) {
            return;
        }
        float far = FeedPass.viewDistance() * 16.0f;
        if (event.getFarPlaneDistance() <= far) {
            return;
        }
        event.setNearPlaneDistance(event.getMode() == FogRenderer.FogMode.FOG_SKY
                ? 0.0f : far - Mth.clamp(far / 10.0f, 4.0f, 64.0f));
        event.setFarPlaneDistance(far);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        CameraLink.tick();
        FeedChunks.tick();
    }

    /** Tell the server whether this client can draw terrain streamed for a drone. */
    @SubscribeEvent
    public static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        FeedChunks.announce();
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CameraLink.close();
        CameraFeedCache.clear();
    }
}
