package com.wf.wflib.flight.gametest;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.ModEntities;
import com.wf.wflib.WFLib;
import com.wf.wflib.flight.FlightStageRegistry;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/** A burnt-out glider trades height for speed: drag-free and diving straight down, it gains g every tick. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class GlideGameTest {

    private static final String TEMPLATE = "empty";
    private static final double START_SPEED = 2.0;
    private static final int TICKS = 20;

    @GameTest(template = TEMPLATE)
    public static void aDivingGliderGainsGravity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 ground = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
        Vec3 from = ground.add(0.0, 90.0, 0.0);
        ResourceLocation direct = FlightStageRegistry.rl("direct");
        MissileEntity glider = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .target(ground)
                .detonation(WarheadRegistry.rl("inert"))
                .cruiseSpeed(START_SPEED)
                .acceleration(0.0)
                .fuel(MissileEntity.FuelType.SOLID, 1)
                .drag(0.0, true)
                .ascentStage(direct)
                .cruiseStage(direct)
                .attackStage(direct)
                .build();
        glider.moveTo(from.x, from.y, from.z, 0.0f, 90.0f);
        glider.setDeltaMovement(0.0, -START_SPEED, 0.0);
        level.addFreshEntity(glider);
        for (int i = 0; i < TICKS; i++) {
            glider.tick();
        }
        double speed = glider.getDeltaMovement().length();
        glider.kill();
        // Drag-free without the term: exactly START_SPEED. Launch ticks before the glide gain nothing.
        double most = START_SPEED + TICKS * KineticPreset.DEFAULT_GRAVITY;
        if (!(speed > START_SPEED + TICKS / 2 * KineticPreset.DEFAULT_GRAVITY) || speed > most + 1.0e-6) {
            helper.fail(String.format(Locale.ROOT, "glide speed %.3f after %d ticks, want (%.3f, %.3f]", speed,
                    TICKS, START_SPEED + TICKS / 2 * KineticPreset.DEFAULT_GRAVITY, most));
            return;
        }
        helper.succeed();
    }
}
