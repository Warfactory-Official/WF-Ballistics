package com.wf.wfballistics;

import com.wf.wfballistics.entity.EntityNukeExplosionMK5;
import com.wf.wfballistics.entity.EntityNukeTorex;
import com.wf.wfballistics.entity.MistEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.eventbus.api.IEventBus;

public class ModEntities {
    // 1. Create the DeferredRegister for Entity Types
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, WFBallistics.MODID);

    // 2. Register your specific entity
    // Replace 'YourEntityClass::new' with your actual custom entity class constructor later
    // Fallback size only: MissileEntity#makeBoundingBox fits the real AABB to the oriented model each tick.
    public static final RegistryObject<EntityType<MissileEntity>> STEALTH_MISSILE =
            ENTITY_TYPES.register("missile", () -> EntityType.Builder.of(MissileEntity::new, MobCategory.MISC)
                    .sized(2.0F, 2.0F)
                    .clientTrackingRange(16)
                    .build("missile")
            );

    // Mist effect cloud: a stationary, fluid-imbued area entity.
    public static final RegistryObject<EntityType<MistEntity>> MIST =
            ENTITY_TYPES.register("mist", () -> EntityType.Builder.<MistEntity>of(MistEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .noSummon()
                    .build("mist")
            );

    // Nuclear detonation driver: a server-side, multi-tick ray explosion. Renders nothing.
    public static final RegistryObject<EntityType<EntityNukeExplosionMK5>> NUKE_EXPLOSION =
            ENTITY_TYPES.register("nuke_explosion", () -> EntityType.Builder.<EntityNukeExplosionMK5>of(EntityNukeExplosionMK5::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .build("nuke_explosion")
            );

    // Torex: the toroidal-convection mushroom cloud effect. Large box, far tracking, never moves.
    public static final RegistryObject<EntityType<EntityNukeTorex>> NUKE_TOREX =
            ENTITY_TYPES.register("torex", () -> EntityType.Builder.<EntityNukeTorex>of(EntityNukeTorex::new, MobCategory.MISC)
                    .noSave()
                    .fireImmune()
                    .sized(20F, 40F)
                    .clientTrackingRange(64)
                    .updateInterval(Integer.MAX_VALUE)
                    .setShouldReceiveVelocityUpdates(false)
                    .build("torex")
            );

    // 3. Register the DeferredRegister with the Mod Event Bus
    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}
