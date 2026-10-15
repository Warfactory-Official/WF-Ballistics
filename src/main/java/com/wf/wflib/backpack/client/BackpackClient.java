package com.wf.wflib.backpack.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.direct.ArmorAppearance;
import com.wf.gemrender.direct.GemRenderArmorModel;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.WFLib;
import com.wf.wflib.backpack.BackpackItem;
import com.wf.wflib.backpack.BackpackPackets;
import com.wf.wflib.backpack.Backpacks;
import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeContext;
import com.wf.wflib.probe.ProbeNode;
import com.wf.wflib.probe.ProbeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BackpackClient {
    private static final int NODE = 0xFF9ACD60;
    private static final int LOCKED = 0xFFE0B040;
    /** Pack centre, vanilla body-part space (px / 16, +Z = back). */
    private static final Vector3f PACK = new Vector3f(0, 0.4F, 0.32F);
    private static final Map<Entity, Drawn> BACKS = new WeakHashMap<>();

    public static final KeyMapping WEAR = new KeyMapping("key.wflib.backpack", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.wflib");

    private BackpackClient() {
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) {
        e.register(WEAR);
    }

    @SubscribeEvent
    public static void screens(RegisterMenuScreensEvent e) {
        e.register(Backpacks.MENU.get(), BackpackScreen::new);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.AddLayers e) {
        for (PlayerSkin.Model skin : e.getSkins()) {
            if (e.getSkin(skin) instanceof LivingEntityRenderer<?, ?> r) {
                addLayer(r);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addLayer(LivingEntityRenderer r) {
        r.addLayer(new Layer(r));
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        Backpacks.panelShown = BackpackPanel::shown;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post t) -> {
            while (WEAR.consumeClick()) {
                PacketDistributor.sendToServer(new BackpackPackets.Action(BackpackPackets.Action.Kind.TOGGLE_WORN));
            }
        });
        NeoForge.EVENT_BUS.addListener(BackpackPanel::background);
        NeoForge.EVENT_BUS.addListener(BackpackPanel::foreground);
        NeoForge.EVENT_BUS.addListener(BackpackPanel::scrolled);
        NeoForge.EVENT_BUS.addListener(BackpackPanel::pressed);
        NeoForge.EVENT_BUS.addListener(BackpackPanel::dragged);
        NeoForge.EVENT_BUS.addListener(BackpackPanel::released);
        ProbeRegistry.addEntityNodes(entity -> entity instanceof Player p && !Backpacks.worn(p).isEmpty(),
                BackpackClient::node, ProbeRegistry.CAPABILITY_PRIORITY);
    }

    /** Node on the drawn pack (last {@link Layer} draw); not drawn lately => none. */
    private static void node(List<ProbeNode> out, ProbeContext ctx, Entity entity) {
        Drawn at = BACKS.get(entity);
        if (entity == ctx.player() || at == null || ctx.level().getGameTime() - at.tick > 2) {
            return;
        }
        ItemStack worn = Backpacks.worn((Player) entity);
        out.add(new ProbeNode("backpack", at.pos, info -> {
            info.title(worn.getHoverName());
            ProbeAction open = ProbeAction.of(Backpacks.OPEN,
                    Component.translatable("wflib.backpack.open", worn.getHoverName()));
            info.action(Backpacks.locked(worn) ? open.unavailable(Component.translatable("wflib.backpack.locked")) : open);
        }).colour(Backpacks.locked(worn) ? LOCKED : NODE).reach(Backpacks.VIEW_RANGE + 1).radius(0.25));
    }

    private record Drawn(Vec3 pos, long tick) {
    }

    /** Worn backpack on the body bone (armour frame, chest slot); bends with the wearer rig. */
    private static final class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        private static final Map<ResourceLocation, ModelCache.Handle<GemRenderGltfModel>> MODELS = new HashMap<>();
        private final GemRenderArmorModel model = new GemRenderArmorModel(new ArmorAppearance() {
            @Nullable
            @Override
            public GemRenderGltfModel model(@Nullable LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
                ResourceLocation id = ((BackpackItem) stack.getItem()).model();
                return id == null ? null : MODELS.computeIfAbsent(id, GemRenderModels::armor).get();
            }
        });

        Layer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                           float limbSwing, float limbSwingAmount, float partialTick, float age, float headYaw,
                           float headPitch) {
            ItemStack worn = Backpacks.worn(player);
            if (!(worn.getItem() instanceof BackpackItem item) || item.model() == null) {
                return;
            }
            pose.pushPose();
            getParentModel().body.translateAndRotate(pose);
            Vector3f p = pose.last().pose().transformPosition(PACK, new Vector3f());
            pose.popPose();
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            BACKS.put(player, new Drawn(cam.add(p.x, p.y, p.z), player.level().getGameTime()));
            HumanoidModel m = model.prepare(player, worn, EquipmentSlot.CHEST);
            getParentModel().copyPropertiesTo(m);
            m.renderToBuffer(pose, null, light, OverlayTexture.NO_OVERLAY, -1);
        }
    }
}
