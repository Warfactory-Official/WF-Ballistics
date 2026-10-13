package com.wf.wflib.pda.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.direct.DirectRenderer;
import com.wf.gemrender.direct.GemRenderItemRenderer;
import com.wf.gemrender.direct.ItemAppearance;
import com.wf.gemrender.direct.TextureSlots;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.texture.VariantUv;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.view.ArmViewPose;
import com.wf.wflib.client.view.PlayerArms;
import com.wf.wflib.ui.UiContext;
import com.wf.wflib.ui.UiSurfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * First person: PDA two-handed in the HAND pass, rest pose low and tilted, raised = screen square to the eye;
 * {@code screen} texture slot = the {@link PdaShell} surface, emissive. Elsewhere: the model, screen not drawn.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class PdaRenderer {
    public static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "models/pda/pda.glb");
    /** {@link UiSurfaces} key. */
    static final Object SURFACE = new Object();
    private static final String SCREEN = "screen";
    /** Placeholder model's {@code screen} quad width, m. */
    private static final float SCREEN_WIDTH = 0.224f;
    /** Surface px per shell unit, integer => even font pixels; ~1:1 with the raised screen on the window. */
    private static final int MAX_PIXELS_PER_UNIT = 8;
    private static final int REST_FPS = 20;

    /** View space, m / degrees about X (screen normal toward +Y as it grows negative). */
    private static final Vector3f REST = new Vector3f(0, -0.31f, -0.42f);
    private static final float REST_TILT = -50;
    private static final Vector3f RAISED = new Vector3f(0, -0.03f, -0.29f);
    private static final float RAISED_TILT = -4;
    /** Viewmodel scale over the metric model: screen ~half the view wide when raised. */
    private static final float VIEW_SCALE = 1.9f;
    /** Item cell fit: longest side of the body, m. */
    private static final float BODY_SIZE = 0.232f;

    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f LOCAL = new Matrix4f();
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f UPPER = new Matrix4f();
    private static final Matrix4f LOWER = new Matrix4f();
    private static final Vector3f AT = new Vector3f();
    private static final Vector3f WRIST = new Vector3f();
    private static final Vector3f SHOULDER = new Vector3f();
    private static final Vector3f ELBOW = new Vector3f();
    private static final Vector3f[] GRIPS = {new Vector3f(), new Vector3f()};

    private static ModelCache.Handle<GemRenderGltfModel> handle;
    private static GemRenderGltfModel gripsOf;
    private static GemRenderItemRenderer itemRenderer;
    private static ResourceLocation surface;
    private static int pixelsPerUnit = 1;
    private static final TextureSlots SLOTS = new TextureSlots() {
        @Override
        public ResourceLocation texture(String slot) {
            return SCREEN.equals(slot) ? surface : null;
        }

        @Override
        public boolean emissive(String slot) {
            return true;
        }
    };

    private PdaRenderer() {
    }

    @Nullable
    static GemRenderGltfModel model() {
        if (handle == null) {
            handle = GemRenderModels.handle(MODEL);
        }
        return handle.get();
    }

    public static GemRenderItemRenderer itemRenderer() {
        if (itemRenderer == null) {
            itemRenderer = new GemRenderItemRenderer(new ItemAppearance() {
                @Override
                public GemRenderGltfModel model(ItemStack stack, ItemDisplayContext context) {
                    return PdaRenderer.model();
                }

                @Override
                public void transform(ItemStack stack, ItemDisplayContext context, PoseStack pose) {
                    float fit = 0.9f / BODY_SIZE;
                    pose.translate(0.5f, 0.5f, 0.5f);
                    pose.scale(fit, fit, fit);
                }
            });
        }
        return itemRenderer;
    }

    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !PdaClient.held()) {
            return;
        }
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND || PdaClient.fullscreenFeed() != 0) {
            return;
        }
        GemRenderGltfModel model = model();
        if (model == null) {
            return;
        }
        if (gripsOf != model) {
            NodeTable table = model.layout().nodeTable();
            grip(table, "grip_right", GRIPS[0]);
            grip(table, "grip_left", GRIPS[1]);
            gripsOf = model;
        }
        float t = PdaClient.raise(event.getPartialTick());
        t = t * t * (3 - 2 * t);
        REST.lerp(RAISED, t, AT);
        float tilt = REST_TILT + (RAISED_TILT - REST_TILT) * t;
        LOCAL.translation(AT).rotateX((float) Math.toRadians(tilt)).scale(VIEW_SCALE);
        VIEW.set(event.getPoseStack().last().pose());
        POSE.set(VIEW).mul(LOCAL);

        pixelsPerUnit = pixelsPerUnit(mc);
        surface = UiSurfaces.request(SURFACE, PdaShell.layout(), UiContext.EMPTY,
                (int) PdaShell.WIDTH * pixelsPerUnit, (int) PdaShell.HEIGHT * pixelsPerUnit, PdaShell.WIDTH, PdaShell.HEIGHT, PdaClient.focused() ? 0 : REST_FPS,
                PdaShell.CLEAR);
        int light = event.getPackedLight();
        DirectRenderer.submit(model, null, 0, POSE, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.HAND,
                VariantUv.NONE, SLOTS);
        arms(player, light);
    }

    /** Surface px per {@link PdaShell} unit, last frame. */
    static int pixelsPerUnit() {
        return pixelsPerUnit;
    }

    /** Raised screen's projected width / shell units, rounded; hand projection = fixed FOV => stable per window. */
    private static int pixelsPerUnit(Minecraft mc) {
        float ndcHalf = RenderSystem.getProjectionMatrix().m00() * SCREEN_WIDTH * VIEW_SCALE / 2 / -RAISED.z;
        int pixels = Math.round(ndcHalf * mc.getWindow().getWidth());
        return Math.clamp(Math.round((float) pixels / PdaShell.WIDTH), 1, MAX_PIXELS_PER_UNIT);
    }

    private static void grip(NodeTable table, String name, Vector3f out) {
        int slot = table.slotOfName(name);
        if (slot < 0) {
            throw new IllegalStateException(MODEL + ": no node " + name);
        }
        out.set(table.restTranslation(slot, 0), table.restTranslation(slot, 1), table.restTranslation(slot, 2));
    }

    private static void arms(LocalPlayer player, int light) {
        PlayerArms arms = PlayerArms.get();
        if (arms == null) {
            return;
        }
        for (int side = 0; side < 2; side++) {
            LOCAL.transformPosition(GRIPS[side], WRIST);
            ArmViewPose.shoulder(side == 1, SHOULDER);
            ArmViewPose.solve(WRIST, null, SHOULDER, side == 0 ? ArmViewPose.RIGHT_POLE : ArmViewPose.LEFT_POLE,
                    arms.lengths, UPPER, LOWER, ELBOW);
            arms.pose(side, UPPER, LOWER);
        }
        arms.submit(VIEW, light, player.getSkin(), player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE),
                player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
    }
}
