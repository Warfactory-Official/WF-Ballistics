package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Where the world is being rendered from, while a feed is being rendered. */
public final class FeedPass {

    @Nullable
    private static Vec3 origin;
    private static int viewDistance;
    private static boolean thermal;
    @Nullable
    private static RenderTarget target;

    private FeedPass() {
    }

    static void begin(Vec3 at, int distance, boolean thermalMode, RenderTarget into) {
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

    /** @return where the feed's grid should be centred, or null when the main view is being drawn. */
    @Nullable
    public static Vec3 origin() {
        return origin;
    }

    /** Where the camera currently drawing the world is, feed or not. */
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
        return origin != null;
    }

    /** Sections either side of the drone the current feed holds. Only meaningful while {@link #active()}. */
    public static int viewDistance() {
        return viewDistance;
    }
}
