package com.wf.wfballistics.block;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import com.wf.wfballistics.block.entity.MissileListenerDebugBlockEntity;
import com.wf.wfballistics.block.entity.TurretCiwsBlockEntity;
import com.wf.wfballistics.block.entity.TurretInterceptorNormalBlockEntity;
import com.wf.wfballistics.block.entity.TurretInterceptorSupersonicBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, WFBallistics.MODID);

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }    public static final RegistryObject<BlockEntityType<MissileListenerDebugBlockEntity>> MISSILE_LISTENER_DEBUG =
            BLOCK_ENTITIES.register("missile_listener_debug", () -> BlockEntityType.Builder.of(
                    MissileListenerDebugBlockEntity::new, ModBlocks.MISSILE_LISTENER_DEBUG.get()).build(null));

    public static final RegistryObject<BlockEntityType<MissileDispenserBlockEntity>> MISSILE_DISPENSER =
            BLOCK_ENTITIES.register("missile_dispenser", () -> BlockEntityType.Builder.of(
                    MissileDispenserBlockEntity::new, ModBlocks.MISSILE_DISPENSER.get()).build(null));

    public static final RegistryObject<BlockEntityType<TurretCiwsBlockEntity>> TURRET_CIWS =
            BLOCK_ENTITIES.register("turret_ciws", () -> BlockEntityType.Builder.of(
                    TurretCiwsBlockEntity::new, ModBlocks.TURRET_CIWS.get()).build(null));

    public static final RegistryObject<BlockEntityType<TurretInterceptorNormalBlockEntity>> TURRET_INTERCEPTOR =
            BLOCK_ENTITIES.register("turret_interceptor", () -> BlockEntityType.Builder.of(
                    TurretInterceptorNormalBlockEntity::new, ModBlocks.TURRET_INTERCEPTOR.get()).build(null));

    public static final RegistryObject<BlockEntityType<TurretInterceptorSupersonicBlockEntity>> TURRET_INTERCEPTOR_SUPERSONIC =
            BLOCK_ENTITIES.register("turret_interceptor_supersonic", () -> BlockEntityType.Builder.of(
                    TurretInterceptorSupersonicBlockEntity::new, ModBlocks.TURRET_INTERCEPTOR_SUPERSONIC.get()).build(null));


}
