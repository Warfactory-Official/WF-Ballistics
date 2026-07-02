package com.wf.wfballistics.fluid;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Consumer;

/**
 * The mod's Forge fluids. These are deliberately minimal "gas" fluids: they have a {@link FluidType} and the
 * source/flowing pair Forge requires, but no fluid block, bucket or world placement — they exist purely to
 * be loaded into a {@link com.wf.wfballistics.entity.MistEntity}. Negative density marks them as gases, and
 * each carries the tint its mist is drawn with.
 *
 * <p>To add another gas: register a {@code FluidType} + source + flowing here, then bind a
 * {@link com.wf.wfballistics.entity.mist.MistEffect} to the source fluid in
 * {@link com.wf.wfballistics.entity.mist.MistEffects#bootstrap()}.
 */
public final class WFFluids {

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, WFBallistics.MODID);
    public static final DeferredRegister<net.minecraft.world.level.material.Fluid> FLUIDS =
            DeferredRegister.create(ForgeRegistries.FLUIDS, WFBallistics.MODID);

    //Phosgene
    public static final RegistryObject<FluidType> PHOSGENE_TYPE =
            FLUID_TYPES.register("phosgene", () -> new GasFluidType(0xCFE0C0));
    // Mustard gas
    public static final RegistryObject<FluidType> MUSTARD_GAS_TYPE =
            FLUID_TYPES.register("mustard_gas", () -> new GasFluidType(0xB8A038));    public static final RegistryObject<ForgeFlowingFluid> PHOSGENE =
            FLUIDS.register("phosgene", () -> new ForgeFlowingFluid.Source(WFFluids.PHOSGENE_PROPS));
    private WFFluids() {
    }    public static final RegistryObject<ForgeFlowingFluid> FLOWING_PHOSGENE =
            FLUIDS.register("flowing_phosgene", () -> new ForgeFlowingFluid.Flowing(WFFluids.PHOSGENE_PROPS));

    public static void register(IEventBus modBus) {
        FLUID_TYPES.register(modBus);
        FLUIDS.register(modBus);
    }    public static final ForgeFlowingFluid.Properties PHOSGENE_PROPS =
            new ForgeFlowingFluid.Properties(PHOSGENE_TYPE, PHOSGENE, FLOWING_PHOSGENE);

    public static class GasFluidType extends FluidType {

        private static final ResourceLocation STILL = new ResourceLocation("block/water_still");
        private static final ResourceLocation FLOW = new ResourceLocation("block/water_flow");

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
    public static final RegistryObject<ForgeFlowingFluid> MUSTARD_GAS =
            FLUIDS.register("mustard_gas", () -> new ForgeFlowingFluid.Source(WFFluids.MUSTARD_GAS_PROPS));
    public static final RegistryObject<ForgeFlowingFluid> FLOWING_MUSTARD_GAS =
            FLUIDS.register("flowing_mustard_gas", () -> new ForgeFlowingFluid.Flowing(WFFluids.MUSTARD_GAS_PROPS));
    public static final ForgeFlowingFluid.Properties MUSTARD_GAS_PROPS =
            new ForgeFlowingFluid.Properties(MUSTARD_GAS_TYPE, MUSTARD_GAS, FLOWING_MUSTARD_GAS);






}
