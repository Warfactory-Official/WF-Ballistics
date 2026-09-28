package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wf.wflib.WFLib;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;

import java.util.Locale;

/** {@code /wfview}: an {@link OffscreenView} blitted top-left, with CPU/GPU pass timing. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class OffscreenViewDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "offscreen_debug");
    /** GPU timestamp pairs, read {@code FRAMES-1} frames late => no stall. GL_TIME_ELAPSED FORBIDDEN: vanilla
     *  {@code TimerQuery} (F3 / metrics recording) holds one open across the frame => begin fails, end closes theirs. */
    private static final int FRAMES = 4;

    @Nullable
    private static OffscreenView view;
    @Nullable
    private static TextureTarget target;
    private static final FeedTexture TEX = new FeedTexture();
    private static boolean registered;
    /** null => follow the player's eye. */
    @Nullable
    private static Vec3 fixedPos;
    private static float yaw;
    private static float pitch;
    private static float roll;
    private static float fov = 70.0f;

    private static int[] queries;
    private static final boolean[] PENDING = new boolean[FRAMES];
    private static int frame;
    private static long cpuNanos;
    private static long gpuNanos;
    private static int cpuSamples;
    private static int gpuSamples;
    private static String entities = "";
    /** Rest of the frame (GameRenderer.render + GUI), Pre-after-pass -> Post. */
    private static long frameStart;
    private static long frameNanos;
    private static int frameSamples;

    private OffscreenViewDebug() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("wfview")
                .then(Commands.literal("off").executes(ctx -> {
                    close();
                    return reply(ctx, "offscreen view off");
                }))
                .then(Commands.literal("stats").executes(OffscreenViewDebug::stats))
                .then(Commands.literal("shared")
                        .then(Commands.argument("size", IntegerArgumentType.integer(16, 4096))
                                .executes(ctx -> start(ctx, OffscreenView.sharedGrid()))
                                .then(Commands.argument("height", IntegerArgumentType.integer(16, 4096))
                                        .executes(ctx -> start(ctx, OffscreenView.sharedGrid())))))
                .then(Commands.literal("own")
                        .then(Commands.argument("size", IntegerArgumentType.integer(16, 4096))
                                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                                        .executes(ctx -> start(ctx, OffscreenView.ownGrid(
                                                IntegerArgumentType.getInteger(ctx, "distance")))))))
                .then(Commands.literal("eye")
                        .then(Commands.argument("fov", FloatArgumentType.floatArg(1.0f, 170.0f))
                                .executes(ctx -> {
                                    fixedPos = null;
                                    fov = FloatArgumentType.getFloat(ctx, "fov");
                                    return reply(ctx, "offscreen view follows the eye, fov " + fov);
                                })))
                .then(Commands.literal("at")
                        .then(Commands.argument("x", FloatArgumentType.floatArg())
                        .then(Commands.argument("y", FloatArgumentType.floatArg())
                        .then(Commands.argument("z", FloatArgumentType.floatArg())
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0f, 90.0f))
                        .then(Commands.argument("roll", FloatArgumentType.floatArg())
                        .then(Commands.argument("fov", FloatArgumentType.floatArg(1.0f, 170.0f))
                                .executes(ctx -> {
                                    fixedPos = new Vec3(FloatArgumentType.getFloat(ctx, "x"),
                                            FloatArgumentType.getFloat(ctx, "y"), FloatArgumentType.getFloat(ctx, "z"));
                                    yaw = FloatArgumentType.getFloat(ctx, "yaw");
                                    pitch = FloatArgumentType.getFloat(ctx, "pitch");
                                    roll = FloatArgumentType.getFloat(ctx, "roll");
                                    fov = FloatArgumentType.getFloat(ctx, "fov");
                                    return reply(ctx, "offscreen view fixed at " + fixedPos);
                                })))))))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, OffscreenView created) {
        int size = IntegerArgumentType.getInteger(ctx, "size");
        int height = ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("height"))
                ? IntegerArgumentType.getInteger(ctx, "height") : size;
        close();
        view = created;
        target = new TextureTarget(size, height, true, Minecraft.ON_OSX);
        target.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        TEX.adopt(target.getColorTextureId());
        if (!registered) {
            Minecraft.getInstance().getTextureManager().register(TEXTURE, TEX);
            registered = true;
        }
        resetStats();
        return reply(ctx, "offscreen view " + size + "x" + height);
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        String text = String.format(Locale.ROOT, "offscreen %s: cpu %.3f ms (n=%d) gpu %.3f ms (n=%d) %s; rest of frame cpu %.3f ms",
                target == null ? "off" : target.width + "x" + target.height,
                cpuSamples == 0 ? 0.0 : cpuNanos / 1e6 / cpuSamples, cpuSamples,
                gpuSamples == 0 ? 0.0 : gpuNanos / 1e6 / gpuSamples, gpuSamples, entities,
                frameSamples == 0 ? 0.0 : frameNanos / 1e6 / frameSamples);
        LOGGER.info(text);
        resetStats();
        return reply(ctx, text);
    }

    private static void resetStats() {
        cpuNanos = 0;
        gpuNanos = 0;
        cpuSamples = 0;
        gpuSamples = 0;
        frameNanos = 0;
        frameSamples = 0;
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (view == null || target == null || mc.player == null) {
            frameStart = System.nanoTime();
            return;
        }
        if (queries == null) {
            queries = new int[FRAMES * 2];
            GL15.glGenQueries(queries);
        }
        int slot = frame++ % FRAMES;
        if (PENDING[slot]) {
            gpuNanos += GL33.glGetQueryObjecti64(queries[slot * 2 + 1], GL15.GL_QUERY_RESULT)
                    - GL33.glGetQueryObjecti64(queries[slot * 2], GL15.GL_QUERY_RESULT);
            gpuSamples++;
            PENDING[slot] = false;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 pos = fixedPos != null ? fixedPos : mc.player.getEyePosition(partial);
        float y = fixedPos != null ? yaw : mc.player.getViewYRot(partial);
        float p = fixedPos != null ? pitch : mc.player.getViewXRot(partial);
        float r = fixedPos != null ? roll : 0.0f;
        long start = System.nanoTime();
        GL33.glQueryCounter(queries[slot * 2], GL33.GL_TIMESTAMP);
        boolean drawn = view.render(pos, y, p, r, fov, target, event.getPartialTick());
        GL33.glQueryCounter(queries[slot * 2 + 1], GL33.GL_TIMESTAMP);
        if (drawn) {
            entities = mc.levelRenderer.getEntityStatistics();
            cpuNanos += System.nanoTime() - start;
            cpuSamples++;
            PENDING[slot] = true;
        }
        frameStart = System.nanoTime();
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (frameStart != 0) {
            frameNanos += System.nanoTime() - frameStart;
            frameSamples++;
            frameStart = 0;
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (target == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.getWindow().getGuiScale();
        float h = (float) (Math.min(target.height, mc.getWindow().getHeight() / 2) / scale);
        float w = h * target.width / target.height;
        FeedBlit.draw(TEXTURE, event.getGuiGraphics().pose().last().pose(), 4.0f, 4.0f, 4.0f + w, 4.0f + h);
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }

    private static void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (target != null) {
            target.destroyBuffers();
            target = null;
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }
}
