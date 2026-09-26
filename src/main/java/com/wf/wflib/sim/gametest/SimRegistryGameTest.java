package com.wf.wflib.sim.gametest;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.ModEntities;
import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraSpec;
import com.wf.wflib.sim.SimMissile;
import com.wf.wflib.sim.SimMissileManager;
import com.wf.wflib.sim.SimWorld;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Off-world tier through the unified registry: offload is clean, the round trip keeps the whole missile. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class SimRegistryGameTest {

    private static final String TEMPLATE = "empty";

    private static MissileEntity armedCruiser(ServerLevel level, Vec3 at, Vec3 target) {
        MissileEntity m = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .target(target).highAltitude(at.y).cruiseSpeed(2.0).health(33.0f)
                .downedAction(MissileEntity.DownedAction.SPIN_OUT).rcs(0.4f).seeker(CameraSpec.TV_SEEKER)
                .fuel(MissileEntity.FuelType.SOLID, 500).startInCruise().startArmed().build();
        m.moveTo(at.x, at.y, at.z);
        m.setDeltaMovement(target.subtract(at).normalize().scale(2.0));
        level.addFreshEntity(m);
        return m;
    }

    /** Armed missile offloads: entity gone (not shot down and left behind), one record in the tier. */
    @GameTest(template = TEMPLATE)
    public static void anArmedMissileLeavesTheWorldCleanly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 40.0, 1.5));
        MissileEntity m = armedCruiser(level, at, at.add(3000.0, 0.0, 0.0));
        UUID id = m.getUUID();
        SimMissileManager.startSim(m);
        Entity left = level.getEntity(id);
        if (left != null && !left.isRemoved()) {
            helper.fail("offloaded missile still in the world (downed " + m.damage().isDowned() + ")");
            return;
        }
        SimMissile record = SimMissileManager.find(level, id);
        if (record == null || record.body == null) {
            helper.fail("no sim record with a body for " + id);
            return;
        }
        SimMissileManager.tier(level).remove(record);
        helper.succeed();
    }

    /** Record -> entity keeps what the old field copy dropped (health, downed action, rcs, seeker), fuel spent. */
    @GameTest(template = TEMPLATE)
    public static void aMissileComesBackWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 40.0, 1.5));
        MissileEntity m = armedCruiser(level, at, at.add(3000.0, 0.0, 0.0));
        SimMissile record = SimMissile.fromEntity(m);
        m.leaveWorld();
        record.fuel = 123;
        MissileEntity back = record.toEntity(level, at.add(10.0, 0.0, 0.0));
        String wrong = null;
        if (!back.getUUID().equals(record.id)) {
            wrong = "uuid";
        } else if (back.damage().getHealth() != 33.0f) {
            wrong = "health " + back.damage().getHealth();
        } else if (back.signature().getRcs() != 0.4f) {
            wrong = "rcs " + back.signature().getRcs();
        } else if (back.seeker().spec() == null) {
            wrong = "seeker";
        } else if (back.motor().getFuel() != 123) {
            wrong = "fuel " + back.motor().getFuel();
        } else if (back.flight().getPhase() != MissileEntity.Phase.CRUISE || !back.fuze().isArmed()) {
            wrong = "phase " + back.flight().getPhase() + " armed " + back.fuze().isArmed();
        } else if (back.position().distanceTo(at.add(10.0, 0.0, 0.0)) > 1.0E-6) {
            wrong = "pos " + back.position();
        }
        if (wrong != null) {
            helper.fail("round trip lost " + wrong);
            return;
        }
        helper.succeed();
    }

    /** Persistent kind survives save -> load through the kind codec, body included. */
    @GameTest(template = TEMPLATE)
    public static void aSimRecordSurvivesSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 40.0, 1.5));
        MissileEntity m = armedCruiser(level, at, at.add(3000.0, 0.0, 0.0));
        SimMissile record = SimMissile.fromEntity(m);
        m.leaveWorld();
        CompoundTag tag = SimMissileManager.KIND.save(record);
        SimMissile loaded = SimMissileManager.KIND.load(tag);
        if (!loaded.id.equals(record.id) || !loaded.pos.equals(record.pos) || loaded.body == null
                || !loaded.body.equals(record.body)) {
            helper.fail("save/load changed the record");
            return;
        }
        if (SimWorld.get(level).tier(SimMissileManager.KIND) != SimMissileManager.tier(level)) {
            helper.fail("tier not stable per level");
            return;
        }
        helper.succeed();
    }
}
