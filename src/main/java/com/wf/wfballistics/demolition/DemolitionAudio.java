package com.wf.wfballistics.demolition;

import com.wf.wfballistics.WFSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Plays a demolition blast as a directional, distance-aware event. */
public final class DemolitionAudio {

    /** ~340 m/s at 20 tps ≈ 17 blocks per tick. */
    private static final double BLOCKS_PER_TICK = 17.0;
    /** Within this, you hear the close blast; beyond it, the distant rumble. */
    private static final double NEAR_RANGE = 24.0;
    private static final float NEAR_VOLUME = 4.0F;
    private static final float DISTANT_VOLUME = 8.0F;

    private DemolitionAudio() {
    }

    public static void playBlast(ServerLevel level, Vec3 pos, float power, double range) {
        double maxRange = range * DISTANT_VOLUME;
        float powerScale = Math.min(1.0F, power / 4.0F);
        long seed = level.random.nextLong();
        for (ServerPlayer player : level.players()) {
            double dist = Math.sqrt(player.distanceToSqr(pos));
            if (dist > maxRange) {
                continue;
            }
            boolean near = dist <= NEAR_RANGE;
            SoundEvent sound = near
                    ? WFSounds.MINING_CHARGE_BLAST.get()
                    : WFSounds.MINING_CHARGE_BLAST_DISTANT.get();
            float volume = (near ? NEAR_VOLUME : DISTANT_VOLUME) * powerScale;
            int delay = (int) (dist / BLOCKS_PER_TICK);
            DelayedSounds.schedule(level, delay, player, pos.x, pos.y, pos.z,
                    sound, SoundSource.BLOCKS, volume, 1.0F, seed);
        }
    }
}
