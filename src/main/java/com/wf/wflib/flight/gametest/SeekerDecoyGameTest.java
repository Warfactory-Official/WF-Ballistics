package com.wf.wflib.flight.gametest;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.ModEntities;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.SeekerDecoy;
import com.wf.wflib.missile.MissileBuilder;
import com.wf.wflib.missile.SeekerMode;
import com.wf.wflib.recon.Band;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code decoyChance}: one roll per flare salvo at a fixed p; unset keeps the strength race. Target = armor stand
 * (THERMAL 0) => the race seduces every time, a salvo roll at p only p of the time. Vertical lane: one loaded column.
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class SeekerDecoyGameTest {

    private static final String TEMPLATE = "empty";
    private static final int N = 400;

    /** THERMAL decoy without an entity type of its own. */
    static final class Flare extends ArmorStand implements SeekerDecoy {
        Flare(Level level) {
            super(EntityType.ARMOR_STAND, level);
            this.setNoGravity(true);
        }

        @Override
        public float decoyStrength(Band band) {
            return band == Band.THERMAL ? 0.01f : 0.0f;
        }
    }

    private static ArmorStand stand(ServerLevel level, Vec3 at) {
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        stand.moveTo(at.x, at.y, at.z);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        return stand;
    }

    private static Flare flare(ServerLevel level, Vec3 at) {
        Flare f = new Flare(level);
        f.moveTo(at.x, at.y, at.z);
        level.addFreshEntity(f);
        return f;
    }

    /** IR round 40 above {@code target}, diving at it, locked. */
    private static MissileEntity round(ServerLevel level, Entity target, float decoyChance) {
        Vec3 at = target.position().add(0.0, 40.0, 0.0);
        MissileBuilder b = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .target(target.position())
                .detonation(WarheadRegistry.rl("inert"))
                .seekerMode(SeekerMode.INFRARED, 30.0f, 0.0)
                .designatedTarget(target.getUUID())
                .decoyChance(decoyChance);
        MissileEntity m = b.build();
        m.moveTo(at.x, at.y, at.z, 0.0f, 90.0f);
        m.setDeltaMovement(0.0, -4.0, 0.0);
        level.addFreshEntity(m);
        return m;
    }

    private static boolean seduced(MissileEntity m, Entity target) {
        return m.seeker().getDesignatedTargetId() != null && !target.getUUID().equals(m.seeker().getDesignatedTargetId());
    }

    /**
     * Seduced fraction over {@link #N} rounds: salvo {@code first} at launch, salvo {@code second} (null = none)
     * {@code gap} ticks later.
     */
    private static double fraction(ServerLevel level, ArmorStand target, float chance, int first, int second, int gap) {
        List<Entity> spawned = new ArrayList<>();
        int hits = 0;
        for (int i = 0; i < N; i++) {
            MissileEntity m = round(level, target, chance);
            for (int k = 0; k < first; k++) {
                spawned.add(flare(level, target.position().add(1.0 + k, 0.0, 0.0)));
            }
            m.tick();
            if (second > 0 && !seduced(m, target)) {
                m.tickCount += gap;
                for (int k = 0; k < second; k++) {
                    spawned.add(flare(level, target.position().add(0.0, 0.0, 1.0 + k)));
                }
                m.tick();
            }
            if (seduced(m, target)) {
                hits++;
            }
            m.discard();
            spawned.forEach(Entity::discard);
            spawned.clear();
        }
        return hits / (double) N;
    }

    /** 4 flares in one salvo, p 0.5: seduced ~0.5 (per-flare rolls ~0.94, strength race 1.0). */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aSalvoRollsOnceAtDecoyChance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArmorStand target = stand(level, helper.absoluteVec(new Vec3(1.5, 20.0, 1.5)));
        double p = fraction(level, target, 0.5f, 4, 0, 0);
        target.discard();
        if (Math.abs(p - 0.5) > 0.08) {
            helper.fail(String.format(Locale.ROOT, "4-flare salvo at p 0.5 seduced %.3f", p));
            return;
        }
        helper.succeed();
    }

    /** Second salvo past the window rolls again: ~0.75; inside the window it rides the first roll: ~0.5. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aLaterSalvoRollsAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArmorStand target = stand(level, helper.absoluteVec(new Vec3(1.5, 20.0, 1.5)));
        double apart = fraction(level, target, 0.5f, 1, 1, 21);
        double within = fraction(level, target, 0.5f, 1, 1, 10);
        target.discard();
        if (Math.abs(apart - 0.75) > 0.08 || Math.abs(within - 0.5) > 0.08) {
            helper.fail(String.format(Locale.ROOT, "two salvos 21 ticks apart %.3f (want 0.75), 10 apart %.3f (0.5)",
                    apart, within));
            return;
        }
        helper.succeed();
    }

    /** Unset: per-decoy race, p = s / (s + 0) = 1 against a cold target. Chance 0: never. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void unsetKeepsTheStrengthRace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArmorStand target = stand(level, helper.absoluteVec(new Vec3(1.5, 20.0, 1.5)));
        double race = fraction(level, target, Float.NaN, 1, 0, 0);
        double never = fraction(level, target, 0.0f, 3, 3, 21);
        target.discard();
        if (race != 1.0 || never != 0.0) {
            helper.fail(String.format(Locale.ROOT, "unset %.3f (want 1), chance 0 %.3f (want 0)", race, never));
            return;
        }
        helper.succeed();
    }
}
