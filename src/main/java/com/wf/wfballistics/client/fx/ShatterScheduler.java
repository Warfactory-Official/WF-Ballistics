package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/** The crack burst a flying chunk of block leaves where it hits. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class ShatterScheduler {

    /** Crack particles one shattering chunk is worth, as {@code BlockDebrisParticle.shatter} spent them. */
    private static final int CRACKS_PER_CHUNK = 16;

    /** How many shatters may be outstanding at once. */
    private static final int MAX_PENDING = 300;

    private static final List<Pending> PENDING = new ArrayList<>();

    private ShatterScheduler() {
    }

    /**
     * @param delayTicks when the chunk gets there, from now
     */
    public static void shatterAt(double x, double y, double z, BlockState state, int delayTicks) {
        if (state == null || state.isAir()) {
            return;
        }
        if (delayTicks <= 0) {
            shatter(x, y, z, state);
            return;
        }
        if (PENDING.size() >= MAX_PENDING) {
            return;
        }
        PENDING.add(new Pending(delayTicks, x, y, z, state));
    }

    private static void shatter(double x, double y, double z, BlockState state) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        BlockParticleOption crack = new BlockParticleOption(ParticleTypes.BLOCK, state);
        for (int i = 0; i < CRACKS_PER_CHUNK; i++) {
            double vx = (level.random.nextDouble() - 0.5) * 0.2;
            double vy = level.random.nextDouble() * 0.2;
            double vz = (level.random.nextDouble() - 0.5) * 0.2;
            // Forced, because a mining blast is watched from well outside vanilla's 32-block particle cull.
            level.addParticle(crack, true, x, y, z, vx, vy, vz);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            PENDING.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }

        for (int i = PENDING.size() - 1; i >= 0; i--) {
            Pending p = PENDING.get(i);
            if (--p.ticksLeft <= 0) {
                shatter(p.x, p.y, p.z, p.state);
                PENDING.remove(i);
            }
        }
    }

    private static final class Pending {
        final double x, y, z;
        final BlockState state;
        int ticksLeft;

        Pending(int ticksLeft, double x, double y, double z, BlockState state) {
            this.ticksLeft = ticksLeft;
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
        }
    }
}
