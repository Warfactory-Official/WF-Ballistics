package com.wf.wflib.compat.gt.gametest;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IControllable;
import com.gregtechceu.gtceu.api.capability.IElectricItem;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.common.machine.electric.BatteryBufferMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.MaintenanceHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.trait.BatterySlotTrait;
import com.gregtechceu.gtceu.common.machine.trait.ConverterTrait;
import com.wf.wflib.WFLib;
import com.wf.wflib.compat.GregtechCompat;
import com.wf.wflib.warhead.EMPWarhead;
import com.wf.wflib.warhead.WarheadCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registered only with gtceu loaded: no {@code @GameTestHolder}, see {@link #register}. */
@PrefixGameTestTemplate(false)
public class GregtechEMPGameTest {

    private static final int LOCK_TICKS = 40;

    public static void register(RegisterGameTestsEvent event) {
        event.register(GregtechEMPGameTest.class);
    }

    private static MetaMachine place(GameTestHelper helper, BlockPos rel, String gtId) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("gtceu", gtId));
        if (block == Blocks.AIR) throw new GameTestAssertException("no block gtceu:" + gtId);
        helper.setBlock(rel, block.defaultBlockState());
        MetaMachine machine = MetaMachine.getMachine(helper.getLevel(), helper.absolutePos(rel));
        if (machine == null) throw new GameTestAssertException("no GT machine at " + rel);
        return machine;
    }

    private static ItemStack chargedBattery() {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("gtceu", "lv_sodium_battery"));
        if (item == Items.AIR) throw new GameTestAssertException("no item gtceu:lv_sodium_battery");
        ItemStack stack = new ItemStack(item);
        IElectricItem electric = GTCapabilityHelper.getElectricItem(stack);
        if (electric == null) throw new GameTestAssertException("battery has no electric item");
        electric.charge(Long.MAX_VALUE, Integer.MAX_VALUE, true, false);
        if (electric.getCharge() <= 0) throw new GameTestAssertException("battery not charged");
        return stack;
    }

    private static long charge(ItemStack stack) {
        return GTCapabilityHelper.getElectricItem(stack).getCharge();
    }

    private static Direction inputSide(GameTestHelper helper, IEnergyContainer energy) {
        for (Direction d : Direction.values()) {
            if (energy.inputsEnergy(d)) return d;
        }
        throw new GameTestAssertException("energy container has no input side");
    }

    private static boolean hasKey(Tag tag, String key) {
        if (tag instanceof CompoundTag c) {
            if (c.contains(key)) return true;
            for (String k : c.getAllKeys()) {
                if (hasKey(c.get(k), key)) return true;
            }
        } else if (tag instanceof net.minecraft.nbt.ListTag l) {
            for (Tag t : l) {
                if (hasKey(t, key)) return true;
            }
        }
        return false;
    }

    @GameTest(templateNamespace = WFLib.MODID, template = "empty", timeoutTicks = 200)
    public static void empDrainsPausesAndLocksCharging(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 1, 1);
        MetaMachine machine = place(helper, rel, "lv_electric_furnace");
        BlockPos pos = machine.getBlockPos();
        IEnergyContainer energy = GTCapabilityHelper.getEnergyContainer(level, pos, null);
        IControllable controllable = GTCapabilityHelper.getControllable(level, pos, null);
        if (energy == null || controllable == null) throw new GameTestAssertException("missing GT capabilities");
        Direction side = inputSide(helper, energy);
        long lv = GTValues.V[GTValues.LV];

        helper.assertTrue(energy.acceptEnergyFromNetwork(side, lv, 1) == 1, "control: accepts 1 A before EMP");
        energy.changeEnergy(energy.getEnergyCapacity());
        helper.assertTrue(energy.getEnergyStored() > 0, "charged before EMP");
        helper.assertTrue(controllable.isWorkingEnabled(), "working before EMP");

        helper.assertTrue(GregtechCompat.applyEMP(level, pos, LOCK_TICKS, true, true, false), "EMP reports energy");
        helper.assertTrue(energy.getEnergyStored() == 0, "drained: " + energy.getEnergyStored());
        helper.assertFalse(controllable.isWorkingEnabled(), "paused");

        helper.runAfterDelay(1, () -> {
            helper.assertTrue(energy.acceptEnergyFromNetwork(side, lv, 1) == 0, "charge locked");
            CompoundTag saved = machine.saveWithoutMetadata(level.registryAccess());
            helper.assertTrue(hasKey(saved, "WFLibEmpLock"), "lock saved: " + saved);
        });
        helper.runAfterDelay(LOCK_TICKS + 10, () -> {
            helper.assertTrue(energy.acceptEnergyFromNetwork(side, lv, 1) == 1, "lock expired");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = WFLib.MODID, template = "empty", timeoutTicks = 100)
    public static void empBreaksMaintenance(GameTestHelper helper) {
        MetaMachine machine = place(helper, new BlockPos(1, 1, 1), "maintenance_hatch");
        if (!(machine instanceof MaintenanceHatchPartMachine hatch)) throw new GameTestAssertException("not a hatch");
        hatch.fixAllMaintenanceProblems();
        byte fixed = hatch.getMaintenanceProblems();
        helper.assertTrue(fixed != 0, "control: fixed hatch != 0");

        GregtechCompat.applyEMP(helper.getLevel(), machine.getBlockPos(), 0, false, false, true);
        helper.assertTrue(hatch.getMaintenanceProblems() == 0, "all problems: " + hatch.getMaintenanceProblems());
        helper.succeed();
    }

    @GameTest(templateNamespace = WFLib.MODID, template = "empty", timeoutTicks = 200)
    public static void empLocksBatterySlotLosslessly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MetaMachine machine = place(helper, new BlockPos(1, 1, 1), "lv_electric_furnace");
        BlockPos pos = machine.getBlockPos();
        IEnergyContainer energy = GTCapabilityHelper.getEnergyContainer(level, pos, null);
        BatterySlotTrait slot = machine.getTrait(BatterySlotTrait.class);
        if (energy == null || slot == null) throw new GameTestAssertException("missing energy container / battery slot");

        GregtechCompat.applyEMP(level, pos, LOCK_TICKS, true, true, false);
        ItemStack battery = chargedBattery();
        long full = charge(battery);
        slot.getStorage().setStackInSlot(0, battery);

        helper.runAfterDelay(LOCK_TICKS / 2, () -> {
            helper.assertTrue(energy.getEnergyStored() == 0, "locked: slot charged machine to " + energy.getEnergyStored());
            helper.assertTrue(charge(slot.getStorage().getStackInSlot(0)) == full, "locked: battery lost charge");
        });
        helper.runAfterDelay(LOCK_TICKS + 10, () -> {
            helper.assertTrue(energy.getEnergyStored() > 0, "lock expired: slot resumed");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = WFLib.MODID, template = "empty", timeoutTicks = 200)
    public static void empLocksFeInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MetaMachine machine = place(helper, new BlockPos(1, 1, 1), "lv_1a_energy_converter");
        ConverterTrait converter = machine.getTrait(ConverterTrait.class);
        ConverterTrait.FEContainer fe = machine.getTrait(ConverterTrait.FEContainer.class);
        if (converter == null || fe == null) throw new GameTestAssertException("missing converter traits");
        converter.setFeToEu(true);
        helper.assertTrue(fe.receiveEnergy(1000, true) > 0, "control: FE accepted before EMP");

        GregtechCompat.applyEMP(level, machine.getBlockPos(), LOCK_TICKS, true, true, false);
        helper.assertTrue(fe.receiveEnergy(1000, false) == 0, "locked: FE refused");
        helper.assertTrue(converter.getEnergyStored() == 0, "locked: no EU gained");

        helper.runAfterDelay(LOCK_TICKS + 10, () -> {
            helper.assertTrue(fe.receiveEnergy(1000, true) > 0, "lock expired: FE accepted");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = WFLib.MODID, template = "empty", timeoutTicks = 100)
    public static void empDrainsBatteryBuffer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MetaMachine machine = place(helper, new BlockPos(1, 1, 1), "lv_battery_buffer_4x");
        if (!(machine instanceof BatteryBufferMachine buffer)) throw new GameTestAssertException("not a battery buffer");
        BlockPos pos = machine.getBlockPos();
        buffer.getBatteryInventory().setStackInSlot(0, chargedBattery());
        IEnergyContainer energy = GTCapabilityHelper.getEnergyContainer(level, pos, null);
        if (energy == null) throw new GameTestAssertException("missing energy container");
        Direction side = inputSide(helper, energy);
        helper.assertTrue(energy.getEnergyStored() > 0, "control: buffer reports battery charge");

        GregtechCompat.applyEMP(level, pos, LOCK_TICKS, true, false, false);
        helper.assertTrue(charge(buffer.getBatteryInventory().getStackInSlot(0)) == 0, "battery drained");
        helper.assertTrue(energy.acceptEnergyFromNetwork(side, GTValues.V[GTValues.LV], 1) == 0, "buffer charge locked");
        helper.succeed();
    }

    /** Own batch: warhead radius 48 reaches neighbouring arenas. */
    @GameTest(templateNamespace = WFLib.MODID, template = "empty", timeoutTicks = 100, batch = "gt_emp_warhead")
    public static void empWarheadReachesGtMachine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MetaMachine machine = place(helper, new BlockPos(1, 1, 1), "lv_electric_furnace");
        BlockPos pos = machine.getBlockPos();
        IEnergyContainer energy = GTCapabilityHelper.getEnergyContainer(level, pos, null);
        if (energy == null) throw new GameTestAssertException("missing energy container");
        Direction side = inputSide(helper, energy);
        energy.changeEnergy(energy.getEnergyCapacity());

        EMPWarhead.detonate(new WarheadCarrier() {
            @Override
            public Level level() {
                return level;
            }

            @Override
            public int getFragmentCount() {
                return 0;
            }

            @Override
            public Vec3 angle() {
                return new Vec3(0, -1, 0);
            }
        }, Vec3.atCenterOf(pos).add(0, 3, 0));

        helper.runAfterDelay(1, () -> {
            helper.assertTrue(energy.getEnergyStored() == 0, "drained: " + energy.getEnergyStored());
            helper.assertTrue(energy.acceptEnergyFromNetwork(side, GTValues.V[GTValues.LV], 1) == 0, "charge locked");
            helper.succeed();
        });
    }
}
