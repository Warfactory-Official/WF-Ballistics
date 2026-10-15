package com.wf.wflib.warhead.gametest;

import com.mojang.authlib.GameProfile;
import com.norwood.ahf.hit.AhfHitSource;
import com.norwood.ahf.hit.HitCategory;
import com.norwood.ahf.hit.HitboxApi;
import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.WFLib;
import com.wf.wflib.grenade.FragRays;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Blasts vs an AHF-rigged player, exploder = another player looking away. Vanilla explosion source (direct ==
 * attacker) = melee to AHF => gap-tested on the exploder's look => dropped by any {@code shouldRejectGap} caller.
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class BlastAhfGameTest {

    private static final String TEMPLATE = "empty";
    /** Past ShapedChargeGameTest (4400..5040). */
    private static final int LANE_ORIGIN = 5200;
    private static final int LANE_SPACING = 160;
    private static final double ALTITUDE = 20.0;

    private static final Map<UUID, DamageSource> SOURCES = new ConcurrentHashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        SOURCES.putIfAbsent(event.getEntity().getUUID(), event.getSource());
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aJetAtTheHeadStrikesTheHead(GameTestHelper helper) {
        jet(helper, 0, 0.95, p -> p == HitboxPart.HEAD, "HEAD");
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aJetAtTheShinsStrikesALeg(GameTestHelper helper) {
        jet(helper, 1, 0.1, HitboxPart::isLeg, "a leg");
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void fragmentsCarryTheirPart(GameTestHelper helper) {
        Lane lane = new Lane(helper, 2);
        lane.whenTicking(() -> {
            FakePlayer attacker = lane.attacker();
            FakePlayer victim = lane.victim();
            Vec3 at = victim.getBoundingBox().getCenter().add(-3.0, 0.0, 0.0);
            FragRays.Result r = FragRays.burst(lane.level, at, 400, 8.0, 2.0f, 6.0, attacker, attacker,
                    RandomSource.create(7));
            helper.runAfterDelay(1, () -> {
                lane.release();
                helper.assertTrue(r.victims() == 1, "fragment victims " + r.victims());
                assertNoGap(helper, victim, part -> true, "any part");
                helper.succeed();
            });
        });
    }

    /** Contact on the victim's -X side at {@code height} of its box, jet +X. */
    private static void jet(GameTestHelper helper, int index, double height, Predicate<HitboxPart> part,
                            String wanted) {
        Lane lane = new Lane(helper, index);
        lane.whenTicking(() -> {
            FakePlayer attacker = lane.attacker();
            FakePlayer victim = lane.victim();
            AABB box = victim.getBoundingBox();
            Vec3 at = new Vec3(box.minX - 0.05, box.minY + box.getYsize() * height, (box.minZ + box.maxZ) * 0.5);
            DamageSource vanilla = lane.level.damageSources().explosion(attacker, attacker);
            if (!HitboxApi.shouldRejectGap(victim, vanilla, HitCategory.EXPLOSION)) {
                lane.release();
                helper.fail("precondition: vanilla blast source from a player looking away is a gap");
            }
            WarheadRegistry.SHAPED_CHARGE.detonate(carrier(lane.level, attacker), at);
            helper.runAfterDelay(1, () -> {
                lane.release();
                assertNoGap(helper, victim, part, wanted);
                helper.succeed();
            });
        });
    }

    private static void assertNoGap(GameTestHelper helper, LivingEntity victim, Predicate<HitboxPart> part,
                                    String wanted) {
        DamageSource src = SOURCES.remove(victim.getUUID());
        helper.assertTrue(src != null, "victim not hurt");
        helper.assertTrue(src instanceof AhfHitSource, "blast source not an AHF hit source: " + src);
        helper.assertTrue(!HitboxApi.shouldRejectGap(victim, src, HitCategory.EXPLOSION), "blast rejected as gap");
        HitboxPart got = HitboxApi.classifyHit(victim, src, HitCategory.EXPLOSION);
        helper.assertTrue(got != null && part.test(got), "struck part " + got + ", wanted " + wanted);
    }

    private static WarheadCarrier carrier(Level level, Entity exploder) {
        return new WarheadCarrier() {
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
                return new Vec3(1.0, 0.0, 0.0);
            }

            @Override
            public Entity exploder() {
                return exploder;
            }

            @Override
            public boolean breaksBlocks() {
                return false;
            }
        };
    }

    private static final class Lane {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos origin;
        private final List<FakePlayer> players = new ArrayList<>();

        Lane(GameTestHelper helper, int index) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.origin = helper.absolutePos(BlockPos.ZERO)
                    .offset(0, (int) ALTITUDE, LANE_ORIGIN + index * LANE_SPACING);
            force(true);
        }

        /** Forced lane chunks load async; a blast before they tick finds no entities. */
        void whenTicking(Runnable then) {
            BlockPos at = this.origin.offset(4, 0, 0);
            this.helper.startSequence()
                    .thenWaitUntil(() -> this.helper.assertTrue(this.level.isPositionEntityTicking(at), "lane loading"))
                    .thenExecute(then);
        }

        /** Upright, facing +Z, hurtable by a player: FakePlayer gates opened, server PvP on (gametest default off). */
        FakePlayer victim() {
            this.level.getServer().setPvpAllowed(true);
            FakePlayer p = new FakePlayer(this.level, new GameProfile(UUID.randomUUID(), "wflib_blast_victim")) {
                @Override
                public boolean isInvulnerableTo(DamageSource source) {
                    return false;
                }

                @Override
                public boolean canHarmPlayer(Player other) {
                    return true;
                }
            };
            ObfuscationReflectionHelper.setPrivateValue(ServerPlayer.class, p, 0, "spawnInvulnerableTime");
            p.setGameMode(GameType.SURVIVAL);
            return place(p, 4.0, 0.0f);
        }

        /** 12 blocks back, facing away from the victim. */
        FakePlayer attacker() {
            return place(new FakePlayer(this.level, new GameProfile(UUID.randomUUID(), "wflib_blast_attacker")),
                    -8.0, 90.0f);
        }

        private FakePlayer place(FakePlayer p, double dx, float yaw) {
            p.moveTo(this.origin.getX() + 0.5 + dx, this.origin.getY(), this.origin.getZ() + 0.5, yaw, 0.0f);
            p.setYHeadRot(yaw);
            p.yBodyRot = yaw;
            p.setNoGravity(true);
            p.setPose(Pose.STANDING);
            this.level.addFreshEntity(p);
            this.players.add(p);
            return p;
        }

        /** Players load chunks round themselves => kept, they break other tests' unloaded-ground setups. */
        void release() {
            this.players.forEach(p -> p.discard());
            force(false);
        }

        private void force(boolean forced) {
            int cx = SectionPos.blockToSectionCoord(this.origin.getX());
            int cz = SectionPos.blockToSectionCoord(this.origin.getZ());
            for (int ox = -1; ox <= 3; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    ForcedChunks.set(this.level, cx + ox, cz + oz, forced);
                }
            }
        }
    }
}
