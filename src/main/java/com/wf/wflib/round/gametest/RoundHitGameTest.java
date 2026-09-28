package com.wf.wflib.round.gametest;

import com.mojang.authlib.GameProfile;
import com.norwood.ahf.hit.AhfHitSource;
import com.norwood.ahf.lagcomp.EntityHistory;
import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.api.StrikeContext;
import com.wf.wflib.armor.ArmorPreset;
import com.wf.wflib.armor.ArmorSystem;
import com.wf.wflib.damage.DamageResistanceHandler;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.RoundDamageSource;
import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.RoundNetwork;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.warhead.WarheadRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Round hits through AHF: rewind, parts, headshot rules, pass-through, pierce, energy damage, wire fields. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class RoundHitGameTest {

    private static final String TEMPLATE = "empty";
    private static final String CLAIMED = "wflib_test_claimed";
    /** Bodies stand this high: above the arena's barrier lid. */
    private static final double HEIGHT = 10.0;
    /** Shots start {@code LANE / 2} in front of a body and stop as far behind: inside the arena footprint. */
    private static final double LANE = 2.4;
    private static final double MOVE = 2.5;

    private static final ResourceLocation RECORDING = WarheadRegistry.rl("test_hit_recording_warhead");
    /** Straight, no drag or gravity, one point of damage, passes through bodies. */
    private static final ResourceLocation LINE = KineticPresetRegistry.rl("test_line_round");
    /** {@link #LINE} that stops on bodies, headshot x2. */
    private static final ResourceLocation HEAD = KineticPresetRegistry.rl("test_head_round");
    private static final ResourceLocation PIERCING = KineticPresetRegistry.rl("test_piercing_round");
    private static final ResourceLocation ENERGY = KineticPresetRegistry.rl("test_energy_round");
    /** Ten points, no headshot factor. */
    private static final ResourceLocation TEN = KineticPresetRegistry.rl("test_ten_round");
    /** {@link #LINE} that stops on bodies and detonates the recording warhead there. */
    private static final ResourceLocation STOP = KineticPresetRegistry.rl("test_stop_round");
    private static final double ENERGY_MASS = 0.004;
    private static final double ENERGY_PER_KJ = 4.0;
    private static final float PIERCE_DT = 3.0f;
    private static final float PIERCE_DR = 0.5f;

    private static final List<Vec3> DETONATIONS = new CopyOnWriteArrayList<>();
    private static final List<ProjectileStrikeEvent> STRIKES = new CopyOnWriteArrayList<>();
    /** {@link #WATCHED} rounds' strikes: span ticks, so kept out of {@link #STRIKES}, which other tests clear. */
    private static final List<ProjectileStrikeEvent> WATCHED_STRIKES = new CopyOnWriteArrayList<>();
    private static final List<Hurt> HURTS = new CopyOnWriteArrayList<>();
    /** Amount after every other listener (armour included). */
    private static final List<Hurt> LANDED = new CopyOnWriteArrayList<>();
    /** Recorders run only inside {@link #record} or for {@link #WATCHED} rounds: other tests' never grow the lists. */
    private static boolean recording;
    private static final java.util.Set<Long> WATCHED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Runs inside each recorded strike's hurt. */
    @Nullable
    private static Runnable duringHit;
    /** Runs inside each recorded strike event. */
    @Nullable
    private static java.util.function.Consumer<ProjectileStrikeEvent> duringStrike;

    /** {@code pierceDT/DR}: what armour resolves this damage with; {@code when}: the source's hit tick. */
    private record Hurt(Entity victim, float amount, float pierceDT, float pierceDR,
                        @Nullable List<HitboxPart> parts, long when) {
    }

    static {
        WarheadRegistry.register(RECORDING, (source, pos) -> DETONATIONS.add(pos));
        KineticPresetRegistry.register(KineticPreset.builder(LINE, null, RECORDING)
                .speed(4.0).drag(0.0).gravity(0.0).life(40).impactDamage(1.0).caliber(5.56).noChunkLoading()
                .passesThroughEntities().build());
        KineticPresetRegistry.register(KineticPreset.builder(HEAD, null, WarheadRegistry.rl("inert"))
                .speed(4.0).drag(0.0).gravity(0.0).life(40).impactDamage(2.0).headshot(2.0).caliber(5.56)
                .noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(PIERCING, null, WarheadRegistry.rl("inert"))
                .speed(4.0).drag(0.0).gravity(0.0).life(40).impactDamage(2.0).pierce(PIERCE_DT, PIERCE_DR)
                .caliber(5.56).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(ENERGY, null, WarheadRegistry.rl("inert"))
                .speed(40.0).drag(0.0).gravity(0.0).life(40).mass(ENERGY_MASS).energyDamage(ENERGY_PER_KJ)
                .caliber(5.56).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(TEN, null, WarheadRegistry.rl("inert"))
                .speed(4.0).drag(0.0).gravity(0.0).life(40).impactDamage(10.0).caliber(5.56).noChunkLoading()
                .build());
        KineticPresetRegistry.register(KineticPreset.builder(STOP, null, RECORDING)
                .speed(4.0).drag(0.0).gravity(0.0).life(40).impactDamage(1.0).caliber(5.56).noChunkLoading()
                .build());
        ArmorSystem.claimResolution(e -> e.getTags().contains(CLAIMED));
    }

    /** One step of round {@code key} with the recorders on. */
    private static void record(ServerLevel level, long key) {
        recording = true;
        try {
            Rounds.step(level, key);
        } finally {
            recording = false;
        }
    }

    private static boolean recorded(Object key) {
        return recording || key instanceof Long l && WATCHED.contains(l);
    }

    @SubscribeEvent
    public static void onStrike(ProjectileStrikeEvent event) {
        if (recorded(event.key())) {
            (event.key() instanceof Long l && WATCHED.contains(l) ? WATCHED_STRIKES : STRIKES).add(event);
            if (duringStrike != null) {
                duringStrike.accept(event);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        StrikeContext strike = StrikeContext.of(event.getSource());
        if (strike != null && recorded(strike.key())) {
            DamageSource source = event.getSource();
            HURTS.add(new Hurt(event.getEntity(), event.getAmount(), ArmorSystem.pierceDT(source),
                    ArmorSystem.pierceDR(source), strike.parts(), ((AhfHitSource) source).hitGameTime()));
            if (duringHit != null) {
                duringHit.run();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLanded(LivingIncomingDamageEvent event) {
        StrikeContext strike = StrikeContext.of(event.getSource());
        if (strike != null && recorded(strike.key())) {
            LANDED.add(new Hurt(event.getEntity(), event.getAmount(), 0.0f, 0.0f, strike.parts(), 0L));
        }
    }

    // --- lag compensation ---------------------------------------------------------------------

    /** Target moved {@link #MOVE}: the rewound round hits the old spot with its part, and only there. */
    @GameTest(template = TEMPLATE)
    public static void aRewoundRoundHitsWhereTheTargetWas(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer v = player(helper, new FakePlayer(level, profile()));
        long[] then = new long[1];
        Vec3[] was = new Vec3[1];
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    then[0] = level.getGameTime();
                    was[0] = v.position();
                    helper.assertTrue(EntityHistory.has(v, then[0]), "AHF captured no history for the target");
                    v.moveTo(was[0].x + MOVE, was[0].y, was[0].z, 0.0F, 0.0F);
                    face(v);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        int rewind = (int) (EntityHistory.broadcastTick(level) - then[0]);
                        Vec3 old = was[0].add(0.0, 1.0, LANE / 2);
                        Vec3 now = v.position().add(0.0, 1.0, LANE / 2);
                        List<ProjectileStrikeEvent> hit = shoot(level, LINE, old, rewind, v);
                        helper.assertTrue(hit.size() == 1 && List.of(HitboxPart.TORSO).equals(hit.get(0).parts()),
                                "rewound round through the old torso: " + describe(hit));
                        helper.assertTrue(shoot(level, LINE, old, 0, v).isEmpty(),
                                "live round hit a target " + MOVE + " blocks from where it flew");
                        helper.assertTrue(shoot(level, LINE, now, rewind, v).isEmpty(),
                                "rewound round hit the live spot");
                        List<ProjectileStrikeEvent> live = shoot(level, LINE, now, 0, v);
                        helper.assertTrue(live.size() == 1 && List.of(HitboxPart.TORSO).equals(live.get(0).parts()),
                                "live round through the live torso: " + describe(live));
                        Husk fresh = pinned(helper, new Husk(EntityType.HUSK, level));
                        fresh.moveTo(was[0].x - MOVE, was[0].y, was[0].z);
                        try {
                            helper.assertTrue(shoot(level, LINE, fresh.position().add(0.0, 1.0, LANE / 2), rewind,
                                    fresh).size() == 1, "rewound round missed a body with no history then (live)");
                        } finally {
                            fresh.discard();
                        }
                    } finally {
                        v.discard();
                    }
                })
                .thenSucceed();
    }

    /**
     * Target moves {@link #MOVE} in x after tick {@code a}. A round launched with rewind {@code X - a} misses on its
     * first step and crosses only the new spot on its second, one tick later: that step sweeps tick {@code a + 1}.
     */
    @GameTest(template = TEMPLATE)
    public static void aLaterStepRewindsFromItsOwnTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer v = player(helper, new FakePlayer(level, profile()));
        long[] a = new long[1];
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    a[0] = EntityHistory.broadcastTick(level);
                    helper.assertTrue(EntityHistory.has(v, a[0]), "AHF captured no history for the target");
                    v.moveTo(v.getX() + MOVE, v.getY(), v.getZ(), 0.0F, 0.0F);
                    face(v);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    int rewind = (int) (EntityHistory.broadcastTick(level) - a[0]);
                    long key = Rounds.launch(level, KineticPresetRegistry.get(LINE),
                            v.position().add(0.0, 1.0, LANE * 1.5), new Vec3(0.0, 0.0, -LANE), null, null, 1.0f,
                            rewind, Rounds.NO_SEQ);
                    WATCHED.add(key);
                    Rounds.step(level, key);
                    helper.assertTrue(hits(key, v).isEmpty(), "first step, clear of both spots, struck " + hits(key, v));
                    helper.runAfterDelay(1, () -> {
                        WATCHED.clear();
                        Rounds.destroy(level, key);
                        v.discard();
                        List<ProjectileStrikeEvent> hit = hits(key, v);
                        helper.assertTrue(hit.size() == 1 && Math.abs(hit.get(0).location().x - v.getX()) < 0.5,
                                "second step through the spot broadcast a tick after the first: " + describe(hit));
                        helper.succeed();
                    });
                });
    }

    private static final String HOLD_BATCH = "wflib_rewind_hold";
    private static final String FIRE_TICK_BATCH = "wflib_rewind_fire_tick";

    /** Hold: step 2 sweeps tick {@code a + 1} (target at {@code +MOVE}), not live ({@code -MOVE}). */
    @GameTest(template = TEMPLATE, batch = HOLD_BATCH)
    public static void aHeldRewindSweepsTheLaterStepAsSeen(GameTestHelper helper) {
        rewindPolicy(helper, true);
    }

    /** Fire-tick only: step 2 sweeps live ({@code -MOVE}), not tick {@code a + 1} ({@code +MOVE}). */
    @GameTest(template = TEMPLATE, batch = FIRE_TICK_BATCH)
    public static void aSpentRewindSweepsTheLaterStepLive(GameTestHelper helper) {
        rewindPolicy(helper, false);
    }

    @AfterBatch(batch = HOLD_BATCH)
    public static void restoreHold(ServerLevel level) {
        Rounds.rewindWholeFlight = true;
    }

    @AfterBatch(batch = FIRE_TICK_BATCH)
    public static void restoreFireTick(ServerLevel level) {
        Rounds.rewindWholeFlight = true;
    }

    /** Own batch: {@link Rounds#rewindWholeFlight} is global. Two rounds, lanes {@code +MOVE} and {@code -MOVE}. */
    private static void rewindPolicy(GameTestHelper helper, boolean wholeFlight) {
        ServerLevel level = helper.getLevel();
        Rounds.rewindWholeFlight = wholeFlight;
        FakePlayer v = player(helper, new FakePlayer(level, profile()));
        Vec3 p0 = v.position();
        long[] a = new long[1];
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    a[0] = EntityHistory.broadcastTick(level);
                    helper.assertTrue(EntityHistory.has(v, a[0]), "AHF captured no history for the target");
                    v.moveTo(p0.x + MOVE, p0.y, p0.z, 0.0F, 0.0F);
                    face(v);
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertTrue(EntityHistory.broadcastTick(level) > a[0], "no tick since a");
                    v.moveTo(p0.x - MOVE, p0.y, p0.z, 0.0F, 0.0F);
                    face(v);
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    int rewind = (int) (EntityHistory.broadcastTick(level) - a[0]);
                    long seen = launchWatched(level, p0.add(MOVE, 1.0, LANE * 1.5), rewind);
                    long live = launchWatched(level, p0.add(-MOVE, 1.0, LANE * 1.5), rewind);
                    Rounds.step(level, seen);
                    Rounds.step(level, live);
                    helper.assertTrue(hits(seen, v).isEmpty() && hits(live, v).isEmpty(),
                            "first step, clear of every spot, struck " + describe(hits(seen, v)) + describe(hits(live, v)));
                    helper.runAfterDelay(1, () -> {
                        WATCHED.clear();
                        Rounds.destroy(level, seen);
                        Rounds.destroy(level, live);
                        v.discard();
                        List<ProjectileStrikeEvent> hit = hits(wholeFlight ? seen : live, v);
                        List<ProjectileStrikeEvent> miss = hits(wholeFlight ? live : seen, v);
                        helper.assertTrue(hit.size() == 1 && miss.isEmpty(), (wholeFlight ? "held" : "spent")
                                + " rewind, step 2: struck " + describe(hit) + ", must miss " + describe(miss));
                        helper.succeed();
                    });
                });
    }

    private static long launchWatched(ServerLevel level, Vec3 at, int rewind) {
        long key = Rounds.launch(level, KineticPresetRegistry.get(LINE), at, new Vec3(0.0, 0.0, -LANE), null, null,
                1.0f, rewind, Rounds.NO_SEQ);
        WATCHED.add(key);
        return key;
    }

    /** Seated in a moving cart: struck where it sits now, stamped live, whatever the rewind. */
    @GameTest(template = TEMPLATE)
    public static void aPassengerIsStruckLive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer watcher = player(helper, new FakePlayer(level, profile()));
        watcher.moveTo(watcher.getX() - MOVE, watcher.getY(), watcher.getZ(), 0.0F, 0.0F);
        Minecart cart = EntityType.MINECART.create(level);
        Vec3 at = spot(helper);
        cart.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        cart.setNoGravity(true);
        level.addFreshEntity(cart);
        Husk rider = pinned(helper, new Husk(EntityType.HUSK, level));
        rider.startRiding(cart, true);
        long[] a = new long[1];
        Vec3[] was = new Vec3[1];
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    a[0] = EntityHistory.broadcastTick(level);
                    was[0] = rider.position();
                    helper.assertTrue(rider.isPassenger() && EntityHistory.has(rider, a[0]),
                            "setup: seated " + rider.isPassenger() + ", history " + EntityHistory.has(rider, a[0]));
                    cart.moveTo(cart.getX(), cart.getY(), cart.getZ() + MOVE, 0.0F, 0.0F);
                    cart.positionRider(rider);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        int rewind = (int) (EntityHistory.broadcastTick(level) - a[0]);
                        helper.assertTrue(Math.abs(rider.getZ() - was[0].z - MOVE) < 1.0e-6,
                                "setup: rider moved with the cart to " + rider.position());
                        HURTS.clear();
                        rider.invulnerableTime = 0;
                        long key = Rounds.launch(level, KineticPresetRegistry.get(LINE),
                                new Vec3(rider.getX() + LANE / 2, rider.getEyeY(), rider.getZ()),
                                new Vec3(-LANE, 0.0, 0.0), null, null, 1.0f, rewind, Rounds.NO_SEQ);
                        record(level, key);
                        Rounds.destroy(level, key);
                        Hurt h = null;
                        for (Hurt x : HURTS) {
                            if (x.victim() == rider) {
                                h = x;
                            }
                        }
                        helper.assertTrue(h != null && h.when() == RoundDamageSource.LIVE,
                                "rewound round through the seated rider's live spot: " + h);
                    } finally {
                        rider.discard();
                        cart.discard();
                        watcher.discard();
                    }
                })
                .thenSucceed();
    }

    /** Static husk grazed 0.2 outside its box (inside the 0.3 pick slack): hit rewound as live. */
    @GameTest(template = TEMPLATE)
    public static void aGrazeWithinThePickSlackHitsRewound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer watcher = player(helper, new FakePlayer(level, profile()));
        Husk husk = pinned(helper, new Husk(EntityType.HUSK, level));
        husk.moveTo(husk.getX() + MOVE, husk.getY(), husk.getZ());
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    try {
                        long a = EntityHistory.broadcastTick(level) - 1;
                        helper.assertTrue(EntityHistory.has(husk, a), "AHF captured no history for the husk");
                        Vec3 graze = new Vec3(husk.getBoundingBox().maxX + 0.2, husk.getY() + 1.0,
                                husk.getZ() + LANE / 2);
                        helper.assertTrue(shoot(level, LINE, graze, 0, husk).size() == 1, "live graze missed");
                        helper.assertTrue(shoot(level, LINE, graze, 1, husk).size() == 1, "rewound graze missed");
                        helper.assertTrue(shoot(level, LINE, graze.add(0.2, 0.0, 0.0), 1, husk).isEmpty(),
                                "0.4 outside the box hit");
                    } finally {
                        husk.discard();
                        watcher.discard();
                    }
                })
                .thenSucceed();
    }

    /** A strike listener destroying the round: the round swapped into its slot is neither struck on nor burst. */
    @GameTest(template = TEMPLATE)
    public static void aRoundDestroyedByItsStrikeLeavesItsNeighbourAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinned(helper, new Husk(EntityType.HUSK, level));
        KineticPreset stop = KineticPresetRegistry.get(STOP);
        long first = Rounds.launch(level, stop, husk.position().add(0.0, 1.0, LANE / 2), new Vec3(0.0, 0.0, -LANE),
                null, null);
        long other = Rounds.launch(level, stop, husk.position().add(0.0, 30.0, 0.0), new Vec3(0.0, 0.0, -LANE),
                null, null);
        DETONATIONS.clear();
        STRIKES.clear();
        duringStrike = e -> Rounds.destroy(level, first);
        try {
            record(level, first);
        } finally {
            duringStrike = null;
        }
        Vec3 left = Rounds.position(level, other);
        Rounds.destroy(level, other);
        husk.discard();
        helper.assertTrue(hits(first, husk).size() == 1, "setup: struck " + STRIKES);
        helper.assertTrue(left != null && DETONATIONS.isEmpty(),
                "neighbour after the destroy: at " + left + ", detonations " + DETONATIONS);
        helper.succeed();
    }

    private static List<ProjectileStrikeEvent> hits(long key, Entity target) {
        List<ProjectileStrikeEvent> out = new ArrayList<>();
        for (List<ProjectileStrikeEvent> list : List.of(STRIKES, WATCHED_STRIKES)) {
            for (ProjectileStrikeEvent e : list) {
                if (e.target() == target && Long.valueOf(key).equals(e.key())) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** Along -z from {@code at}, one step of {@link #LANE}. @return strikes on {@code target} */
    private static List<ProjectileStrikeEvent> shoot(ServerLevel level, ResourceLocation preset, Vec3 at, int rewind,
                                                     Entity target) {
        STRIKES.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(preset), at, new Vec3(0.0, 0.0, -LANE), null, null,
                1.0f, rewind, Rounds.NO_SEQ);
        record(level, key);
        Rounds.destroy(level, key);
        return hits(key, target);
    }

    private static String describe(List<ProjectileStrikeEvent> strikes) {
        StringBuilder sb = new StringBuilder("[");
        for (ProjectileStrikeEvent e : strikes) {
            sb.append(e.location()).append(' ').append(e.parts()).append(';');
        }
        return sb.append(']').toString();
    }

    // --- headshot rules -----------------------------------------------------------------------

    /** Player: rig HEAD doubles, torso does not; parts ride the damage; claimed players take factor 1. */
    @GameTest(template = TEMPLATE)
    public static void aPlayerHeadshotIsTheRigHeadUnlessClaimed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer v = player(helper, hurtable(level));
        try {
            Vec3 feet = v.position();
            Hurt head = hurt(level, HEAD, feet.add(0.0, 1.75, LANE / 2), v);
            Hurt body = hurt(level, HEAD, feet.add(0.0, 1.0, LANE / 2), v);
            helper.assertTrue(head != null && body != null, "no damage reached the player: " + head + ", " + body);
            helper.assertTrue(List.of(HitboxPart.HEAD).equals(head.parts())
                            && List.of(HitboxPart.TORSO).equals(body.parts()),
                    "parts on the damage: head " + head.parts() + ", body " + body.parts());
            helper.assertTrue(head.amount() == 4.0f && body.amount() == 2.0f,
                    "head " + head.amount() + ", body " + body.amount() + " (want 4, 2)");
            Hurt claimed;
            setPlayerClaim(true);
            try {
                claimed = hurt(level, HEAD, feet.add(0.0, 1.75, LANE / 2), v);
            } finally {
                setPlayerClaim(false);
            }
            helper.assertTrue(claimed != null && claimed.amount() == 2.0f
                            && List.of(HitboxPart.HEAD).equals(claimed.parts()),
                    "claimed player head: " + claimed + " (want 2 with HEAD part)");
        } finally {
            v.discard();
        }
        helper.succeed();
    }

    /** No part model (spider: wider than tall) => the eye band decides; a predicate claim => factor 1. */
    @GameTest(template = TEMPLATE)
    public static void aWideMobFallsBackToTheEyeBandAndClaimsWin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Spider spider = pinned(helper, new Spider(EntityType.SPIDER, level));
        Husk claimed = pinned(helper, new Husk(EntityType.HUSK, level));
        claimed.moveTo(claimed.getX() + MOVE, claimed.getY(), claimed.getZ());
        claimed.addTag(CLAIMED);
        try {
            Vec3 feet = spider.position();
            Hurt eye = hurt(level, HEAD, feet.add(0.0, spider.getEyeHeight(), LANE / 2), spider);
            Hurt low = hurt(level, HEAD, feet.add(0.0, spider.getEyeHeight() - 0.45, LANE / 2), spider);
            helper.assertTrue(eye != null && low != null, "spider not hit: " + eye + ", " + low);
            helper.assertTrue(eye.parts() == null && low.parts() == null,
                    "a spider has no part model: " + eye.parts() + ", " + low.parts());
            helper.assertTrue(eye.amount() == 4.0f && low.amount() == 2.0f,
                    "spider eye " + eye.amount() + ", low " + low.amount() + " (want 4, 2)");
            Husk husk = pinned(helper, new Husk(EntityType.HUSK, level));
            husk.moveTo(husk.getX() - MOVE, husk.getY(), husk.getZ());
            // AHF head band starts at 0.74 of height (1.44): 1.46 is HEAD, 0.28 under the eye.
            Hurt bandHead = hurt(level, HEAD, husk.position().add(0.0, 1.46, LANE / 2), husk);
            husk.discard();
            helper.assertTrue(bandHead != null && bandHead.amount() == 4.0f
                            && List.of(HitboxPart.HEAD).equals(bandHead.parts()),
                    "husk head below the eye band: " + bandHead + " (want 4 with HEAD part)");
            Hurt claimedHead = hurt(level, HEAD, claimed.position().add(0.0, claimed.getEyeHeight(), LANE / 2), claimed);
            helper.assertTrue(claimedHead != null && claimedHead.amount() == 2.0f
                            && List.of(HitboxPart.HEAD).equals(claimedHead.parts()),
                    "claimed husk head: " + claimedHead + " (want 2 with HEAD part)");
        } finally {
            spider.discard();
            claimed.discard();
        }
        helper.succeed();
    }

    /** WFLib armour on a mob: the struck part's slot, not coverage-weighted. Helmet: DT 6, DR 25%. */
    @GameTest(template = TEMPLATE)
    public static void aStruckPartResolvesItsOwnSlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinned(helper, new Husk(EntityType.HUSK, level));
        husk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.armorItem(ArmorPreset.rl("combat_helmet"))));
        try {
            hurt(level, TEN, husk.position().add(0.0, 1.0, LANE / 2), husk);
            Hurt torso = landed(husk);
            hurt(level, TEN, husk.position().add(0.0, husk.getEyeHeight(), LANE / 2), husk);
            Hurt head = landed(husk);
            helper.assertTrue(torso != null && torso.amount() == 10.0f, "torso under a lone helmet: " + torso);
            helper.assertTrue(head != null && Math.abs(head.amount() - 3.0f) < 1.0e-4f, "helmeted head: " + head);
        } finally {
            husk.discard();
        }
        helper.succeed();
    }

    @Nullable
    private static Hurt landed(LivingEntity victim) {
        for (Hurt h : LANDED) {
            if (h.victim() == victim) {
                return h;
            }
        }
        return null;
    }

    @Nullable
    private static Hurt hurt(ServerLevel level, ResourceLocation preset, Vec3 at, LivingEntity victim) {
        HURTS.clear();
        LANDED.clear();
        victim.invulnerableTime = 0;
        long key = Rounds.launch(level, KineticPresetRegistry.get(preset), at, new Vec3(0.0, 0.0, -LANE), null, null);
        record(level, key);
        Rounds.destroy(level, key);
        for (Hurt h : HURTS) {
            if (h.victim() == victim) {
                return h;
            }
        }
        return null;
    }

    private static void setPlayerClaim(boolean claimed) {
        try {
            Field f = ArmorSystem.class.getDeclaredField("externalPlayerResolution");
            f.setAccessible(true);
            f.setBoolean(null, claimed);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- pass-through, multi-target -----------------------------------------------------------

    /** One tick's segment: both bodies struck in order, then the wall behind them stops the round. */
    @GameTest(template = TEMPLATE)
    public static void aPassThroughRoundStillMeetsTheWallBehind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk upper = pinned(helper, new Husk(EntityType.HUSK, level));
        Husk lower = pinned(helper, new Husk(EntityType.HUSK, level));
        Vec3 c = upper.position();
        upper.moveTo(c.x, c.y + 8.0, c.z);
        lower.moveTo(c.x, c.y + 3.0, c.z);
        BlockPos wall = BlockPos.containing(c.x, c.y - 1.0, c.z);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        STRIKES.clear();
        DETONATIONS.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(LINE), new Vec3(c.x, c.y + 12.0, c.z),
                new Vec3(0.0, -16.0, 0.0), null, null);
        record(level, key);
        Vec3 still = Rounds.position(level, key);
        Rounds.destroy(level, key);
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        upper.discard();
        lower.discard();
        List<Entity> order = new ArrayList<>();
        for (ProjectileStrikeEvent e : STRIKES) {
            if (Long.valueOf(key).equals(e.key())) {
                order.add(e.target());
            }
        }
        Vec3 det = DETONATIONS.isEmpty() ? null : DETONATIONS.get(0);
        helper.assertTrue(order.equals(List.of(upper, lower)), "struck in one tick: " + order);
        helper.assertTrue(still == null && det != null && Math.abs(det.y - (wall.getY() + 1)) < 1.0e-6,
                "round after the bodies: still flying at " + still + ", detonation " + det + " (wall top "
                        + (wall.getY() + 1) + ")");
        helper.succeed();
    }

    // --- pierce, energy damage ----------------------------------------------------------------

    /**
     * Preset pierce rides on the round's source; the thread context is untouched: damage resolved inside the hit reads
     * the outer context, and it stands after.
     */
    @GameTest(template = TEMPLATE)
    public static void pierceRidesOnTheSourceOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinned(helper, new Husk(EntityType.HUSK, level));
        float[] nested = {Float.NaN, Float.NaN};
        DamageSource generic = level.damageSources().generic();
        try {
            DamageResistanceHandler.setup(9.0f, 0.9f);
            duringHit = () -> {
                nested[0] = ArmorSystem.pierceDT(generic);
                nested[1] = ArmorSystem.pierceDR(generic);
            };
            Hurt h = hurt(level, PIERCING, husk.position().add(0.0, 1.0, LANE / 2), husk);
            duringHit = null;
            helper.assertTrue(h != null && h.pierceDT() == PIERCE_DT && h.pierceDR() == PIERCE_DR,
                    "pierce on the hit: " + h);
            helper.assertTrue(nested[0] == 9.0f && nested[1] == 0.9f,
                    "damage inside the hit: " + nested[0] + ", " + nested[1] + " (want the outer 9, 0.9)");
            helper.assertTrue(DamageResistanceHandler.currentPierceDT() == 9.0f
                            && DamageResistanceHandler.currentPierceDR() == 0.9f,
                    "outer context after: " + DamageResistanceHandler.currentPierceDT() + ", "
                            + DamageResistanceHandler.currentPierceDR());
            Hurt plain = hurt(level, HEAD, husk.position().add(0.0, 1.0, LANE / 2), husk);
            helper.assertTrue(plain != null && plain.pierceDT() == 0.0f && plain.pierceDR() == 0.0f,
                    "pierce on a non-piercing round: " + plain);
        } finally {
            duringHit = null;
            DamageResistanceHandler.reset();
            husk.discard();
        }
        helper.succeed();
    }

    /** Opted-in preset: 0.5 m (20 v)^2 / 1000 per kJ, times the launch's scale. */
    @GameTest(template = TEMPLATE)
    public static void energyDamageScalesWithTheLaunch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinned(helper, new Husk(EntityType.HUSK, level));
        try {
            KineticPreset preset = KineticPresetRegistry.get(ENERGY);
            Vec3 v = new Vec3(0.0, -preset.muzzleSpeed(), 0.0);
            float full = energyHit(level, husk, v, 1.0f);
            float half = energyHit(level, husk, v, 0.5f);
            double mps = preset.muzzleSpeed() * 20.0;
            double want = 0.5 * ENERGY_MASS * mps * mps / 1000.0 * ENERGY_PER_KJ;
            helper.assertTrue(Math.abs(full - want) < want * 1.0e-5 && Math.abs(half - want * 0.5) < want * 1.0e-5,
                    "energy damage " + full + " / scaled " + half + ", want " + want + " / " + want * 0.5);
        } finally {
            husk.discard();
        }
        helper.succeed();
    }

    private static float energyHit(ServerLevel level, Husk husk, Vec3 v, float scale) {
        HURTS.clear();
        husk.invulnerableTime = 0;
        Vec3 at = husk.position().add(0.0, 1.0 - v.y * 0.5, 0.0);
        long key = Rounds.launch(level, KineticPresetRegistry.get(ENERGY), at, v, null, null, scale, 0,
                Rounds.NO_SEQ);
        record(level, key);
        Rounds.destroy(level, key);
        for (Hurt h : HURTS) {
            if (h.victim() == husk) {
                return h.amount();
            }
        }
        return Float.NaN;
    }

    // --- wire ---------------------------------------------------------------------------------

    /** Spawn carries shooter + seq; end carries position + reason; a first-step end shares its spawn's packet. */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void theWireCarriesShooterSeqAndEnd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer watcher = new ServerPlayer(level.getServer(), level, profile(), ClientInformation.createDefault());
        List<RoundNetwork.RoundPacket> packets = capture(level, watcher);
        Vec3 c = spot(helper);
        watcher.moveTo(c.x, c.y, c.z, 0.0F, 0.0F);
        level.addFreshEntity(watcher);
        Husk shooter = pinned(helper, new Husk(EntityType.HUSK, level));
        shooter.moveTo(shooter.getX() + MOVE, shooter.getY(), shooter.getZ());
        BlockPos floor = BlockPos.containing(c.x + 1.0, c.y - 1.0, c.z);
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        KineticPreset line = KineticPresetRegistry.get(LINE);
        long slow = Rounds.launch(level, line, new Vec3(c.x + 1.0, c.y + 2.5, c.z), new Vec3(0.0, -1.0, 0.0),
                shooter, null, 1.0f, 0, 42);
        long fast = Rounds.launch(level, line, new Vec3(c.x + 1.0, c.y + 2.5, c.z), new Vec3(0.0, -4.0, 0.0),
                shooter, null, 1.0f, 0, 43);
        helper.runAfterDelay(8, () -> {
            level.setBlockAndUpdate(floor, Blocks.AIR.defaultBlockState());
            shooter.discard();
            watcher.discard();
            List<RoundNetwork.RoundPacket> wire = new ArrayList<>();
            for (RoundNetwork.RoundPacket p : packets) {
                ByteBuf buf = Unpooled.buffer();
                RoundNetwork.RoundPacket.STREAM_CODEC.encode(buf, p);
                wire.add(RoundNetwork.RoundPacket.STREAM_CODEC.decode(buf));
            }
            RoundNetwork.Spawn slowSpawn = spawn(wire, slow);
            RoundNetwork.End slowEnd = end(wire, slow);
            helper.assertTrue(slowSpawn != null && slowSpawn.shooter() == shooter.getId() && slowSpawn.seq() == 42,
                    "spawn " + slowSpawn + ", shooter id " + shooter.getId());
            helper.assertTrue(slowEnd != null && slowEnd.reason() == RoundEnd.BLOCK
                            && Math.abs(slowEnd.y() - (floor.getY() + 1)) < 1.0e-9,
                    "end " + slowEnd + ", floor top " + (floor.getY() + 1));
            boolean shared = false;
            for (RoundNetwork.RoundPacket p : wire) {
                shared |= p.spawns().stream().anyMatch(s -> s.key() == fast && s.seq() == 43)
                        && p.ends().stream().anyMatch(e -> e.key() == fast);
            }
            helper.assertTrue(shared, "first-step end not in its spawn's packet: " + wire.size() + " packets");
            helper.succeed();
        });
    }

    @Nullable
    private static RoundNetwork.Spawn spawn(List<RoundNetwork.RoundPacket> wire, long key) {
        for (RoundNetwork.RoundPacket p : wire) {
            for (RoundNetwork.Spawn s : p.spawns()) {
                if (s.key() == key) {
                    return s;
                }
            }
        }
        return null;
    }

    @Nullable
    private static RoundNetwork.End end(List<RoundNetwork.RoundPacket> wire, long key) {
        for (RoundNetwork.RoundPacket p : wire) {
            for (RoundNetwork.End e : p.ends()) {
                if (e.key() == key) {
                    return e;
                }
            }
        }
        return null;
    }

    /** Swaps in a listener that has the rounds channel and keeps what is sent (a FakePlayer is never sent rounds). */
    private static List<RoundNetwork.RoundPacket> capture(ServerLevel level, ServerPlayer player) {
        List<RoundNetwork.RoundPacket> out = new CopyOnWriteArrayList<>();
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
                if (packet instanceof ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof RoundNetwork.RoundPacket rounds) {
                    out.add(rounds);
                }
            }

            @Override
            public boolean hasChannel(ResourceLocation payloadId) {
                return RoundNetwork.RoundPacket.TYPE.id().equals(payloadId);
            }
        };
        return out;
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static GameProfile profile() {
        return new GameProfile(UUID.randomUUID(), "wflib_test");
    }

    /** FakePlayer that can be hurt: both invulnerability gates opened. */
    private static FakePlayer hurtable(ServerLevel level) {
        FakePlayer p = new FakePlayer(level, profile()) {
            @Override
            public boolean isInvulnerableTo(DamageSource source) {
                return false;
            }
        };
        ObfuscationReflectionHelper.setPrivateValue(ServerPlayer.class, p, 0, "spawnInvulnerableTime");
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    /** Standing at {@link #spot}, facing +z, in the level (AHF captures players in it). */
    private static FakePlayer player(GameTestHelper helper, FakePlayer p) {
        Vec3 at = spot(helper);
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        face(p);
        p.setPose(Pose.STANDING);
        helper.getLevel().addFreshEntity(p);
        return p;
    }

    /** Rig poses off {@code yBodyRot}. */
    private static void face(FakePlayer p) {
        p.setYRot(0.0F);
        p.setXRot(0.0F);
        p.setYHeadRot(0.0F);
        p.yBodyRot = 0.0F;
        p.yBodyRotO = 0.0F;
        p.yHeadRotO = 0.0F;
    }

    private static <T extends Mob> T pinned(GameTestHelper helper, T mob) {
        Vec3 at = spot(helper);
        mob.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        mob.setYBodyRot(0.0F);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }

    /** Middle of the 3x3 footprint, {@link #HEIGHT} up. */
    private static Vec3 spot(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 1.5, origin.getY() + HEIGHT, origin.getZ() + 1.5);
    }
}
