package com.wf.wflib.round.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.api.StrikeContext;
import com.wf.wflib.armor.ArmorPreset;
import com.wf.wflib.armor.ArmorProfiles;
import com.wf.wflib.armor.ArmorStacks;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.DeferredImpact;
import com.wf.wflib.round.DeferredImpacts;
import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.round.effect.ImpactContext;
import com.wf.wflib.round.effect.ImpactEffects;
import com.wf.wflib.round.effect.ImpactTrigger;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Impact effects: every trigger once in order, each built-in, energy-scaled armour pen, thermal pen + wear. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class ImpactEffectGameTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation INERT = WarheadRegistry.rl("inert");
    private static final ResourceLocation PROBE = ImpactEffects.rl("test_probe");
    private static final List<ImpactContext> PROBED = new CopyOnWriteArrayList<>();
    private static final List<ProjectileStrikeEvent> STRIKES = new CopyOnWriteArrayList<>();
    private static final List<LivingIncomingDamageEvent> HURTS = new CopyOnWriteArrayList<>();

    private static final ResourceLocation ALL_TRIGGERS = KineticPresetRegistry.rl("test_fx_all_triggers");
    private static final ResourceLocation SHORT_LIFE = KineticPresetRegistry.rl("test_fx_short_life");
    private static final ResourceLocation IGNITE_HIT = KineticPresetRegistry.rl("test_fx_ignite_hit");
    private static final ResourceLocation IGNITE_BLOCK = KineticPresetRegistry.rl("test_fx_ignite_block");
    private static final ResourceLocation DAMAGE_HIT = KineticPresetRegistry.rl("test_fx_damage_hit");
    private static final ResourceLocation EXPLODE_BLOCK = KineticPresetRegistry.rl("test_fx_explode_block");
    private static final ResourceLocation PLAIN = KineticPresetRegistry.rl("test_fx_plain");
    private static final ResourceLocation THERMAL = KineticPresetRegistry.rl("test_fx_thermal");
    private static final ResourceLocation PEN = KineticPresetRegistry.rl("test_fx_energy_pen");
    private static final ResourceLocation BENCH = KineticPresetRegistry.rl("test_fx_bench");
    private static final double RIFLE = 40.0;
    private static final double PEN_SPEED = 40.0;
    private static final float PEN_MM = 100.0f;

    static {
        ImpactEffects.register(PROBE, PROBED::add);
        KineticPreset.Builder all = rifle(ALL_TRIGGERS).blockPen(10.0).passesThroughEntities();
        for (ImpactTrigger t : ImpactTrigger.VALUES) {
            all.effect(PROBE, t, 1.0, new JsonObject());
        }
        KineticPresetRegistry.register(all.build());
        KineticPresetRegistry.register(slow(SHORT_LIFE).life(2).effect(PROBE, ImpactTrigger.END, 1.0, new JsonObject())
                .build());
        KineticPresetRegistry.register(slow(IGNITE_HIT).effect(ImpactEffects.IGNITE, ImpactTrigger.HIT, 1.0,
                json("{\"seconds\": 4}")).build());
        KineticPresetRegistry.register(slow(IGNITE_BLOCK).effect(ImpactEffects.IGNITE, ImpactTrigger.BLOCK, 1.0,
                new JsonObject()).build());
        KineticPresetRegistry.register(slow(DAMAGE_HIT).effect(ImpactEffects.DAMAGE, ImpactTrigger.HIT, 1.0,
                json("{\"type\": \"minecraft:in_fire\", \"amount\": 3}")).build());
        KineticPresetRegistry.register(slow(EXPLODE_BLOCK).effect(ImpactEffects.EXPLODE, ImpactTrigger.BLOCK, 1.0,
                json("{\"warhead\": \"wflib:standard\", \"size\": 3}")).build());
        KineticPresetRegistry.register(slow(PLAIN).impactDamage(30.0).armorPenetration(20.0).build());
        KineticPresetRegistry.register(slow(THERMAL).impactDamage(30.0).armorPenetration(20.0)
                .effect(ImpactEffects.THERMAL, ImpactTrigger.HIT, 1.0, json("{\"pen\": 30, \"wear\": 3}")).build());
        KineticPresetRegistry.register(KineticPreset.builder(PEN, null, INERT).speed(PEN_SPEED).drag(0.02)
                .gravity(0.0).life(400).mass(0.0095).energyDamage(1.0e-4).caliber(7.62).armorPenetration(PEN_MM)
                .passesThroughEntities().noChunkLoading().build());
        KineticPresetRegistry.register(rifle(BENCH).armorPenetration(10.0).passesThroughEntities().build());
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static KineticPreset.Builder rifle(ResourceLocation id) {
        return KineticPreset.builder(id, null, INERT).speed(RIFLE).drag(0.0).gravity(0.0).life(100).mass(0.004)
                .energyDamage(0.01).caliber(5.56).noChunkLoading();
    }

    /** 4 blocks/tick, flat 1 damage: one step reaches a pinned husk 6 below. */
    private static KineticPreset.Builder slow(ResourceLocation id) {
        return KineticPreset.builder(id, null, INERT).speed(4.0).drag(0.0).gravity(0.0).life(100).mass(0.01)
                .impactDamage(1.0).caliber(5.56).noChunkLoading();
    }

    @SubscribeEvent
    public static void onStrike(ProjectileStrikeEvent event) {
        STRIKES.add(event);
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        HURTS.add(event);
    }

    /**
     * Husk, plank, stone on one line: HIT, PIERCE_IN + PIERCE_OUT (plank), PIERCE_IN (stone), BLOCK, END, in that
     * order, each carrying its own point and velocity.
     */
    @GameTest(template = TEMPLATE)
    public static void everyTriggerFiresOnceInFlightOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinnedHusk(level, centre(helper, 7.0));
        BlockPos plank = BlockPos.containing(centre(helper, 10.0));
        BlockPos stone = plank.above(2);
        level.setBlockAndUpdate(plank, Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        PROBED.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(ALL_TRIGGERS), centre(helper, 5.0),
                new Vec3(0.0, RIFLE, 0.0), null, null);
        boolean flying = Rounds.step(level, key);
        List<ImpactContext> mine = PROBED.stream().filter(c -> Long.valueOf(key).equals(c.key())).toList();
        husk.discard();
        level.setBlockAndUpdate(plank, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
        List<ImpactTrigger> order = mine.stream().map(ImpactContext::trigger).toList();
        LOGGER.info("fx: triggers {}", order);
        helper.assertFalse(flying, "stone did not stop it");
        helper.assertTrue(order.equals(List.of(ImpactTrigger.HIT, ImpactTrigger.PIERCE_IN, ImpactTrigger.PIERCE_OUT,
                ImpactTrigger.PIERCE_IN, ImpactTrigger.BLOCK, ImpactTrigger.END)), "order " + order);
        ImpactContext hit = mine.get(0);
        helper.assertTrue(hit.entity() == husk && Math.abs(hit.energy() / (0.5 * 0.004 * 800.0 * 800.0) - 1.0) < 1.0e-6,
                "hit " + hit.entity() + " energy " + hit.energy());
        ImpactContext in = mine.get(1);
        ImpactContext out = mine.get(2);
        helper.assertTrue(in.block().getBlockPos().equals(plank) && Math.abs(in.at().y - plank.getY()) < 1.0e-9
                && Math.abs(out.at().y - (plank.getY() + 1)) < 1.0e-9 && out.velocity().equals(out.pass().velocity())
                && out.velocity().lengthSqr() < in.velocity().lengthSqr(), "plank in " + in.at() + " out " + out.at());
        ImpactContext stop = mine.get(4);
        ImpactContext end = mine.get(5);
        helper.assertTrue(stop.block().getBlockPos().equals(stone) && !stop.pass().exited()
                && stop.at().equals(stop.pass().point()), "block " + stop.at());
        helper.assertTrue(end.end() == RoundEnd.BLOCK && end.at().equals(stop.at()), "end " + end.end() + " "
                + end.at());
        helper.succeed();
    }

    /**
     * Plank over stone on disk in an unloaded chunk: nothing fires there (pierce skipped, END owed, chunk stays
     * unloaded; a round expiring in its air: no END); once it loads the deferred impact fires BLOCK then END, once
     * each, at the stop point.
     */
    @GameTest(template = TEMPLATE, batch = "wflib_virtual", timeoutTicks = 2000)
    public static void unloadedGroundOwesItsEffectsToTheChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos((o.getX() - 3600) >> 4, o.getZ() >> 4);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + VirtualFlightGameTest.BUILD_OFFSET);
        int x = chunk.getMinBlockX() + 7;
        int z = chunk.getMinBlockZ() + 9;
        Vec3 start = new Vec3(x + 0.5, 170.0, z + 0.5);
        List<ImpactContext> unloaded = new ArrayList<>();
        DeferredImpact[] owed = new DeferredImpact[1];
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(build.x, build.z) != null,
                        "build chunk not loaded"))
                .thenExecute(() -> {
                    int bz = z + VirtualFlightGameTest.BUILD_OFFSET * 16;
                    level.setBlockAndUpdate(new BlockPos(x, 150, bz), Blocks.OAK_PLANKS.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, 146, bz), Blocks.STONE.defaultBlockState());
                    VirtualFlightGameTest.plant(level, build, chunk);
                    level.setBlockAndUpdate(new BlockPos(x, 150, bz), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, 146, bz), Blocks.AIR.defaultBlockState());
                    ForcedChunks.set(level, build.x, build.z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.prefetch(level, start, start), "decoding"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "setup: loaded");
                    PROBED.clear();
                    long key = Rounds.launch(level, KineticPresetRegistry.get(ALL_TRIGGERS), start,
                            new Vec3(0.0, -RIFLE, 0.0), null, null);
                    for (int n = 0; n < 10 && Rounds.step(level, key); n++) {
                    }
                    long expiring = Rounds.launch(level, KineticPresetRegistry.get(SHORT_LIFE),
                            new Vec3(x + 0.5, 200.0, z + 0.5), new Vec3(3.0, 0.0, 0.0), null, null);
                    for (int n = 0; n < 10 && Rounds.step(level, expiring); n++) {
                    }
                    unloaded.addAll(in(chunk));
                    helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null,
                            "the round loaded the chunk");
                    List<DeferredImpact> d = DeferredImpacts.pending(level, chunk);
                    helper.assertTrue(d.size() == 1, "owed " + d);
                    owed[0] = d.get(0);
                    ForcedChunks.set(level, chunk.x, chunk.z, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(DeferredImpacts.pending(level, chunk).isEmpty(),
                        "deferred impact not applied"))
                .thenExecute(() -> {
                    List<ImpactContext> loaded = in(chunk);
                    ForcedChunks.set(level, chunk.x, chunk.z, false);
                    helper.assertTrue(unloaded.isEmpty(), "fired unloaded: "
                            + unloaded.stream().map(ImpactContext::trigger).toList());
                    List<ImpactTrigger> order = loaded.stream().map(ImpactContext::trigger).toList();
                    helper.assertTrue(order.equals(List.of(ImpactTrigger.BLOCK, ImpactTrigger.END)), "owed " + order);
                    helper.assertTrue(loaded.stream().allMatch(c -> c.at().equals(owed[0].at()))
                            && owed[0].at().y > 146.0 && owed[0].at().y < 147.0, "at " + owed[0].at());
                })
                .thenSucceed();
    }

    private static List<ImpactContext> in(ChunkPos chunk) {
        return PROBED.stream().filter(c -> (c.preset().id().equals(ALL_TRIGGERS)
                || c.preset().id().equals(SHORT_LIFE))
                && (Mth.floor(c.at().x) >> 4) == chunk.x && (Mth.floor(c.at().z) >> 4) == chunk.z).toList();
    }

    /** Clear flight, destroyed: END only. */
    @GameTest(template = TEMPLATE)
    public static void aClearFlightFiresOnlyItsEnd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PROBED.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(ALL_TRIGGERS), centre(helper, 5.0),
                new Vec3(0.0, RIFLE, 0.0), null, null);
        Rounds.step(level, key);
        Rounds.destroy(level, key);
        List<ImpactContext> mine = PROBED.stream().filter(c -> Long.valueOf(key).equals(c.key())).toList();
        helper.assertTrue(mine.size() == 1 && mine.get(0).end() == RoundEnd.DESTROYED, "fired " + mine);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void igniteSetsTheTargetAlight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinnedHusk(level, centre(helper, 8.0));
        long key = downOnto(level, helper, IGNITE_HIT, 14.0);
        Rounds.step(level, key);
        Rounds.destroy(level, key);
        int fire = husk.getRemainingFireTicks();
        husk.discard();
        helper.assertTrue(fire == 80, "fire ticks " + fire);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void igniteLightsTheOpenSideOfTheBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        long key = downOnto(level, helper, IGNITE_BLOCK, 13.0);
        boolean flying = Rounds.step(level, key);
        boolean lit = level.getBlockState(floor.above()).getBlock() instanceof BaseFireBlock;
        level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(floor, Blocks.AIR.defaultBlockState());
        helper.assertFalse(flying, "stone did not stop it");
        helper.assertTrue(lit, "no fire above the struck face");
        helper.succeed();
    }

    /** Server-start check: shipped refs pass; an unknown damage type or warhead fails, both named. */
    @GameTest(template = TEMPLATE)
    public static void unknownRegistryRefsFailTheStartCheck(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        ImpactEffects.check(List.of(KineticPresetRegistry.get(DAMAGE_HIT), KineticPresetRegistry.get(EXPLODE_BLOCK)),
                registries);
        KineticPreset bad = slow(KineticPresetRegistry.rl("test_fx_unregistered_refs"))
                .effect(ImpactEffects.DAMAGE, ImpactTrigger.HIT, 1.0,
                        json("{\"type\": \"minecraft:no_such_type\", \"amount\": 1}"))
                .effect(ImpactEffects.EXPLODE, ImpactTrigger.BLOCK, 1.0, json("{\"warhead\": \"wflib:no_such_warhead\"}"))
                .build();
        String message = null;
        try {
            ImpactEffects.check(List.of(bad), registries);
        } catch (IllegalStateException e) {
            message = e.getMessage();
        }
        helper.assertTrue(message != null && message.contains("minecraft:no_such_type")
                && message.contains("wflib:no_such_warhead"), "check: " + message);
        helper.succeed();
    }

    /** Round (physical) then the effect's in_fire 3, i-frames ignored. */
    @GameTest(template = TEMPLATE)
    public static void damageAddsItsTypedHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinnedHusk(level, centre(helper, 8.0));
        HURTS.clear();
        long key = downOnto(level, helper, DAMAGE_HIT, 14.0);
        Rounds.step(level, key);
        Rounds.destroy(level, key);
        List<LivingIncomingDamageEvent> mine = HURTS.stream().filter(e -> e.getEntity() == husk).toList();
        husk.discard();
        helper.assertTrue(mine.size() == 2 && !mine.get(0).getSource().is(DamageTypes.IN_FIRE)
                && mine.get(1).getSource().is(DamageTypes.IN_FIRE) && mine.get(1).getOriginalAmount() == 3.0f,
                "hurts " + mine.stream().map(e -> e.getSource().getMsgId() + " " + e.getOriginalAmount()).toList());
        helper.succeed();
    }

    /** Inert round, explode effect {wflib:standard, size 3} on the planks it stops on. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void explodeBlowsAHoleWhereItStops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = BlockPos.containing(centre(helper, 10.0));
        List<BlockPos> planks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(floor.offset(-1, 0, -1), floor.offset(1, 0, 1))) {
            planks.add(p.immutable());
            level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
        }
        long key = downOnto(level, helper, EXPLODE_BLOCK, 13.0);
        Rounds.step(level, key);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(floor).isAir(), "floor intact"))
                .thenExecute(() -> planks.forEach(p -> level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState())))
                .thenSucceed();
    }

    /** Muzzle strike: plain threat pen 20; thermal 20 + 30, wear x3. */
    @GameTest(template = TEMPLATE)
    public static void thermalAddsPenToTheThreat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        float[] pen = new float[2];
        float[] wear = new float[2];
        ResourceLocation[] ids = {PLAIN, THERMAL};
        for (int k = 0; k < 2; k++) {
            Husk husk = pinnedHusk(level, centre(helper, 8.0));
            STRIKES.clear();
            long key = downOnto(level, helper, ids[k], 14.0);
            Rounds.step(level, key);
            Rounds.destroy(level, key);
            ProjectileStrikeEvent s = STRIKES.stream().filter(e -> Long.valueOf(key).equals(e.key())).findFirst()
                    .orElse(null);
            helper.assertTrue(s != null, ids[k] + " missed");
            pen[k] = s.threat().penetrationMm();
            wear[k] = s.threat().wearFactor();
            husk.discard();
        }
        helper.assertTrue(pen[0] == 20.0f && wear[0] == 1.0f && pen[1] == 50.0f && wear[1] == 3.0f,
                "plain " + pen[0] + " x" + wear[0] + ", thermal " + pen[1] + " x" + wear[1]);
        helper.succeed();
    }

    /** Same 30-damage round into a plate carrier + steel plate: the thermal one costs the plate 3x. */
    @GameTest(template = TEMPLATE)
    public static void thermalWearsThePlateFaster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int[] lost = new int[2];
        ResourceLocation[] ids = {PLAIN, THERMAL};
        for (int k = 0; k < 2; k++) {
            Villager v = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
            v.setNoGravity(true);
            ItemStack carrier = armour("plate_carrier");
            ArmorStacks.setInserts(carrier, List.of(armour("steel_plate")));
            v.setItemSlot(EquipmentSlot.CHEST, carrier);
            int before = plate(v);
            long key = Rounds.launch(level, KineticPresetRegistry.get(ids[k]),
                    v.position().add(1.4, 1.0, 0.0), new Vec3(-4.0, 0.0, 0.0), null, null);
            Rounds.step(level, key);
            Rounds.destroy(level, key);
            lost[k] = before - plate(v);
            v.discard();
        }
        LOGGER.info("fx: plate lost plain {} thermal {}", lost[0], lost[1]);
        helper.assertTrue(lost[0] > 0 && lost[1] == 3 * lost[0], "plate lost: plain " + lost[0] + ", thermal "
                + lost[1]);
        helper.succeed();
    }

    /**
     * {@code armorPenetrationMm} x {@code |v|^2 / v0^2}: velocity after 0/500/1000 blocks of real flight (drag 0.02,
     * above the build limit), then a strike at that velocity. Pen read where ywzj's {@code Ballistics.resolve} reads
     * it: {@code StrikeContext.of(damage source)}; strike event agrees.
     */
    @GameTest(template = TEMPLATE)
    public static void armourPenFallsWithImpactEnergy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset preset = KineticPresetRegistry.get(PEN);
        Vec3 start = new Vec3(centre(helper, 0.0).x, 2000.0, centre(helper, 0.0).z);
        long flight = Rounds.launch(level, preset, start, new Vec3(PEN_SPEED, 0.0, 0.0), null, null);
        double[] marks = {0.0, 500.0, 1000.0};
        Vec3[] v = new Vec3[3];
        double[] flown = new double[3];
        v[0] = new Vec3(PEN_SPEED, 0.0, 0.0);
        for (int k = 1, n = 0; k < 3 && n < 200; n++) {
            helper.assertTrue(Rounds.step(level, flight), "flight ended");
            double d = Rounds.position(level, flight).x - start.x;
            if (d >= marks[k]) {
                v[k] = Rounds.velocity(level, flight);
                flown[k] = d;
                k++;
            }
        }
        Rounds.destroy(level, flight);
        Husk husk = pinnedHusk(level, centre(helper, 8.0));
        float[] pen = new float[3];
        double[] energy = new double[3];
        StringBuilder log = new StringBuilder();
        for (int k = 0; k < 3; k++) {
            STRIKES.clear();
            HURTS.clear();
            long key = Rounds.launch(level, preset, centre(helper, 14.0), new Vec3(0.0, -v[k].length(), 0.0), null,
                    null);
            Rounds.step(level, key);
            Rounds.destroy(level, key);
            ProjectileStrikeEvent s = STRIKES.stream().filter(e -> Long.valueOf(key).equals(e.key())).findFirst()
                    .orElse(null);
            StrikeContext round = HURTS.stream().filter(e -> e.getEntity() == husk)
                    .map(e -> StrikeContext.of(e.getSource())).filter(c -> c != null).findFirst().orElse(null);
            helper.assertTrue(s != null && round != null, "missed at " + marks[k]);
            pen[k] = round.threat().penetrationMm();
            energy[k] = round.velocity().lengthSqr();
            helper.assertTrue(s.threat().penetrationMm() == pen[k], "event " + s.threat().penetrationMm() + " source "
                    + pen[k]);
            log.append(String.format(Locale.ROOT, " %.0f blocks: v %.2f pen %.2f mm;", flown[k], v[k].length(),
                    pen[k]));
        }
        husk.discard();
        LOGGER.info("fx: energy-scaled pen{}", log);
        helper.assertTrue(pen[0] == PEN_MM && pen[1] < pen[0] && pen[2] < pen[1], "pen " + pen[0] + " " + pen[1]
                + " " + pen[2]);
        for (int k = 1; k < 3; k++) {
            double ratio = energy[k] / energy[0];
            helper.assertTrue(Math.abs(pen[k] / pen[0] - ratio) < 1.0e-5, marks[k] + ": pen ratio " + pen[k] / pen[0]
                    + ", energy ratio " + ratio);
        }
        helper.succeed();
    }

    /**
     * Step cost of effect-free rounds, 2000 x launch+step+destroy, best of 31, 3 passes: clear, into a pinned husk
     * (pass-through), into stone. Logs only.
     */
    @GameTest(template = TEMPLATE, required = false, batch = "wflib_fx_bench", timeoutTicks = 400)
    public static void effectFreeStepCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset plain = KineticPresetRegistry.get(BENCH);
        StringBuilder out = new StringBuilder();
        BlockPos wall = BlockPos.containing(centre(helper, 12.0));
        String[] cases = {"clear", "husk", "stone"};
        for (int rep = 0; rep < 3; rep++) {
            for (String c : cases) {
                Husk husk = c.equals("husk") ? pinnedHusk(level, centre(helper, 8.0)) : null;
                if (c.equals("stone")) {
                    level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                }
                double best = Double.MAX_VALUE;
                for (int r = 0; r < 31; r++) {
                    long t0 = System.nanoTime();
                    for (int n = 0; n < 2000; n++) {
                        long key = Rounds.launch(level, plain, centre(helper, 5.0), new Vec3(0.0, RIFLE, 0.0), null,
                                null);
                        Rounds.step(level, key);
                        Rounds.destroy(level, key);
                    }
                    best = Math.min(best, (System.nanoTime() - t0) / 2000.0);
                }
                if (husk != null) {
                    husk.discard();
                }
                level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
                out.append(String.format(Locale.ROOT, " %s %.0f ns;", c, best));
            }
        }
        STRIKES.clear();
        HURTS.clear();
        LOGGER.info("fx bench (effect-free, launch+step+destroy, best of 31 x 2000):{}", out);
        helper.succeed();
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static long downOnto(ServerLevel level, GameTestHelper helper, ResourceLocation preset, double from) {
        return Rounds.launch(level, KineticPresetRegistry.get(preset), centre(helper, from),
                new Vec3(0.0, -4.0, 0.0), null, null);
    }

    private static Husk pinnedHusk(ServerLevel level, Vec3 at) {
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.moveTo(at.x, at.y, at.z);
        husk.setNoAi(true);
        husk.setNoGravity(true);
        level.addFreshEntity(husk);
        return husk;
    }

    private static ItemStack armour(String preset) {
        return new ItemStack(ModItems.armorItem(ArmorPreset.rl(preset)));
    }

    private static int plate(LivingEntity v) {
        ItemStack plate = ArmorStacks.inserts(v.getItemBySlot(EquipmentSlot.CHEST)).get(0);
        return ArmorStacks.condition(plate, ArmorProfiles.specFor(plate));
    }

    private static Vec3 centre(GameTestHelper helper, double height) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, origin.getY() + height, origin.getZ() + 0.5);
    }
}
