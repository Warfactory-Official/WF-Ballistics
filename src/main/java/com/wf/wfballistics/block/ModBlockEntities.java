package com.wf.wfballistics.block;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.block.entity.MissileListenerDebugBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, WFBallistics.MODID);

    public static final RegistryObject<BlockEntityType<MissileListenerDebugBlockEntity>> MISSILE_LISTENER_DEBUG =
            BLOCK_ENTITIES.register("missile_listener_debug", () -> BlockEntityType.Builder.of(
                    MissileListenerDebugBlockEntity::new, ModBlocks.MISSILE_LISTENER_DEBUG.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
