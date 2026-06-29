package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraftforge.api.distmarker.Dist;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

// This annotation tells Forge to only execute this class on the physical Client game instance
@Mod.EventBusSubscriber(modid = WFBallistics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class WFBallisticsClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Tie your custom missile entity to a renderer
        // For a bare minimum test, we will temporarily use vanilla's ThrownItemRenderer
        // (This makes it look like a flying snowball/item so you can see it without writing a custom model file yet!)
        event.registerEntityRenderer(ModEntities.MY_CUSTOM_ENTITY.get(), MissileRenderer::new);

        // Mist clouds draw nothing themselves — they are pure particle effects (see MistClientFX).
        event.registerEntityRenderer(ModEntities.MIST.get(),
                ctx -> new net.minecraft.client.renderer.entity.NoopRenderer<>(ctx));

        // The nuke explosion is server-side block destruction; nothing to draw.
        event.registerEntityRenderer(ModEntities.NUKE_EXPLOSION.get(),
                ctx -> new net.minecraft.client.renderer.entity.NoopRenderer<>(ctx));

        // The Torex mushroom cloud has its own bespoke cloudlet renderer.
        event.registerEntityRenderer(ModEntities.NUKE_TOREX.get(),
                com.wf.wfballistics.client.render.EntityTorexRender::new);

        LOGGER.info("HELLO FROM CLIENT SETUP");

        ModModels.init();
    }
    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("HELLO FROM ONCLIENTSETUP");

        event.enqueueWork(() -> {
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

            VisualizerRegistry.setVisualizer(ModEntities.MY_CUSTOM_ENTITY.get(), visualizer);
        });
    }
}