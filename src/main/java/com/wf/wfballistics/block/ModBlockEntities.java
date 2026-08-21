package com.wf.wfballistics.block;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.block.entity.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WFBallistics.MODID);

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DronePadBlockEntity>> DRONE_PAD =
            BLOCK_ENTITIES.register("drone_pad", () -> BlockEntityType.Builder.of(
                    DronePadBlockEntity::new, ModBlocks.DRONE_PAD.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MissileListenerDebugBlockEntity>> MISSILE_LISTENER_DEBUG =
            BLOCK_ENTITIES.register("missile_listener_debug", () -> BlockEntityType.Builder.of(
                    MissileListenerDebugBlockEntity::new, ModBlocks.MISSILE_LISTENER_DEBUG.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MissileDispenserBlockEntity>> MISSILE_DISPENSER =
            BLOCK_ENTITIES.register("missile_dispenser", () -> BlockEntityType.Builder.of(
                    MissileDispenserBlockEntity::new, ModBlocks.MISSILE_DISPENSER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretCiwsBlockEntity>> TURRET_CIWS =
            BLOCK_ENTITIES.register("turret_ciws", () -> BlockEntityType.Builder.of(
                    TurretCiwsBlockEntity::new, ModBlocks.TURRET_CIWS.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretInterceptorNormalBlockEntity>> TURRET_INTERCEPTOR =
            BLOCK_ENTITIES.register("turret_interceptor", () -> BlockEntityType.Builder.of(
                    TurretInterceptorNormalBlockEntity::new, ModBlocks.TURRET_INTERCEPTOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretInterceptorSupersonicBlockEntity>> TURRET_INTERCEPTOR_SUPERSONIC =
            BLOCK_ENTITIES.register("turret_interceptor_supersonic", () -> BlockEntityType.Builder.of(
                    TurretInterceptorSupersonicBlockEntity::new, ModBlocks.TURRET_INTERCEPTOR_SUPERSONIC.get()).build(null));


}
