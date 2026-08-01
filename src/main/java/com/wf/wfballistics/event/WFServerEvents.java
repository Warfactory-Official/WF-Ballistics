package com.wf.wfballistics.event;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.api.MissileData;
import com.wf.wfballistics.api.WFBallisticsAPI;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.debug.MissileDebug;
import com.wf.wfballistics.item.MissilePreset;
import com.wf.wfballistics.item.MissilePresetRegistry;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.sim.SimMissileManager;
import com.wf.wfballistics.sim.SimMissileRegistry;
import com.wf.wfballistics.swarm.SwarmManager;
import com.wf.wfballistics.util.FragmentationUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side global driver for the missile simulation system.
 */
@Mod.EventBusSubscriber(modid = WFBallistics.MODID)
public final class WFServerEvents {
    private WFServerEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.level instanceof ServerLevel level) {
            SimMissileManager.tick(level);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wfballistics")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("interceptmode")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("Intercept mode: " + MissileSimConfig.INTERCEPT_MODE), false);
                            return 1;
                        })
                        .then(Commands.literal("chance")
                                .executes(ctx -> setInterceptMode(ctx.getSource(), MissileSimConfig.InterceptResolution.CHANCE_ROLL)))
                        .then(Commands.literal("in_world")
                                .executes(ctx -> setInterceptMode(ctx.getSource(), MissileSimConfig.InterceptResolution.IN_WORLD))))
                .then(Commands.literal("frag")
                        .executes(ctx -> spawnFrag(ctx.getSource(), 16, 1.2))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> spawnFrag(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"), 1.2))
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.0, 10.0))
                                        .executes(ctx -> spawnFrag(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                DoubleArgumentType.getDouble(ctx, "speed"))))))
                .then(Commands.literal("intercept")
                        .then(Commands.literal("nearest")
                                .executes(ctx -> interceptNearest(ctx.getSource())))
                        .then(Commands.argument("target", UuidArgument.uuid())
                                .executes(ctx -> interceptUuid(ctx.getSource(), UuidArgument.getUuid(ctx, "target")))))
                .then(Commands.literal("list")
                        .executes(ctx -> listMissiles(ctx.getSource())))
                .then(Commands.literal("simlist")
                        .executes(ctx -> simList(ctx.getSource())))
                .then(Commands.literal("debug")
                        .executes(ctx -> debugStatus(ctx.getSource()))
                        .then(Commands.literal("on")
                                .executes(ctx -> setDebug(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setDebug(ctx.getSource(), false))))
                .then(Commands.literal("track")
                        .then(Commands.literal("latest")
                                .executes(ctx -> trackLatest(ctx.getSource())))
                        .then(Commands.argument("target", UuidArgument.uuid())
                                .executes(ctx -> trackUuid(ctx.getSource(), UuidArgument.getUuid(ctx, "target")))))
                .then(Commands.literal("retarget")
                        .executes(ctx -> retarget(ctx.getSource())))
                .then(Commands.literal("swarm")
                        .then(Commands.argument("count", IntegerArgumentType.integer(2, 32))
                                .executes(ctx -> spawnSwarm(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"))))));
    }

    /**
     * Scatters a spherical burst of bomblets from the player's eye position.
     */
    private static int spawnFrag(CommandSourceStack src, int count, double speed) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Vec3 origin = player.getEyePosition();
        FragmentationUtil.burst(player.serverLevel(), origin, count, speed);
        src.sendSuccess(() -> Component.literal("Spawned " + count + " bomblets"), true);
        return count;
    }

    private static int setInterceptMode(CommandSourceStack src, MissileSimConfig.InterceptResolution mode) {
        MissileSimConfig.INTERCEPT_MODE = mode;
        src.sendSuccess(() -> Component.literal("Intercept mode set to " + mode), true);
        return 1;
    }

    /**
     * Launches a real interceptor from the player's eye in NEAREST mode (targeting mode 1: it auto-acquires
     * the closest non-friendly missile each tick).
     */
    private static int interceptNearest(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Vec3 spawn = player.getEyePosition().add(player.getLookAngle().scale(2.0));
        spawnInterceptor(player.serverLevel(), player, spawn, null);
        src.sendSuccess(() -> Component.literal("Launched interceptor (nearest hostile)."), true);
        return 1;
    }

    /**
     * Launches an interceptor locked on a specific missile UUID (targeting mode 2). Resolves against real
     * entities first, then the off-world simulation (a sim interceptor closes on the simulated track).
     */
    private static int interceptUuid(CommandSourceStack src, UUID target) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Vec3 spawn = player.getEyePosition().add(player.getLookAngle().scale(2.0));
        if (level.getEntity(target) instanceof MissileEntity) {
            spawnInterceptor(level, player, spawn, target);
            src.sendSuccess(() -> Component.literal("Launched interceptor locked on " + target), true);
            return 1;
        }
        if (SimMissileRegistry.get(level).getById(target) != null) {
            SimMissileManager.launchInterceptor(level, spawn, target, MissileSimConfig.DEFAULT_INTERCEPT_CHANCE);
            src.sendSuccess(() -> Component.literal("Launched simulated interceptor at " + target), true);
            return 1;
        }
        src.sendFailure(Component.literal("No missile with UUID " + target));
        return 0;
    }

    /**
     * Lists nearby missiles with their UUID (used to lock one), type, phase, speed, fuel and stealth flag.
     * Each line is click-to-target — clicking suggests {@code /wfballistics intercept <uuid>}.
     */
    private static int listMissiles(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        double r = 512.0;
        AABB box = player.getBoundingBox().inflate(r);
        List<MissileEntity> ms = level.getEntitiesOfClass(MissileEntity.class, box, MissileEntity::isAlive);
        if (ms.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No missiles within " + (int) r + " blocks."), false);
            return 0;
        }
        ms.sort(Comparator.comparingDouble(player::distanceToSqr));
        int total = ms.size();
        src.sendSuccess(() -> Component.literal(total + " missile(s) nearby (click a line to target it):")
                .withStyle(ChatFormatting.GOLD), false);
        int limit = Math.min(total, 30);
        for (int i = 0; i < limit; i++) {
            MissileEntity m = ms.get(i);
            int fuelPct = m.getFuelCapacity() > 0 ? Math.round(100.0f * m.getFuel() / m.getFuelCapacity()) : 0;
            int dist = (int) Math.sqrt(player.distanceToSqr(m));
            String tags = (m.isInterceptor() ? " [INT]" : "") + (m.isStealth() ? " [STEALTH]" : "")
                    + (m.getEvasion() > 0.0f ? " [EVA " + Math.round(m.getEvasion() * 100) + "%]" : "");
            String uuid = m.getUUID().toString();
            String line = String.format("• %s  %dm  %s  spd %.1f  fuel %d%%%s  %s",
                    m.getModelId().getPath(), dist, m.getPhase(), m.getCruiseSpeed(), fuelPct, tags, uuid);
            ChatFormatting colour = m.isStealth() ? ChatFormatting.LIGHT_PURPLE
                    : (m.isInterceptor() ? ChatFormatting.AQUA : ChatFormatting.YELLOW);
            Component comp = Component.literal(line).withStyle(s -> s
                    .withColor(colour)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/wfballistics intercept " + uuid)));
            src.sendSuccess(() -> comp, false);
        }
        return total;
    }

    /**
     * Lists every missile currently in off-world simulation for the player's dimension, nearest-to-target first,
     * with the honest fuel picture: powered range vs. distance and whether it will actually reach under power.
     * Each line is click-to-track for the debug logger.
     */
    private static int simList(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        List<MissileData> sims = WFBallisticsAPI.listSimMissiles(level);
        if (sims.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No missiles in off-world simulation."), false);
            return 0;
        }
        sims.sort(Comparator.comparingDouble(MissileData::horizontalDistance));
        int total = sims.size();
        src.sendSuccess(() -> Component.literal(total + " simulated missile(s), nearest target first "
                + "(click a line to track it):").withStyle(ChatFormatting.GOLD), false);
        int limit = Math.min(total, 40);
        for (int i = 0; i < limit; i++) {
            MissileData d = sims.get(i);
            boolean reach = d.canReach();
            String eta = reach ? String.format("ETA ~%.0fs", d.etaTicks() / 20.0) : "WON'T REACH";
            String line = String.format("• %s  %s  dist %dm  spd %.1f  fuel %d (range %dm)  %s  %s",
                    d.model(), d.phase(), (int) d.horizontalDistance(), d.speed(),
                    d.fuel(), (int) d.poweredRange(), eta, d.id());
            ChatFormatting colour = reach ? ChatFormatting.YELLOW : ChatFormatting.RED;
            Component comp = Component.literal(line).withStyle(s -> s
                    .withColor(colour)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/wfballistics track " + d.id())));
            src.sendSuccess(() -> comp, false);
        }
        return total;
    }

    private static int debugStatus(CommandSourceStack src) {
        String tracking = MissileDebug.latest() != null ? " (latest " + MissileDebug.latest() + ")" : "";
        src.sendSuccess(() -> Component.literal("Missile debug logging: "
                + (MissileDebug.enabled() ? "ON" : "OFF") + tracking), false);
        return 1;
    }

    private static int setDebug(CommandSourceStack src, boolean on) {
        MissileDebug.setEnabled(on);
        src.sendSuccess(() -> Component.literal("Missile debug logging " + (on ? "enabled" : "disabled")
                + ". New missiles are auto-tracked; watch the server log."), true);
        return 1;
    }

    private static int trackLatest(CommandSourceStack src) {
        UUID latest = MissileDebug.latest();
        if (latest == null) {
            src.sendFailure(Component.literal("No missile tracked yet. Enable debug then launch one."));
            return 0;
        }
        MissileDebug.track(latest);
        src.sendSuccess(() -> Component.literal("Tracking latest missile " + latest), true);
        return 1;
    }

    private static int trackUuid(CommandSourceStack src, UUID id) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        if (level.getEntity(id) instanceof MissileEntity m) {
            WFBallisticsAPI.openTelemetry(m);
        } else if (SimMissileRegistry.get(level).getById(id) != null) {
            WFBallisticsAPI.openTelemetry(id, level.getGameTime());
        } else {
            src.sendFailure(Component.literal("No missile with UUID " + id + " in this dimension."));
            return 0;
        }
        MissileDebug.track(id);
        String hint = MissileDebug.enabled() ? "" : " Enable output with /wfballistics debug on.";
        src.sendSuccess(() -> Component.literal("Tracking missile " + id + "." + hint), true);
        return 1;
    }

    /**
     * Re-tasks the player's nearest owned missile/drone (same control id, or same WarForge faction) to strike
     * the entity the player is looking at — e.g. redirecting a loitering munition mid-flight.
     */
    private static int retarget(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Entity aim = aimedEntity(player);
        if (aim == null) {
            src.sendFailure(Component.literal("Look directly at the entity you want re-tasked as the target."));
            return 0;
        }
        UUID mine = player.getUUID();
        UUID myTeam = com.wf.wfballistics.compat.WarforgeCompat.factionOfPlayer(mine);
        AABB box = player.getBoundingBox().inflate(512.0);
        List<MissileEntity> ms = level.getEntitiesOfClass(MissileEntity.class, box,
                m -> m.isAlive() && !m.isInterceptor()
                        && (mine.equals(m.getControlId()) || (myTeam != null && myTeam.equals(m.getTeamId()))));
        if (ms.isEmpty()) {
            src.sendFailure(Component.literal("No missile of yours nearby to re-task."));
            return 0;
        }
        ms.sort(Comparator.comparingDouble(player::distanceToSqr));
        MissileEntity m = ms.get(0);
        m.setDesignatedTarget(aim.getUUID());
        src.sendSuccess(() -> Component.literal("Re-tasked " + m.getModelId().getPath() + " onto "
                + aim.getName().getString()), true);
        return 1;
    }

    /**
     * Launches a swarm of {@code count} missiles toward the player's aim: one commander flying the mission and
     * the rest holding a wedge formation on it (see {@link SwarmManager}). If the commander is intercepted, the
     * nearest survivor takes over and the rest re-form on it.
     */
    private static int spawnSwarm(CommandSourceStack src, int count) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Vec3 target = swarmTarget(level, player);
        long swarmId = SwarmManager.newId(level);
        UUID team = WarforgeCompat.factionOfPlayer(player.getUUID());
        MissilePreset preset = MissilePresetRegistry.get(MissilePresetRegistry.rl("cruise"));
        Vec3 base = player.getEyePosition().add(player.getLookAngle().scale(3.0));
        for (int i = 0; i < count; i++) {
            MissileEntity m = preset.build(level, target);
            m.setControlId(player.getUUID());
            m.setTeamId(team);
            m.setSwarmId(swarmId);
            if (i == 0) {
                m.setCommander(true);
            }
            double side = (i % 2 == 0 ? 1.0 : -1.0) * ((double) (i + 1) / 2) * 2.0;
            Vec3 spawn = base.add(side, i * 0.4, 0.0);
            m.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), 0.0f);
            level.addFreshEntity(m);
        }
        src.sendSuccess(() -> Component.literal("Launched a swarm of " + count + " (1 commander, "
                + (count - 1) + " in formation)."), true);
        return count;
    }

    private static Vec3 swarmTarget(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(512.0));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
    }

    private static Entity aimedEntity(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(256.0));
        AABB search = new AABB(eye, end).inflate(1.0);
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, search,
                x -> x != player && x.isAlive() && x.isPickable() && !(x instanceof MissileEntity))) {
            Optional<Vec3> clip = e.getBoundingBox().inflate(0.3).clip(eye, end);
            if (clip.isPresent()) {
                double d = clip.get().distanceToSqr(eye);
                if (d < bestSq) {
                    bestSq = d;
                    best = e;
                }
            }
        }
        return best;
    }

    private static void spawnInterceptor(ServerLevel level, ServerPlayer player, Vec3 spawn, UUID lock) {
        MissilePreset preset = MissilePresetRegistry.get(MissilePresetRegistry.rl("interceptor"));
        MissileEntity m = preset.build(level, spawn);
        m.setControlId(player.getUUID());
        m.setTeamId(com.wf.wfballistics.compat.WarforgeCompat.factionOfPlayer(player.getUUID()));
        if (lock != null) {
            m.setInterceptLock(lock);
        }
        m.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), 0.0f);
        level.addFreshEntity(m);
    }
}
