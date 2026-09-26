package com.wf.wflib.armor.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.armor.ArmorConfig;
import com.wf.wflib.armor.ArmorExposure;
import com.wf.wflib.armor.ArmorOutcome;
import com.wf.wflib.armor.ArmorPreset;
import com.wf.wflib.armor.ArmorPresets;
import com.wf.wflib.armor.ArmorProfiles;
import com.wf.wflib.armor.ArmorResult;
import com.wf.wflib.armor.ArmorSpec;
import com.wf.wflib.armor.ArmorStacks;
import com.wf.wflib.armor.ArmorSystem;
import com.wf.wflib.armor.ArmorUnits;
import com.wf.wflib.armor.ProtectionType;
import com.wf.wflib.damage.WFDamage;
import com.wf.wflib.damage.WFDamageTypes;
import com.wf.wflib.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Does the armour system do in the world what the arithmetic says it does?
 *
 * <p>The pure resolver is pinned by {@code ArmorResolverTest} under plain JUnit and needs no game at
 * all. What needs a game is everything around it: that vanilla's armour attributes really are gone
 * so nothing is counted twice, that wear really lands on the plate rather than the carrier it was
 * fitted to, that a helmet really does not protect a shin, and that a hit resolves before anything
 * else reads the damage amount.
 *
 * <p>Victims are villagers with no free will and no gravity: they do not burn in daylight, do not
 * wander out of a three-block arena, and take fall damage from nothing.
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class ArmorGameTest {

    private static final String TEMPLATE = "empty";

    private static final int CARRIER_MAX = 600 * ArmorUnits.POINTS_PER_DAMAGE;
    private static final int STEEL_MAX = 3000 * ArmorUnits.POINTS_PER_DAMAGE;

    // --- wiring ---------------------------------------------------------------------------------

    /** Armour nobody can wear is armour nobody can test. */
    @GameTest(template = TEMPLATE)
    public static void everyPresetHasAnItemAndAProfile(GameTestHelper helper) {
        for (ArmorPreset preset : ArmorPresets.all()) {
            Item item = ModItems.armorItem(preset.id());
            if (item == null) {
                helper.fail("no item for armour preset " + preset.id());
                return;
            }
            ItemStack stack = new ItemStack(item);
            ArmorSpec spec = ArmorProfiles.specFor(stack);
            if (spec == null) {
                helper.fail("no profile registered for " + preset.id());
                return;
            }
            int expected = preset.durability() * ArmorUnits.POINTS_PER_DAMAGE;
            if (spec.maxCondition() != expected) {
                helper.fail(preset.id() + " condition scale is " + spec.maxCondition()
                        + ", but its durability says " + expected);
                return;
            }
            if (ArmorStacks.condition(stack, spec) != expected) {
                helper.fail(preset.id() + " does not start pristine");
                return;
            }
            // The materials declare iron-grade defense so the master switch has something to fall back
            // to. While the system is on, none of it may survive as far as a damage calculation.
            for (ItemAttributeModifiers.Entry entry : stack.getAttributeModifiers().modifiers()) {
                if (entry.attribute() == Attributes.ARMOR || entry.attribute() == Attributes.ARMOR_TOUGHNESS) {
                    helper.fail(preset.id() + " still carries a vanilla " + entry.attribute()
                            + " modifier, so it would be counted twice");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * Derive-then-strip. Foreign armour gets a profile from its own vanilla numbers, and those numbers
     * are then taken off it: {@code LivingIncomingDamageEvent} fires before vanilla's armour absorb, so
     * a piece left holding both would be counted twice.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void vanillaArmourIsDerivedAndThenStripped(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        victim.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));

        helper.startSequence().thenExecuteAfter(3, () -> {
            double armour = victim.getAttributeValue(Attributes.ARMOR);
            if (armour != 0.0D) {
                helper.fail("iron chestplate still carries " + armour + " armour points");
                return;
            }
            ItemStack chest = victim.getItemBySlot(EquipmentSlot.CHEST);
            ArmorSpec spec = ArmorProfiles.specFor(chest);
            if (spec == null) {
                helper.fail("iron chestplate got no derived profile");
                return;
            }
            // 6 defense over vanilla's own scale of 25, and no toughness, so no threshold.
            double dr = spec.row(ProtectionType.KINETIC).dr();
            if (Math.abs(dr - 0.24D) > 1.0E-6D) {
                helper.fail("derived DR is " + dr + ", expected 6/25");
                return;
            }
            if (spec.row(ProtectionType.CHEMICAL).dr() != 0.0D) {
                helper.fail("a chestplate is not a respirator, but it derived a chemical rating");
                return;
            }
            ArmorResult result = ArmorSystem.resolveSlot(victim, EquipmentSlot.CHEST,
                    source(helper, WFDamageTypes.PHYSICAL), 10.0F, true);
            if (Math.abs(result.through() - 7.6D) > 1.0E-4D) {
                helper.fail("expected 7.6 through an iron chestplate, got " + result.through());
                return;
            }
            // 2.4 absorbed, no hardness, so 240 points: two whole durability points and 40 over.
            if (chest.getDamageValue() != 2) {
                helper.fail("expected 2 durability spent, got " + chest.getDamageValue());
                return;
            }
            helper.succeed();
        }).thenSucceed();
    }

    // --- where the hit landed --------------------------------------------------------------------

    /**
     * The bug this replaced summed every worn piece regardless of where the hit landed, so a helmet
     * protected a shin. Per slot, it protects a head and nothing else.
     */
    @GameTest(template = TEMPLATE)
    public static void aHelmetDoesNotProtectAShin(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        victim.setItemSlot(EquipmentSlot.HEAD, stack("combat_helmet"));
        DamageSource rifle = source(helper, WFDamageTypes.PHYSICAL);

        ArmorResult head = ArmorSystem.resolveSlot(victim, EquipmentSlot.HEAD, rifle, 10.0F, false);
        ArmorResult foot = ArmorSystem.resolveSlot(victim, EquipmentSlot.FEET, rifle, 10.0F, false);
        // DT 6 then DR 25% of the remaining 4.
        if (Math.abs(head.through() - 3.0D) > 1.0E-4D) {
            helper.fail("helmet let " + head.through() + " through, expected 3");
            return;
        }
        if (foot.through() != 10.0D || foot.outcome() != ArmorOutcome.THROUGH) {
            helper.fail("a helmet protected a bare foot: " + foot.through() + " through");
            return;
        }
        helper.succeed();
    }

    /**
     * The whole-entity door has nobody to tell it where the hit landed, so every piece is worth its
     * share of a body. A lone helmet is an eighth of a set, not the whole of one.
     */
    @GameTest(template = TEMPLATE)
    public static void theWholeEntityDoorWeightsByCoverage(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        victim.setItemSlot(EquipmentSlot.HEAD, stack("combat_helmet"));
        DamageSource rifle = source(helper, WFDamageTypes.PHYSICAL);

        ArmorResult whole = ArmorSystem.resolveWhole(victim, rifle, 10.0F, false);
        ArmorResult perSlot = ArmorSystem.resolveSlot(victim, EquipmentSlot.HEAD, rifle, 10.0F, false);
        if (!(whole.through() > perSlot.through())) {
            helper.fail("a helmet weighted by coverage should stop less than a helmet that was hit: "
                    + whole.through() + " vs " + perSlot.through());
            return;
        }
        if (!(whole.through() < 10.0D)) {
            helper.fail("a helmet should still be worth something: " + whole.through());
            return;
        }
        // 15% of a body: DT 0.9, then 3.75% off the rest.
        if (Math.abs(whole.through() - 8.75875D) > 1.0E-4D) {
            helper.fail("coverage-weighted helmet let " + whole.through() + " through");
            return;
        }
        helper.succeed();
    }

    // --- inserts ---------------------------------------------------------------------------------

    /** The plate that ate the round is the thing that wears out, not the carrier it was fitted to. */
    @GameTest(template = TEMPLATE)
    public static void theInsertWearsRatherThanTheCarrier(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        ItemStack carrier = stack("plate_carrier");
        if (!ArmorStacks.canInsert(carrier, stack("steel_plate"))) {
            helper.fail("a plate carrier would not take a steel plate");
            return;
        }
        ArmorStacks.setInserts(carrier, List.of(stack("steel_plate")));
        victim.setItemSlot(EquipmentSlot.CHEST, carrier);

        ArmorResult result = ArmorSystem.resolveSlot(victim, EquipmentSlot.CHEST,
                source(helper, WFDamageTypes.PHYSICAL), 30.0F, true);
        // DT 22 then 60% of the remaining 8.
        if (Math.abs(result.through() - 3.2D) > 1.0E-4D) {
            helper.fail("expected 3.2 through carrier plus plate, got " + result.through());
            return;
        }
        int carrierLost = CARRIER_MAX - condition(carrier);
        List<ItemStack> inserts = ArmorStacks.inserts(carrier);
        if (inserts.size() != 1) {
            helper.fail("the plate fell out: " + inserts.size() + " inserts");
            return;
        }
        int plateLost = STEEL_MAX - condition(inserts.get(0));
        if (plateLost != 975 || carrierLost != 185) {
            helper.fail("attribution is wrong: plate " + plateLost + ", carrier " + carrierLost
                    + " (expected 975 / 185)");
            return;
        }
        helper.succeed();
    }

    /**
     * Hardness, and the reason it exists. A fully stopped hit hands all of its energy to the armour, so
     * volume fire would otherwise be the cheapest way through expensive plate. Below hardness a hit
     * marks the plate instead of biting it, so grapeshot cannot grind down steel it cannot defeat.
     */
    @GameTest(template = TEMPLATE)
    public static void hardnessStopsVolumeFireGrindingDownPlate(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        ItemStack carrier = stack("plate_carrier");
        ArmorStacks.setInserts(carrier, List.of(stack("steel_plate")));
        victim.setItemSlot(EquipmentSlot.CHEST, carrier);
        DamageSource kinetic = source(helper, WFDamageTypes.PHYSICAL);

        // Eight pellets of four: thirty-two points of incoming damage, none of it through.
        for (int i = 0; i < 8; i++) {
            ArmorResult pellet = ArmorSystem.resolveSlot(victim, EquipmentSlot.CHEST, kinetic, 4.0F, true);
            if (pellet.outcome() != ArmorOutcome.STOPPED) {
                helper.fail("buckshot got through steel plate: " + pellet.through());
                return;
            }
        }
        int plateFromBuckshot = STEEL_MAX - condition(ArmorStacks.inserts(carrier).get(0));

        // One round of thirty, which is less incoming damage and more than five times the wear.
        ItemStack fresh = stack("plate_carrier");
        ArmorStacks.setInserts(fresh, List.of(stack("steel_plate")));
        victim.setItemSlot(EquipmentSlot.CHEST, fresh);
        ArmorSystem.resolveSlot(victim, EquipmentSlot.CHEST, kinetic, 30.0F, true);
        int plateFromRifle = STEEL_MAX - condition(ArmorStacks.inserts(fresh).get(0));

        if (plateFromBuckshot != 144) {
            helper.fail("a buckshot blast cost the plate " + plateFromBuckshot + ", expected 144");
            return;
        }
        if (plateFromRifle != 975) {
            helper.fail("a rifle round cost the plate " + plateFromRifle + ", expected 975");
            return;
        }
        int blastsToBreak = STEEL_MAX / plateFromBuckshot;
        int roundsToBreak = STEEL_MAX / plateFromRifle;
        if (blastsToBreak < roundsToBreak * 5) {
            helper.fail("volume fire is still the cheap answer: " + blastsToBreak + " blasts against "
                    + roundsToBreak + " rounds");
            return;
        }
        helper.succeed();
    }

    /**
     * A plate that shatters stops working and stays where it is. Ceramic has no worn floor, so a dead
     * one contributes nothing at all, and the carrier is back to being a vest. It is not ejected: a
     * plate that fell into the world mid-firefight would be a silently lost item.
     */
    @GameTest(template = TEMPLATE)
    public static void aDeadCeramicPlateStopsWorkingAndStaysIn(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        ItemStack carrier = stack("plate_carrier");
        ItemStack ceramic = stack("ceramic_plate");
        ArmorStacks.setCondition(ceramic, ArmorProfiles.specFor(ceramic), 0);
        ArmorStacks.setInserts(carrier, List.of(ceramic));
        victim.setItemSlot(EquipmentSlot.CHEST, carrier);

        ArmorResult result = ArmorSystem.resolveSlot(victim, EquipmentSlot.CHEST,
                source(helper, WFDamageTypes.PHYSICAL), 30.0F, false);
        // The carrier alone: DT 2, then 10% off the remaining 28.
        if (Math.abs(result.through() - 25.2D) > 1.0E-4D) {
            helper.fail("a dead ceramic plate was still stopping things: " + result.through());
            return;
        }
        if (ArmorStacks.inserts(carrier).size() != 1) {
            helper.fail("the dead plate was thrown away instead of left to be replaced");
            return;
        }
        helper.succeed();
    }

    // --- storage ----------------------------------------------------------------------------------

    /**
     * Condition is inflated a hundred to one and still lives in vanilla durability, so the bar moves
     * and the anvil works. The round trip through that has to be exact.
     */
    @GameTest(template = TEMPLATE)
    public static void conditionSurvivesTheRoundTripThroughDurability(GameTestHelper helper) {
        ItemStack plate = stack("steel_plate");
        ArmorSpec spec = ArmorProfiles.specFor(plate);
        for (int target : new int[]{STEEL_MAX, STEEL_MAX - 1, STEEL_MAX - 99, STEEL_MAX - 100,
                STEEL_MAX / 3, 1, 0}) {
            ArmorStacks.setCondition(plate, spec, target);
            int read = ArmorStacks.condition(plate, spec);
            if (read != target) {
                helper.fail("condition " + target + " read back as " + read);
                return;
            }
        }
        // Wearing in small steps must land in the same place as wearing in one.
        ItemStack a = stack("steel_plate");
        ItemStack b = stack("steel_plate");
        for (int i = 0; i < 37; i++) {
            ArmorStacks.wear(a, 13);
        }
        ArmorStacks.wear(b, 37 * 13);
        if (condition(a) != condition(b)) {
            helper.fail("37 small bites left " + condition(a) + ", one big one left " + condition(b));
            return;
        }
        helper.succeed();
    }

    // --- the damage pipeline ----------------------------------------------------------------------

    /**
     * The ordering fix, pinned. Both mods used to sit at {@code NORMAL} on this event, so which one saw
     * the raw damage was decided by mod load order, and WFMedical reads that amount as wound energy.
     * Armour resolves at {@code HIGH} now, so a listener at {@code NORMAL} sees what got through.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void armourResolvesBeforeAnyoneReadsTheAmount(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        ItemStack carrier = stack("plate_carrier");
        ArmorStacks.setInserts(carrier, List.of(stack("steel_plate")));
        victim.setItemSlot(EquipmentSlot.CHEST, carrier);

        List<Float> seen = new ArrayList<>();
        Consumer<LivingIncomingDamageEvent> listener = event -> {
            if (event.getEntity() == victim) {
                seen.add(event.getAmount());
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LivingIncomingDamageEvent.class, listener);
        try {
            WFDamage.hurtIgnoringIFrames(victim, source(helper, WFDamageTypes.PHYSICAL), 30.0F);
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }

        if (seen.isEmpty()) {
            helper.fail("nothing at NORMAL priority saw the hit at all");
            return;
        }
        float amount = seen.get(0);
        // Coverage 0.40 on the chest: DT 8.8, then 24% of the remaining 21.2.
        if (amount >= 30.0F) {
            helper.fail("a listener at NORMAL still saw the raw " + amount + ", so armour ran too late");
            return;
        }
        if (Math.abs(victim.getMaxHealth() - victim.getHealth() - amount) > 0.05F) {
            helper.fail("health fell by " + (victim.getMaxHealth() - victim.getHealth())
                    + " but the event said " + amount);
            return;
        }
        helper.succeed();
    }

    /**
     * Vanilla charges a quarter of the incoming damage to armour durability per hit; this system
     * charges what the armour actually absorbed, per layer. Leaving both on would wear a plate out at
     * roughly twice the rate and the halves would disagree about which layer did the work.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void vanillaDoesNotAlsoChargeDurability(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        victim.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));

        helper.startSequence().thenExecuteAfter(3, () -> {
            ItemStack chest = victim.getItemBySlot(EquipmentSlot.CHEST);
            ArmorSpec spec = ArmorProfiles.specFor(chest);
            int before = ArmorStacks.condition(chest, spec);

            ArmorResult dryRun = ArmorSystem.resolveWhole(victim,
                    source(helper, WFDamageTypes.PHYSICAL), 12.0F, false);
            int predicted = 0;
            for (ArmorResult.LayerWear wear : dryRun.wear()) {
                predicted += wear.points();
            }
            if (predicted <= 0) {
                helper.fail("the dry run predicted no wear at all, so this proves nothing");
                return;
            }

            WFDamage.hurtIgnoringIFrames(victim, source(helper, WFDamageTypes.PHYSICAL), 12.0F);
            int spent = before - ArmorStacks.condition(chest, spec);
            if (spent != predicted) {
                helper.fail("armour lost " + spent + " points but the resolver only charged "
                        + predicted + "; something else is charging durability too");
                return;
            }
            helper.succeed();
        }).thenSucceed();
    }

    /**
     * Sustained fire is the second way through armour, and the reason an ineffective gun is tactical
     * rather than broken: condition scales the threshold, so emptying a magazine into steel is doing
     * durability and then it is doing damage.
     */
    @GameTest(template = TEMPLATE)
    public static void aSpentPlateStopsStoppingThings(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        ItemStack carrier = stack("plate_carrier");
        ItemStack plate = stack("steel_plate");
        ArmorStacks.setInserts(carrier, List.of(plate));
        victim.setItemSlot(EquipmentSlot.CHEST, carrier);
        DamageSource kinetic = source(helper, WFDamageTypes.PHYSICAL);

        ArmorResult fresh = ArmorSystem.resolveSlot(victim, EquipmentSlot.CHEST, kinetic, 15.0F, false);
        if (fresh.outcome() != ArmorOutcome.STOPPED) {
            helper.fail("a fresh plate should stop 15: " + fresh.through());
            return;
        }

        ItemStack spentPlate = stack("steel_plate");
        ArmorStacks.setCondition(spentPlate, ArmorProfiles.specFor(spentPlate), 0);
        ItemStack spentCarrier = stack("plate_carrier");
        ArmorStacks.setCondition(spentCarrier, ArmorProfiles.specFor(spentCarrier), 0);
        ArmorStacks.setInserts(spentCarrier, List.of(spentPlate));
        victim.setItemSlot(EquipmentSlot.CHEST, spentCarrier);

        ArmorResult spent = ArmorSystem.resolveSlot(victim, EquipmentSlot.CHEST, kinetic, 15.0F, false);
        if (spent.through() <= 0.0D) {
            helper.fail("a spent plate still stopped the same round outright");
            return;
        }
        // Worn floor 0.6 on the steel and 0.1 on the carrier: DT 12.2 and DR 0.31, not 22 and 0.60.
        if (Math.abs(spent.through() - 1.932D) > 1.0E-3D) {
            helper.fail("a spent plate let " + spent.through() + " through");
            return;
        }
        helper.succeed();
    }

    // --- exposure ---------------------------------------------------------------------------------

    /**
     * Walking into the gas is a splash and costs a share of maximum condition at once; staying in it is
     * a timed decay; and a hazard at or below the suit's tier for that type costs nothing at all, for as
     * long as you like. That last clause is the whole reason to own a sealed suit.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aRatedSuitShrugsOffGasThatRuinsAVest(GameTestHelper helper) {
        LivingEntity inVest = victim(helper, 1);
        LivingEntity inSuit = victim(helper, 2);
        ItemStack vest = stack("plate_carrier");
        ItemStack suit = stack("hazmat_suit");
        inVest.setItemSlot(EquipmentSlot.CHEST, vest);
        inSuit.setItemSlot(EquipmentSlot.CHEST, suit);
        int suitMax = ArmorProfiles.specFor(suit).maxCondition();

        // A cloud dealing 150 condition points an interval, against a hazmat rating of 200. Under the
        // rating, so the suit accrues nothing at all however long it stands there; the vest, rated for
        // nothing, pays all 150 of it every interval.
        helper.startSequence()
                .thenExecuteFor(120, () -> {
                    ArmorExposure.contact(inVest, ProtectionType.CHEMICAL, 150.0D);
                    ArmorExposure.contact(inSuit, ProtectionType.CHEMICAL, 150.0D);
                })
                .thenExecute(() -> {
                    ArmorExposure.flush(inVest);
                    ArmorExposure.flush(inSuit);
                    int vestLost = CARRIER_MAX - condition(vest);
                    int suitLost = suitMax - condition(suit);

                    // The splash: 4% of maximum, less the suit's own 85% chemical resistance.
                    int expectedSuitSplash = (int) Math.round(suitMax * ArmorConfig.acuteFraction * 0.15D);
                    if (suitLost != expectedSuitSplash) {
                        helper.fail("the suit lost " + suitLost + " where only the splash ("
                                + expectedSuitSplash + ") should have reached it");
                        return;
                    }
                    int expectedVestSplash = (int) Math.round(CARRIER_MAX * ArmorConfig.acuteFraction);
                    if (vestLost <= expectedVestSplash) {
                        helper.fail("the vest took the splash but no timed decay: " + vestLost);
                        return;
                    }
                    if (vestLost < suitLost * 5) {
                        helper.fail("a rated suit should be worth far more than this: vest " + vestLost
                                + ", suit " + suitLost);
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }

    /** Being on fire is contact too, and nothing had to be told about it: the built-in source polls it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void burningEatsArmourWithNobodyPushingIt(GameTestHelper helper) {
        LivingEntity victim = victim(helper, 1);
        ItemStack vest = stack("plate_carrier");
        victim.setItemSlot(EquipmentSlot.CHEST, vest);

        helper.startSequence()
                .thenExecuteFor(100, () -> victim.setRemainingFireTicks(40))
                .thenExecute(() -> {
                    victim.setRemainingFireTicks(0);
                    ArmorExposure.flush(victim);
                    int lost = CARRIER_MAX - condition(vest);
                    if (lost <= 0) {
                        helper.fail("standing on fire cost the vest nothing");
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * A villager with no free will and no gravity: it does not burn in daylight the way a zombie does,
     * does not wander out of a three-block arena, and cannot fall out of one either.
     */
    private static LivingEntity victim(GameTestHelper helper, int x) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(x, 2, 1));
        villager.setNoGravity(true);
        villager.setHealth(villager.getMaxHealth());
        return villager;
    }

    private static ItemStack stack(String preset) {
        Item item = ModItems.armorItem(ArmorPreset.rl(preset));
        if (item == null) {
            throw new IllegalStateException("no armour item for " + preset);
        }
        return new ItemStack(item);
    }

    private static int condition(ItemStack stack) {
        return ArmorStacks.condition(stack, ArmorProfiles.specFor(stack));
    }

    private static DamageSource source(GameTestHelper helper, ResourceKey<DamageType> key) {
        return new DamageSource(helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key));
    }
}
