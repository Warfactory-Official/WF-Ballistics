package com.wf.wflib.exchange;

import com.mojang.logging.LogUtils;
import com.wf.wflib.block.entity.DronePadBlockEntity;
import com.wf.wflib.drone.CrateEntity;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Runs the arranged exchanges: watches the rendezvous, sends the collection drones, and calls the whole thing off
 * when somebody wanders past.
 */
public final class ExchangeManager {

    /** How close a player has to be to a rendezvous to burn it. */
    public static final double WATCH_RADIUS = 64.0;
    /** How often the zones are swept. */
    public static final int SWEEP_INTERVAL = 10;

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Rendezvous points are drawn from here rather than the level's shared, seeded random. */
    private static final SecureRandom SECURE = new SecureRandom();

    private ExchangeManager() {
    }

    /**
     * Arrange a meeting between two stations.
     *
     * @param senderSending true if the arranging station has cargo to bring
     * @param recipientSending true if the far station has cargo to bring, which is what makes it a trade
     * @return the new exchange, or null with a reason if it could not be arranged
     */
    public static Result arrange(MinecraftServer server, String senderCode, String recipientCode,
                                 boolean senderSending, boolean recipientSending) {
        StationRegistry stations = StationRegistry.get(server);
        StationRecord sender = stations.byCode(senderCode);
        StationRecord recipient = stations.byCode(recipientCode);
        if (sender == null) {
            return Result.refused("this pad is not registered");
        }
        if (recipient == null || !recipient.allows(senderCode)) {
            return Result.refused("that station is not accepting from this one");
        }
        if (recipient.code().equals(senderCode)) {
            return Result.refused("a station cannot trade with itself");
        }

        ExchangeRegistry exchanges = ExchangeRegistry.get(server);
        if (exchanges.activeFor(senderCode) != null) {
            return Result.refused("this station already has an exchange running");
        }
        if (exchanges.activeFor(recipientCode) != null) {
            return Result.refused("that station already has an exchange running");
        }

        ServerLevel level = server.getLevel(sender.dimension());
        if (level == null) {
            return Result.refused("this pad's dimension is not loaded");
        }
        if (!recipient.dimension().equals(sender.dimension())) {
            return Result.refused("that station is in another dimension");
        }

        Vec3 rendezvous = Obfuscation.rendezvous(SECURE, sender.launchPoint(), recipient.launchPoint());
        Exchange exchange = new Exchange(UUID.randomUUID(), sender.dimension(), rendezvous,
                new Exchange.Party(senderCode, senderSending),
                new Exchange.Party(recipientCode, recipientSending),
                level.getGameTime() + Exchange.DEFAULT_LIFETIME_TICKS);
        exchanges.add(exchange);
        LOGGER.debug("[wflib] exchange {} arranged between two stations", exchange.id());
        return Result.arranged(exchange);
    }

    /**
     * Per-tick upkeep for one dimension: sweep the zones, send collectors, retire what is finished.
     */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % SWEEP_INTERVAL != 0) {
            return;
        }
        MinecraftServer server = level.getServer();
        ExchangeRegistry exchanges = ExchangeRegistry.get(server);
        long now = level.getGameTime();

        for (Exchange exchange : exchanges.active()) {
            if (!exchange.dimension().equals(level.dimension())) {
                continue;
            }
            if (now > exchange.deadline()) {
                cancel(server, exchange, "timed out");
                continue;
            }
            if (watched(level, exchange.rendezvous())) {
                cancel(server, exchange, "a player entered the zone");
                continue;
            }
            dispatchCollectors(level, exchange);
            if (exchange.settled()) {
                exchange.complete();
                exchanges.touch();
            }
        }
        exchanges.prune(now);
    }

    /**
     * @return true if any player who could actually see it is within {@link #WATCH_RADIUS} of the zone.
     */
    public static boolean watched(ServerLevel level, Vec3 zone) {
        double radiusSqr = WATCH_RADIUS * WATCH_RADIUS;
        for (Player player : level.players()) {
            if (player.isSpectator() || player.isDeadOrDying()) {
                continue;
            }
            if (player.distanceToSqr(zone) <= radiusSqr) {
                return true;
            }
        }
        return false;
    }

    /**
     * Send a station's drone out for whatever the other side has left, once there is something to fetch.
     */
    private static void dispatchCollectors(ServerLevel level, Exchange exchange) {
        collectFor(level, exchange, exchange.sender(), exchange.recipient());
        collectFor(level, exchange, exchange.recipient(), exchange.sender());
    }

    private static void collectFor(ServerLevel level, Exchange exchange, Exchange.Party collector,
                                   Exchange.Party owner) {
        if (collector.collectorSent() || collector.collected() || owner.droppedCrate() == null) {
            return;
        }
        StationRecord station = StationRegistry.get(level).byCode(collector.code());
        if (station == null) {
            return;
        }
        DronePadBlockEntity pad = padAt(level, station);
        if (pad == null) {
            return;
        }
        String error = pad.dispatchCollection(exchange, station);
        if (error == null) {
            collector.markCollectorSent();
            exchange.begin();
            ExchangeRegistry.get(level).touch();
        }
    }

    @Nullable
    private static DronePadBlockEntity padAt(ServerLevel level, StationRecord station) {
        if (!level.isLoaded(station.pos())) {
            return null;
        }
        return level.getBlockEntity(station.pos()) instanceof DronePadBlockEntity pad ? pad : null;
    }

    /**
     * A drone let go of its cargo. If it was doing so for an exchange, record it.
     */
    public static void onCargoDropped(ServerLevel level, DroneEntity drone, CrateEntity crate) {
        Exchange exchange = exchangeOf(level, drone);
        if (exchange == null) {
            return;
        }
        Exchange.Party party = exchange.party(drone.getStationCode());
        if (party == null) {
            return;
        }
        party.markDropped(crate.getUUID());
        exchange.begin();
        ExchangeRegistry.get(level).touch();
    }

    /**
     * A drone picked cargo up. If it was the far side's, the exchange is one half done.
     */
    public static void onCargoCollected(ServerLevel level, DroneEntity drone, CrateEntity crate) {
        Exchange exchange = exchangeOf(level, drone);
        if (exchange == null) {
            return;
        }
        Exchange.Party party = exchange.party(drone.getStationCode());
        if (party == null) {
            return;
        }
        party.markCollected();
        ExchangeRegistry.get(level).touch();
    }

    @Nullable
    private static Exchange exchangeOf(ServerLevel level, DroneEntity drone) {
        UUID id = drone.getExchangeId();
        return id == null ? null : ExchangeRegistry.get(level).byId(id);
    }

    /** Call an exchange off. */
    public static void cancel(MinecraftServer server, Exchange exchange, String reason) {
        exchange.cancel(reason);
        ExchangeRegistry.get(server).touch();
        for (ServerLevel level : server.getAllLevels()) {
            for (DroneEntity drone : List.copyOf(DroneTracker.drones(level))) {
                if (exchange.id().equals(drone.getExchangeId())) {
                    drone.abortExchange();
                }
            }
        }
        LOGGER.debug("[wflib] exchange {} cancelled: {}", exchange.id(), reason);
    }

    /** The outcome of trying to arrange an exchange. */
    public record Result(@Nullable Exchange exchange, @Nullable String error) {

        public static Result arranged(Exchange exchange) {
            return new Result(exchange, null);
        }

        public static Result refused(String reason) {
            return new Result(null, reason);
        }

        public boolean ok() {
            return error == null;
        }
    }
}
