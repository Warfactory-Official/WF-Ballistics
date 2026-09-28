package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** State of the running {@link OffscreenView} pass. */
public final class FeedPass {

    @Nullable
    private static Vec3 origin;
    private static int viewDistance;
    private static boolean thermal;
    @Nullable
    private static RenderTarget target;

    private FeedPass() {
    }

    /** @param at grid centre; null => main grid (shared) */
    static void begin(@Nullable Vec3 at, int distance, boolean thermalMode, RenderTarget into) {
        origin = at;
        target = into;
        viewDistance = distance;
        thermal = thermalMode;
    }

    static void end() {
        origin = null;
        target = null;
        thermal = false;
    }

    /**
     * @return whether the pass being drawn is a thermal one. Read by {@code MixinParticleEngine}: a
     *      microbolometer has nothing to say about a smoke plume, and the colour heuristic reads bright particles
     *      as warm, so a campfire or a damaged drone smears its own picture with false heat. Optical and lowlight
     *      keep their particles, which are worth having on a reconnaissance camera.
     */
    public static boolean thermal() {
        return thermal;
    }

    /** @return where the pass's own grid is centred; null => main view or a shared-grid pass. */
    @Nullable
    public static Vec3 origin() {
        return origin;
    }

    /** Translucent sort origin: own-grid pass camera, else main camera. */
    public static Vec3 sortOrigin() {
        Vec3 at = origin;
        return at != null ? at : Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    /**
     * @return the feed's framebuffer while a pass runs: {@code MixinMinecraftFeedTarget} answers it for
     *      {@code getMainRenderTarget}. Iris (no pack: {@code VanillaRenderingPipeline.beginLevelRendering}) binds
     *      the main target inside {@code renderLevel} => terrain drawn to the screen, feed = clear colour.
     */
    @Nullable
    public static RenderTarget target() {
        return target;
    }

    public static boolean active() {
        return target != null;
    }

    /** Fog distance of the current pass, in sections. Only meaningful while {@link #active()}. */
    public static int viewDistance() {
        return viewDistance;
    }
}
