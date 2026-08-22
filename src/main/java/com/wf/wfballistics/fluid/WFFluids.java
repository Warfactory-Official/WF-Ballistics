package com.wf.wfballistics.fluid;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Consumer;

public final class WFFluids {

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, WFBallistics.MODID);
    public static final DeferredRegister<net.minecraft.world.level.material.Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, WFBallistics.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, WFBallistics.MODID);

    //Phosgene
    public static final DeferredHolder<FluidType, FluidType> PHOSGENE_TYPE =
            FLUID_TYPES.register("phosgene", () -> new GasFluidType(0xCFE0C0));
    // Mustard gas
    public static final DeferredHolder<FluidType, FluidType> MUSTARD_GAS_TYPE =
            FLUID_TYPES.register("mustard_gas", () -> new GasFluidType(0xB8A038));
    public static final DeferredHolder<FluidType, FluidType> KEROSENE_TYPE =
            FLUID_TYPES.register("kerosene", KeroseneFluidType::new);
    // Glyphid acid
    public static final DeferredHolder<FluidType, FluidType> GLYPHID_ACID_TYPE =
            FLUID_TYPES.register("glyphid_acid", () -> new GasFluidType(0x8CD836));    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid> PHOSGENE =
            FLUIDS.register("phosgene", () -> new BaseFlowingFluid.Source(WFFluids.PHOSGENE_PROPS));

    private WFFluids() {
    }

    public static void register(IEventBus modBus) {
        FLUID_TYPES.register(modBus);
        FLUIDS.register(modBus);
        ITEMS.register(modBus);
    }    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid>FLOWING_PHOSGENE =
            FLUIDS.register("flowing_phosgene", () -> new BaseFlowingFluid.Flowing(WFFluids.PHOSGENE_PROPS));

    public static class GasFluidType extends FluidType {

        private static final ResourceLocation STILL = ResourceLocation.withDefaultNamespace("block/water_still");
        private static final ResourceLocation FLOW = ResourceLocation.withDefaultNamespace("block/water_flow");

        private final int tint;

        public GasFluidType(int rgb) {
            super(FluidType.Properties.create()
                    .density(-2)
                    .viscosity(1)
                    .temperature(300)
                    .canSwim(false)
                    .canDrown(false)
                    .canExtinguish(false)
                    .fallDistanceModifier(0F)
                    .canPushEntity(false)
                    .supportsBoating(false)
                    .lightLevel(0));
            this.tint = rgb;
        }

        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public int getTintColor() {
                    return 0xFF000000 | tint;
                }

                @Override
                public ResourceLocation getStillTexture() {
                    return STILL;
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return FLOW;
                }
            });
        }
    }

    public static class KeroseneFluidType extends FluidType {

        private static final ResourceLocation STILL = ResourceLocation.withDefaultNamespace("block/water_still");
        private static final ResourceLocation FLOW = ResourceLocation.withDefaultNamespace("block/water_flow");
        private static final int TINT = 0xC8A64B; // amber

        public KeroseneFluidType() {
            super(FluidType.Properties.create()
                    .density(800)
                    .viscosity(1200)
                    .temperature(300)
                    .canSwim(true)
                    .canDrown(true));
        }

        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public int getTintColor() {
                    return 0xFF000000 | TINT;
                }

                @Override
                public ResourceLocation getStillTexture() {
                    return STILL;
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return FLOW;
                }
            });
        }
    }    public static final BaseFlowingFluid.Properties PHOSGENE_PROPS =
            new BaseFlowingFluid.Properties(PHOSGENE_TYPE, PHOSGENE, FLOWING_PHOSGENE);



    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid>MUSTARD_GAS =
            FLUIDS.register("mustard_gas", () -> new BaseFlowingFluid.Source(WFFluids.MUSTARD_GAS_PROPS));
    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid>FLOWING_MUSTARD_GAS =
            FLUIDS.register("flowing_mustard_gas", () -> new BaseFlowingFluid.Flowing(WFFluids.MUSTARD_GAS_PROPS));
    public static final BaseFlowingFluid.Properties MUSTARD_GAS_PROPS =
            new BaseFlowingFluid.Properties(MUSTARD_GAS_TYPE, MUSTARD_GAS, FLOWING_MUSTARD_GAS);


    // Glyphid acid. Registered as a fluid because that is the key MistEffects looks an effect up by; there is
    // deliberately no bucket, since the only thing that produces it is a bug.
    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid> GLYPHID_ACID =
            FLUIDS.register("glyphid_acid", () -> new BaseFlowingFluid.Source(WFFluids.GLYPHID_ACID_PROPS));
    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid> FLOWING_GLYPHID_ACID =
            FLUIDS.register("flowing_glyphid_acid", () -> new BaseFlowingFluid.Flowing(WFFluids.GLYPHID_ACID_PROPS));
    public static final BaseFlowingFluid.Properties GLYPHID_ACID_PROPS =
            new BaseFlowingFluid.Properties(GLYPHID_ACID_TYPE, GLYPHID_ACID, FLOWING_GLYPHID_ACID);



    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid>KEROSENE =
            FLUIDS.register("kerosene", () -> new BaseFlowingFluid.Source(WFFluids.KEROSENE_PROPS));
    public static final DeferredHolder<net.minecraft.world.level.material.Fluid, BaseFlowingFluid>FLOWING_KEROSENE =
            FLUIDS.register("flowing_kerosene", () -> new BaseFlowingFluid.Flowing(WFFluids.KEROSENE_PROPS));
    public static final DeferredHolder<Item, Item> KEROSENE_BUCKET =
            ITEMS.register("kerosene_bucket", () -> new BucketItem(KEROSENE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
    public static final BaseFlowingFluid.Properties KEROSENE_PROPS =
            new BaseFlowingFluid.Properties(KEROSENE_TYPE, KEROSENE, FLOWING_KEROSENE).bucket(KEROSENE_BUCKET);



}
