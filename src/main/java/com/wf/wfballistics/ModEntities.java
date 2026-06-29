package com.wf.wfballistics;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.LivingEntity; // Or your specific entity class
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
    public static final RegistryObject<EntityType<MissileEntity>> MY_CUSTOM_ENTITY =
            ENTITY_TYPES.register("missile", () -> EntityType.Builder.of(MissileEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .build("missile")
            );

    // 3. Register the DeferredRegister with the Mod Event Bus
    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}
