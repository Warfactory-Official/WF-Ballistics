package com.wf.wflib.probe;

import com.wf.wflib.WFLib;
import com.wf.wflib.network.WFNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Channelled probe actions in progress, one per player; see {@link ProbeActions.TimedEntity}. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class ProbeJobs {

    /** Horizontal drift the player may make from where they started (entity job: relative to the target). */
    static final double PLAYER_DRIFT = 0.75;

    public enum Reason {
        STOPPED, MOVED, HURT, TARGET_HURT, TARGET_MOVED, TARGET_GONE, OUT_OF_REACH, CONDITION;

        public Component message() {
            return Component.translatable("probe.wflib.job." + name().toLowerCase(Locale.ROOT));
        }
    }

    private static final Map<UUID, Job> JOBS = new HashMap<>();

    private ProbeJobs() {
    }

    abstract static class Job {
        final ServerPlayer player;
        final ResourceLocation action;
        final int arg;
        final int total;
        final Vec3 playerStart;
        int elapsed;

        Job(ServerPlayer player, ResourceLocation action, int arg, int total) {
            this.player = player;
            this.action = action;
            this.arg = arg;
            this.total = total;
            this.playerStart = player.position();
        }

        /** Null while it may continue. */
        abstract Reason check();

        /** Player's horizontal walk from where it started. */
        Reason drift() {
            Vec3 now = player.position();
            double dx = now.x - playerStart.x;
            double dz = now.z - playerStart.z;
            return dx * dx + dz * dz > PLAYER_DRIFT * PLAYER_DRIFT ? Reason.MOVED : null;
        }

        abstract void finish();
    }

    static final class EntityJob extends Job {
        final Entity target;
        final ProbeActions.TimedEntity handler;
        final Vec3 targetStart;

        EntityJob(ServerPlayer player, Entity target, ProbeActions.TimedEntity handler, ResourceLocation action,
                  int arg, int total) {
            super(player, action, arg, total);
            this.target = target;
            this.handler = handler;
            this.targetStart = target.position();
        }

        /**
         * Measured between the two, not in the world: player and target on one moving platform (a ship's deck) keep
         * their offset while both travel. Blame = whichever travelled further.
         */
        @Override
        Reason drift() {
            Vec3 player = this.player.position();
            Vec3 target = this.target.position();
            double dx = (player.x - target.x) - (playerStart.x - targetStart.x);
            double dz = (player.z - target.z) - (playerStart.z - targetStart.z);
            if (dx * dx + dz * dz <= PLAYER_DRIFT * PLAYER_DRIFT) {
                return null;
            }
            return player.distanceToSqr(playerStart) >= target.distanceToSqr(targetStart) ? Reason.MOVED : Reason.TARGET_MOVED;
        }

        @Override
        Reason check() {
            if (target.isRemoved()) {
                return Reason.TARGET_GONE;
            }
            if (target.getBoundingBox().distanceToSqr(player.getEyePosition()) > ProbeActions.MAX_RANGE_SQR) {
                return Reason.OUT_OF_REACH;
            }
            return handler.holds(player, target, arg) ? null : Reason.CONDITION;
        }

        @Override
        void finish() {
            handler.perform(player, target, arg);
        }
    }

    static final class BlockJob extends Job {
        final ServerLevel level;
        final BlockPos pos;
        final BlockState state;
        final ProbeActions.TimedBlock handler;

        BlockJob(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state,
                 ProbeActions.TimedBlock handler, ResourceLocation action, int arg, int total) {
            super(player, action, arg, total);
            this.level = level;
            this.pos = pos.immutable();
            this.state = state;
            this.handler = handler;
        }

        @Override
        Reason check() {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(state.getBlock())) {
                return Reason.TARGET_GONE;
            }
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > ProbeActions.MAX_RANGE_SQR) {
                return Reason.OUT_OF_REACH;
            }
            return handler.holds(player, level, pos, level.getBlockState(pos), arg) ? null : Reason.CONDITION;
        }

        @Override
        void finish() {
            handler.perform(player, level, pos, level.getBlockState(pos), arg);
        }
    }

    static void start(ServerPlayer player, Job job) {
        JOBS.put(player.getUUID(), job);
        WFNetwork.sendToPlayer(player, ProbeJobPacket.started(job.action, job.arg, job.total));
    }

    /** @return whether {@code player} had a job */
    public static boolean cancel(ServerPlayer player, Reason reason) {
        Job job = JOBS.remove(player.getUUID());
        if (job == null) {
            return false;
        }
        WFNetwork.sendToPlayer(player, ProbeJobPacket.cancelled(reason));
        return true;
    }

    /** Stops every job on {@code target} (it was shot, it drove off); the owner decides when. */
    public static void cancelOn(Entity target, Reason reason) {
        for (Iterator<Job> it = JOBS.values().iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job instanceof EntityJob entityJob && entityJob.target == target) {
                it.remove();
                WFNetwork.sendToPlayer(job.player, ProbeJobPacket.cancelled(reason));
            }
        }
    }

    public static boolean busy(ServerPlayer player) {
        return JOBS.containsKey(player.getUUID());
    }

    static void forget(ServerPlayer player) {
        JOBS.remove(player.getUUID());
    }

    /** Test seam: runs every job one tick. */
    public static void tickAll() {
        if (JOBS.isEmpty()) {
            return;
        }
        java.util.List<Job> done = new java.util.ArrayList<>();
        for (Iterator<Job> it = JOBS.values().iterator(); it.hasNext(); ) {
            Job job = it.next();
            Reason reason = job.player.isRemoved() || !job.player.isAlive() ? Reason.TARGET_GONE : job.drift();
            if (reason == null) {
                reason = job.check();
            }
            if (reason != null) {
                it.remove();
                WFNetwork.sendToPlayer(job.player, ProbeJobPacket.cancelled(reason));
                continue;
            }
            if (++job.elapsed >= job.total) {
                it.remove();
                WFNetwork.sendToPlayer(job.player, ProbeJobPacket.finished());
                done.add(job);
            }
        }
        done.forEach(Job::finish); // outside the loop: a handler may start the next job
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickAll();
    }

    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getNewDamage() > 0) {
            cancel(player, Reason.HURT);
        }
    }
}
