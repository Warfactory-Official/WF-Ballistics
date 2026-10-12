package com.wf.wflib.telemetry;

import com.wf.wflib.WFLib;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.network.TelemetryPacket;
import com.wf.wflib.recon.ReconOwners;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reporter positions -> players carrying a {@code wflib:terminal}. No range limit: the link is the network. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class TelemetryLink {

    public static final int PERIOD_TICKS = 10;

    private static final Map<Level, Set<Entity>> REPORTERS = new ConcurrentHashMap<>();
    /** Got a non-empty frame last period => owed one clearing frame. */
    private static final Set<UUID> RECEIVING = new HashSet<>();

    private TelemetryLink() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof TelemetryReporter && !event.getLevel().isClientSide) {
            REPORTERS.computeIfAbsent(event.getLevel(), k -> ConcurrentHashMap.newKeySet()).add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        Set<Entity> set = REPORTERS.get(event.getLevel());
        if (set != null && set.remove(event.getEntity()) && set.isEmpty()) {
            REPORTERS.remove(event.getLevel());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RECEIVING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % PERIOD_TICKS != 0) {
            return;
        }
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (carriesTerminal(player) || RECEIVING.contains(player.getUUID())) {
                viewers.add(player);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }
        List<Report> reports = collect(level);
        for (ServerPlayer viewer : viewers) {
            List<TelemetryPacket.Entry> frame = new ArrayList<>();
            if (carriesTerminal(viewer)) {
                UUID side = ReconOwners.owningEntity(viewer);
                for (Report report : reports) {
                    if (receives(viewer.getUUID(), side, report.telemetry())) {
                        frame.add(report.entry(viewer.getUUID().equals(report.telemetry().operator())));
                    }
                }
            }
            if (frame.isEmpty() && !RECEIVING.remove(viewer.getUUID())) {
                continue;
            }
            if (!frame.isEmpty()) {
                RECEIVING.add(viewer.getUUID());
            }
            PacketDistributor.sendToPlayer(viewer, new TelemetryPacket(level.dimension(), frame));
        }
    }

    /** Operator always; else same side or WarForge allies. */
    public static boolean receives(UUID viewer, @Nullable UUID viewerSide, Telemetry telemetry) {
        return viewer.equals(telemetry.operator()) || allied(viewerSide, telemetry.team());
    }

    /** Same side, or WarForge allies. */
    public static boolean allied(@Nullable UUID viewerSide, @Nullable UUID team) {
        return viewerSide != null && team != null
                && (viewerSide.equals(team) || WarforgeCompat.areFactionsAllied(viewerSide, team));
    }

    public static boolean carriesTerminal(ServerPlayer player) {
        return player.getInventory().hasAnyMatching(stack -> stack.is(ModItems.TERMINAL.get()));
    }

    private record Report(Entity entity, Telemetry telemetry) {
        TelemetryPacket.Entry entry(boolean own) {
            return new TelemetryPacket.Entry(entity.getUUID().getLeastSignificantBits()
                    ^ entity.getUUID().getMostSignificantBits(), telemetry.kind(), entity.getX(), entity.getY(),
                    entity.getZ(), heading(entity), telemetry.label(), own);
        }
    }

    /** Degrees clockwise from north, from velocity; at rest, the body yaw. */
    private static float heading(Entity entity) {
        var v = entity.getDeltaMovement();
        if (v.x * v.x + v.z * v.z > 1.0e-4) {
            return (float) Math.toDegrees(Math.atan2(-v.x, v.z)) + 180.0f;
        }
        return entity.getYRot() + 180.0f;
    }

    private static List<Report> collect(ServerLevel level) {
        Set<Entity> set = REPORTERS.get(level);
        if (set == null) {
            return List.of();
        }
        List<Report> out = new ArrayList<>(set.size());
        for (Entity entity : set) {
            Telemetry telemetry = entity.isRemoved() ? null : ((TelemetryReporter) entity).datalink();
            if (telemetry != null) {
                out.add(new Report(entity, telemetry));
            }
        }
        return out;
    }
}
