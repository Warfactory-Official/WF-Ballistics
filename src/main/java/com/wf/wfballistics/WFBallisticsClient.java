package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.client.gui.MissileDispenserScreen;
import com.wf.wfballistics.client.render.BombletRenderer;
import com.wf.wfballistics.client.render.EntityTorexRender;
import com.wf.wfballistics.item.MissilePreset;
import com.wf.wfballistics.item.MissilePresetRegistry;
import com.wf.wfballistics.menu.ModMenus;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

import java.util.Map;

// This annotation tells Forge to only execute this class on the physical Client game instance
@Mod.EventBusSubscriber(modid = WFBallistics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class WFBallisticsClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Tie your custom missile entity to a renderer
        // For a bare minimum test, we will temporarily use vanilla's ThrownItemRenderer
        // (This makes it look like a flying snowball/item so you can see it without writing a custom model file yet!)
        event.registerEntityRenderer(ModEntities.STEALTH_MISSILE.get(), MissileRenderer::new);

        // Bomblets are simple tumbling orange cubes (fragmentation payload).
        event.registerEntityRenderer(ModEntities.BOMBLET.get(),
                BombletRenderer::new);

        // Mist clouds draw nothing themselves — they are pure particle effects (see MistClientFX).
        event.registerEntityRenderer(ModEntities.MIST.get(),
                NoopRenderer::new);

        event.registerEntityRenderer(ModEntities.FIRE_LINGERING.get(),
                NoopRenderer::new);

        // The nuke explosion is server-side block destruction; nothing to draw.
        event.registerEntityRenderer(ModEntities.NUKE_EXPLOSION.get(),
                NoopRenderer::new);

        // The Torex mushroom cloud has its own bespoke cloudlet renderer.
        event.registerEntityRenderer(ModEntities.NUKE_TOREX.get(),
                EntityTorexRender::new);

        LOGGER.info("HELLO FROM CLIENT SETUP");

        ModModels.init();
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        BakedModel template = missileItemTemplate(models);
        if (template == null) {
            return;
        }
        for (MissilePreset preset : MissilePresetRegistry.all()) {
            models.put(missileItemModel(preset.id().getPath()), template);
        }
    }

    private static BakedModel missileItemTemplate(Map<ResourceLocation, BakedModel> models) {
        for (MissilePreset preset : MissilePresetRegistry.all()) {
            BakedModel model = models.get(missileItemModel(preset.id().getPath()));
            if (model != null && model.isCustomRenderer()) {
                return model;
            }
        }
        return null;
    }

    private static ModelResourceLocation missileItemModel(String presetId) {
        return new ModelResourceLocation(WFBallistics.MODID, "missile_" + presetId, "inventory");
    }

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {

        event.enqueueWork(() -> {
            MenuScreens.register(
                    ModMenus.MISSILE_DISPENSER.get(),
                    MissileDispenserScreen::new);

            EntityVisualizer<MissileEntity> visualizer = new EntityVisualizer<MissileEntity>() {
                @Override
                public EntityVisual<? super MissileEntity> createVisual(VisualizationContext ctx, MissileEntity entity, float partialTick) {
                    return new MissileVisual(ctx, entity);
                }

                @Override
                public boolean skipVanillaRender(MissileEntity entity) {
                    return false;
                }
            };

            VisualizerRegistry.setVisualizer(ModEntities.STEALTH_MISSILE.get(), visualizer);
        });
    }
}