package com.wf.wflib;

import com.mojang.logging.LogUtils;
import com.wf.wflib.client.gui.DronePadScreen;
import com.wf.wflib.client.gui.MissileDispenserScreen;
import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.client.render.BombletRenderer;
import com.wf.wflib.client.render.CameraMonitorRenderer;
import com.wf.wflib.client.render.RadarScopeRenderer;
import com.wf.wflib.client.render.CrateRenderer;
import com.wf.wflib.client.render.DroneDebrisVisual;
import com.wf.wflib.client.render.DroneVisual;
import com.wf.wflib.client.model.GlyphidModel;
import com.wf.wflib.client.model.MineRigs;
import com.wf.wflib.client.render.MineItemRenderers;
import com.wf.wflib.client.model.PartRigs;
import com.wf.wflib.client.render.GlyphidVisual;
import com.wf.wflib.client.render.MineVisual;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.client.render.EntityTorexRender;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.mine.MineEntity;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.menu.ModMenus;
import com.wf.wflib.kinetic.KineticShellEntity;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

import java.util.Map;

// This annotation tells NeoForge to only execute this class on the physical Client game instance
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class WFLibClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.STEALTH_MISSILE.get(), MissileRenderer::new);

        // Bomblets are simple tumbling orange cubes (fragmentation payload).
        event.registerEntityRenderer(ModEntities.BOMBLET.get(),
                BombletRenderer::new);

        // Mines draw through flywheel (see MineVisual).
        event.registerEntityRenderer(ModEntities.MINE.get(), NoopRenderer::new);

        // Drones draw through flywheel (see DroneVisual); crates use the standard block-ish renderer.
        event.registerEntityRenderer(ModEntities.DRONE.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.DRONE_DEBRIS.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.CRATE.get(), CrateRenderer::new);

        // Mist clouds draw nothing themselves: they are pure particle effects (see MistClientFX).
        event.registerEntityRenderer(ModEntities.MIST.get(),
                NoopRenderer::new);

        event.registerEntityRenderer(ModEntities.FIRE_LINGERING.get(),
                NoopRenderer::new);

        for (GlyphidCaste caste : GlyphidCaste.VALUES) {
            event.registerEntityRenderer(caste.type(), NoopRenderer::new);
        }
        event.registerEntityRenderer(ModEntities.GLYPHID_WAYPOINT.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.GLYPHID_BOMB.get(), NoopRenderer::new);

        // Benchmark targets are server-side furniture; nothing to draw.
        event.registerEntityRenderer(ModEntities.DEBUG_DUMMY.get(), NoopRenderer::new);

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
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        BakedModel template = missileItemTemplate(models);
        if (template == null) {
            return;
        }
        for (MissilePreset preset : MissilePresetRegistry.all()) {
            models.put(missileItemModel(preset.id().getPath()), template);
        }
    }

    private static BakedModel missileItemTemplate(Map<ModelResourceLocation, BakedModel> models) {
        for (MissilePreset preset : MissilePresetRegistry.all()) {
            BakedModel model = models.get(missileItemModel(preset.id().getPath()));
            if (model != null && model.isCustomRenderer()) {
                return model;
            }
        }
        return null;
    }

    private static ModelResourceLocation missileItemModel(String presetId) {
        return ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "missile_" + presetId));
    }

    /** Rebuild the mine rigs after a resource reload. */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((barrier, manager, prepProfiler, reloadProfiler, background, game) ->
                barrier.wait(net.minecraft.Util.NIL_UUID)
                        .thenRunAsync(MineRigs::init, game));
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MISSILE_DISPENSER.get(), MissileDispenserScreen::new);
        event.register(ModMenus.DRONE_PAD.get(), DronePadScreen::new);
    }

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {

        event.enqueueWork(() -> {
            PartRigs.init();
            MineRigs.init();
            MineItemRenderers.init();
            GlyphidModel.init();

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

            VisualizerRegistry.setVisualizer(ModEntities.KINETIC_SHELL.get(),
                    new EntityVisualizer<KineticShellEntity>() {
                        @Override
                        public EntityVisual<? super KineticShellEntity> createVisual(VisualizationContext ctx,
                                                                                     KineticShellEntity entity,
                                                                                     float partialTick) {
                            return new MissileVisual(ctx, entity);
                        }

                        @Override
                        public boolean skipVanillaRender(KineticShellEntity entity) {
                            return false;
                        }
                    });

            VisualizerRegistry.setVisualizer(ModEntities.MINE.get(), new EntityVisualizer<MineEntity>() {
                @Override
                public EntityVisual<? super MineEntity> createVisual(VisualizationContext ctx, MineEntity entity,
                                                                     float partialTick) {
                    return new MineVisual(ctx, entity);
                }

                @Override
                public boolean skipVanillaRender(MineEntity entity) {
                    return true;
                }
            });

            VisualizerRegistry.setVisualizer(ModEntities.DRONE.get(), new EntityVisualizer<DroneEntity>() {
                @Override
                public EntityVisual<? super DroneEntity> createVisual(VisualizationContext ctx, DroneEntity entity,
                                                                      float partialTick) {
                    return new DroneVisual(ctx, entity);
                }

                @Override
                public boolean skipVanillaRender(DroneEntity entity) {
                    return true;
                }
            });

            VisualizerRegistry.setVisualizer(ModEntities.DRONE_DEBRIS.get(),
                    new EntityVisualizer<com.wf.wflib.drone.DroneDebrisEntity>() {
                        @Override
                        public EntityVisual<? super com.wf.wflib.drone.DroneDebrisEntity> createVisual(
                                VisualizationContext ctx, com.wf.wflib.drone.DroneDebrisEntity entity,
                                float partialTick) {
                            return new DroneDebrisVisual(ctx, entity);
                        }

                        @Override
                        public boolean skipVanillaRender(com.wf.wflib.drone.DroneDebrisEntity entity) {
                            return true;
                        }
                    });

            for (GlyphidCaste caste : GlyphidCaste.VALUES) {
                bindGlyphid(caste.type(), caste);
            }
        });
    }

    /**
     * The mod's first {@link net.minecraft.client.renderer.blockentity.BlockEntityRenderer}: the in-world scope
     * display.
     */
    @SubscribeEvent
    public static void registerBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.RADAR_SCOPE.get(), RadarScopeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.CAMERA_MONITOR.get(), CameraMonitorRenderer::new);
    }

    /**
     * Generic so the wildcard on {@link GlyphidCaste#type()} has somewhere to be captured.
     */
    private static <T extends EntityGlyphid> void bindGlyphid(EntityType<T> type, GlyphidCaste caste) {
        VisualizerRegistry.setVisualizer(type, new EntityVisualizer<T>() {
            @Override
            public EntityVisual<? super T> createVisual(VisualizationContext ctx, T entity, float partialTick) {
                return new GlyphidVisual(ctx, entity, caste);
            }

            @Override
            public boolean skipVanillaRender(T entity) {
                return true;
            }
        });
    }
}