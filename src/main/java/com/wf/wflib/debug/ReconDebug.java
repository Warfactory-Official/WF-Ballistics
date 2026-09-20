package com.wf.wflib.debug;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.ModEntities;
import com.wf.wflib.block.ModBlocks;
import com.wf.wflib.block.ProbeKind;
import com.wf.wflib.block.entity.RadarSurveillanceBlockEntity;
import com.wf.wflib.block.entity.ReconHubBlockEntity;
import com.wf.wflib.block.entity.ReconProbeBlockEntity;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.drone.ai.DroneAiScheduler;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.example.ExampleCountermeasureProvider;
import com.wf.wflib.recon.example.AnechoicCoating;
import com.wf.wflib.recon.example.RadarAbsorbentCoating;
import com.wf.wflib.recon.example.decoy.DecoyRegistry;
import com.wf.wflib.recon.fuse.CrossFix;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.EmconState;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconNetwork;
import com.wf.wflib.recon.ReconTargets;
import com.wf.wflib.recon.SensorHandle;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.Signature;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.detect.DetectionPass;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.env.Atmosphere;
import com.wf.wflib.recon.env.ReconWeather;
import com.wf.wflib.recon.env.WeatherStation;
import com.wf.wflib.recon.event.SeismicFix;
import com.wf.wflib.recon.event.SeismicLog;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.HubIndex;
import com.wf.wflib.recon.grid.ReconGrid;
import com.wf.wflib.recon.propagate.RadarPropagator;
import com.wf.wflib.recon.propagate.SeismicPropagator;
import com.wf.wflib.recon.propagate.SonarPropagator;
import com.wf.wflib.recon.source.SensorTargetSource;
import com.wf.wflib.recon.propagate.ThermalPropagator;
import com.wf.wflib.recon.snapshot.ReconTerrain;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import com.wf.wflib.recon.track.Track;
import com.wf.wflib.recon.track.TrackPicture;
import com.wf.wflib.recon.track.TrackQuality;
import com.wf.wflib.recon.track.Tracker;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** What the sensor net is doing right now, and whether the parts of it that are pure functions still work. */
public final class ReconDebug {

    /** Targets printed per look before the report becomes noise. */
    private static final int EXPLAIN_LIMIT = 12;
    /** Targets printed per source by {@link #collect}, same reason. */
    private static final int COLLECT_LIMIT = 6;
    /**
     * Blocks {@link #bind} sweeps when given no radius: the cluster you are standing in and not the one beside it,
     * which is the arrangement the command exists for: two sets a short walk apart, on two networks.
     */
    public static final double BIND_RADIUS = 8.0;
    /** Largest sweep {@link #bind} will do. Past this it is not a test cell, it is somebody's base. */
    public static final double BIND_MAX_RADIUS = 128.0;

    private ReconDebug() {
    }

    public static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        List<ReconNetwork> nets = ReconNet.networks(level);
        StringBuilder out = new StringBuilder();
        out.append("recon: ").append(nets.size()).append(" net(s), ")
                .append(ReconTargets.sourceCount()).append(" target source(s), pool ")
                .append(DroneAiScheduler.poolSize()).append(" worker(s), bands ")
                .append(implementedBands());
        for (ReconNetwork net : nets) {
            out.append(String.format(Locale.ROOT,
                    "\n  net %s: %d sensor(s), %d swept last pass, %d target(s) collected, %d track(s)"
                            + "\n    collected: %s",
                    Long.toHexString(net.netId()), net.sensorCount(), net.lastSensorCount(),
                    net.lastTargetCount(), net.trackCount(), net.lastKindSummary()));
        }
        if (nets.isEmpty()) {
            out.append("\n  no sensors registered in this dimension");
        }
        source.sendSuccess(() -> Component.literal(out.toString()), false);
        return nets.size();
    }

    /** The topology of every network in this dimension: who is online, through whom, and at what cost. */
    public static int grid(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        List<ReconNetwork> nets = ReconNet.networks(level);
        if (nets.isEmpty()) {
            source.sendFailure(Component.literal("No sensor networks in this dimension."));
            return 0;
        }
        int online = 0;
        for (ReconNetwork net : nets) {
            ReconGrid grid = net.grid();
            if (grid.nodeCount() == 0) {
                continue;
            }
            online += grid.onlineCount();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "net %s: %d/%d node(s) online, %d hub(s), %d sensor(s), %d orphaned last pass",
                            Long.toHexString(net.netId()), grid.onlineCount(), grid.nodeCount(),
                            grid.rootCount(), net.sensorCount(), net.lastOrphanCount()))
                    .withStyle(ChatFormatting.GOLD), false);
            if (grid.rootCount() == 0) {
                source.sendSuccess(() -> Component.literal(
                                "  no hub on this net - every probe on it is orphaned by definition")
                        .withStyle(ChatFormatting.RED), false);
            }
            List<GridNode> nodes = new ArrayList<>(grid.nodes());
            nodes.sort(Comparator.comparingInt((GridNode n) -> n.online() ? n.hops() : Integer.MAX_VALUE)
                    .thenComparing(n -> n.pos().asLong()));
            for (GridNode node : nodes) {
                source.sendSuccess(() -> Component.literal(describe(node, grid)), false);
            }
        }
        if (online == 0) {
            source.sendSuccess(() -> Component.literal(
                            "  nothing online: check power, then link range, then clearance")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return online;
    }

    /** What the nearest hub has heard go off. */
    public static int events(CommandSourceStack source, int limit) {
        ReconHubBlockEntity hub = nearestHub(source);
        if (hub == null) {
            source.sendFailure(Component.literal("No hub within 64 blocks."));
            return 0;
        }
        SeismicLog log = hub.events();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "net %s blast log: %d/%d event(s)",
                        Long.toHexString(hub.netId()), log.size(), log.capacity()))
                .withStyle(ChatFormatting.GOLD), false);
        if (log.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  a hub is deaf on its own - this fills only while "
                            + "a geophone or a hydrophone on its net is online and routed")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }

        BlockPos at = hub.getBlockPos();
        double hx = at.getX() + 0.5;
        double hy = at.getY() + 0.5;
        double hz = at.getZ() + 0.5;
        long now = source.getLevel().getGameTime();
        List<SeismicFix> recent = log.recent(limit);
        for (SeismicFix fix : recent) {
            source.sendSuccess(() -> Component.literal(describe(fix, hx, hy, hz, now))
                    .withStyle(fix.stations() >= 3 ? ChatFormatting.YELLOW
                            : fix.stations() == 2 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY), false);
        }
        return recent.size();
    }

    private static String describe(SeismicFix fix, double hx, double hy, double hz, long now) {
        double range = fix.rangeFrom(hx, hy, hz);
        String bearing = fix.hasBearing(hx, hy, hz)
                ? String.format(Locale.ROOT, "brg %03.0f+/-%-3.0f", fix.bearingDegFrom(hx, hz),
                        fix.bearingErrorDegFrom(hx, hy, hz))
                : "brg none      ";
        return String.format(Locale.ROOT,
                "  %5ds ago  %5.0fm+/-%-4.0f %s  power %.0f (%.0f-%.0f)  %d stn [%s]%s",
                Math.max(0L, now - fix.gameTime()) / 20L, range, fix.positionError(), bearing,
                fix.power(), fix.powerLow(), fix.powerHigh(), fix.stations(), fix.band().tag(),
                fix.subsurface() ? "  underground" : "");
    }

    private static String describe(GridNode node, ReconGrid grid) {
        if (!node.online()) {
            return String.format(Locale.ROOT, "  %-7s %-18s ORPHANED", node.label(), node.pos().toShortString());
        }
        GridNode up = node.uplink() == 0L ? null : nodeById(grid, node.uplink());
        return String.format(Locale.ROOT, "  %-7s %-18s %d hop(s)  via %-18s carrying %d",
                node.label(), node.pos().toShortString(), node.hops(),
                up == null ? "-" : up.pos().toShortString(), node.downstream());
    }

    @Nullable
    private static GridNode nodeById(ReconGrid grid, long id) {
        for (GridNode node : grid.nodes()) {
            if (node.id() == id) {
                return node;
            }
        }
        return null;
    }

    /**
     * Stand up a working relay chain, because building one by hand is fiddly and getting the spacing wrong looks
     * exactly like the mechanic being broken.
     */
    public static int gridDemo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        int ox = Mth.floor(here.x);
        int oz = Mth.floor(here.z);

        place(level, ox, oz, ModBlocks.RECON_HUB.get());
        int[] out = {100, 200, 300, 390, 480};
        ProbeKind[] kinds = {ProbeKind.RADAR, ProbeKind.RADAR, ProbeKind.RADAR,
                ProbeKind.SEISMIC, ProbeKind.THERMAL};
        for (int i = 0; i < out.length; i++) {
            int px = ox + out[i];
            place(level, px, oz, ModBlocks.PROBES.get(kinds[i]).get());
            place(level, px + 1, oz, ModBlocks.GRID_POWER_CELL.get());
        }

        source.sendSuccess(() -> Component.literal("recon grid demo").withStyle(ChatFormatting.GOLD), false);
        for (String line : new String[]{
                "  hub at " + ox + "," + oz + ", then 5 probes east at +100 +200 +300 +390 +480",
                "  radar, radar, radar, seismic, thermal - hops 1 to 5, each through the one before it",
                "  every probe has a power cell beside it; they calibrate over 15s to 60s",
                "  read it with: /wflib recon grid          (topology, and what each node carries)",
                "  then break the probe at +200 and read it again  (3 nodes orphan at once)",
                "  note: links need clearance. Over broken ground some of these will not connect,",
                "        which is the mechanic rather than a fault - raise the ground or move them."}) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return out.length;
    }

    /**
     * Stand up the smallest arrangement that shows what a met station is worth: a hub, a thermal probe, and a
     * weather probe close enough to cover it.
     */
    public static int weatherDemo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        int ox = Mth.floor(here.x);
        int oz = Mth.floor(here.z);

        place(level, ox, oz, ModBlocks.RECON_HUB.get());
        place(level, ox + 40, oz, ModBlocks.PROBES.get(ProbeKind.WEATHER).get());
        place(level, ox + 41, oz, ModBlocks.GRID_POWER_CELL.get());
        place(level, ox + 80, oz, ModBlocks.PROBES.get(ProbeKind.THERMAL).get());
        place(level, ox + 81, oz, ModBlocks.GRID_POWER_CELL.get());

        source.sendSuccess(() -> Component.literal("recon weather demo").withStyle(ChatFormatting.GOLD), false);
        for (String line : new String[]{
                "  hub at " + ox + "," + oz + ", met probe at +40, thermal probe at +80",
                "  the met probe warms up in 10s, the thermal one in 15s",
                "  read it with: /wflib recon weather      (both columns, and who is covered)",
                "                right-click the met probe        (ambient, forecast, band factors)",
                "  then try:  /weather thunder    - thermal x0.31, seismic x0.45, radar x0.70",
                "             /time set midnight  - thermal gains back what the cold ground gives it",
                "  break the met probe and read it again: every factor above 1.00 goes straight to 1.00,",
                "  because a bonus you are not measuring is a bonus you cannot use."}) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /**
     * Put a block on the surface, clearing the column above it so a probe is not buried by the terrain it was
     * dropped into.
     */
    private static void place(ServerLevel level, int x, int z, net.minecraft.world.level.block.Block block) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    /** Give the nearest hub a brand-new identity. */
    public static int remint(CommandSourceStack source) {
        ReconHubBlockEntity hub = nearestHub(source);
        if (hub == null) {
            source.sendFailure(Component.literal("No hub within 64 blocks."));
            return 0;
        }
        UUID minted = hub.remint();
        source.sendSuccess(() -> Component.literal("Hub re-minted: " + minted
                + " (net " + Long.toHexString(hub.netId()) + ")").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /** Point the nearest hub at an explicit identity. */
    public static int adopt(CommandSourceStack source, String raw) {
        ReconHubBlockEntity hub = nearestHub(source);
        if (hub == null) {
            source.sendFailure(Component.literal("No hub within 64 blocks."));
            return 0;
        }
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Not a UUID: " + raw));
            return 0;
        }
        hub.adopt(id);
        source.sendSuccess(() -> Component.literal("Hub adopted " + id
                + " (net " + Long.toHexString(hub.netId()) + ")").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /**
     * Put every recon block around the caller onto a named network.
     *
     * @param who a UUID, {@code new}, or {@code auto}.
     * @param radius blocks to sweep. Everything bindable inside it is bound, hub included.
     */
    public static int bind(CommandSourceStack source, String who, double radius) {
        UUID id;
        if (who.equalsIgnoreCase("auto")) {
            id = null;
        } else if (who.equalsIgnoreCase("new")) {
            id = UUID.randomUUID();
        } else {
            try {
                id = UUID.fromString(who);
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal("Not a UUID, and not 'auto' or 'new': " + who));
                return 0;
            }
        }

        ServerLevel level = source.getLevel();
        List<BoundBlock> found = boundBlocks(level, BlockPos.containing(source.getPosition()), radius);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "No recon blocks within %.0f blocks.", radius)));
            return 0;
        }
        for (BoundBlock block : found) {
            block.net().bindNet(id);
        }

        String headline = id == null
                ? String.format(Locale.ROOT, "%d block(s) back on automatic", found.size())
                : String.format(Locale.ROOT, "%d block(s) bound to %s (net %s)",
                        found.size(), id, Long.toHexString(SourceIds.of(id)));
        source.sendSuccess(() -> Component.literal("recon bind: " + headline)
                .withStyle(ChatFormatting.GOLD), true);
        for (BoundBlock block : found) {
            source.sendSuccess(() -> Component.literal(describeBinding(block)), false);
        }
        warnIfHubless(source, found);
        return found.size();
    }

    /** What every recon block around the caller is on, and whether it chose that for itself. */
    public static int bindings(CommandSourceStack source, double radius) {
        List<BoundBlock> found = boundBlocks(source.getLevel(),
                BlockPos.containing(source.getPosition()), radius);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "No recon blocks within %.0f blocks.", radius)));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "recon bind: %d block(s) within %.0f blocks", found.size(), radius))
                .withStyle(ChatFormatting.GOLD), false);
        for (BoundBlock block : found) {
            source.sendSuccess(() -> Component.literal(describeBinding(block)), false);
        }
        return found.size();
    }

    private static String describeBinding(BoundBlock block) {
        UUID id = block.net().boundNet();
        UUID identity = block.net().identity();
        return String.format(Locale.ROOT, "  %-22s %-18s net %-17s %-5s %s", block.name(),
                block.pos().toShortString(), Long.toHexString(block.net().netId()),
                id == null ? "auto" : "bound", identity == null ? "" : identity);
    }

    /** Say so when probes have been bound to a net whose hub is not in the same set. */
    private static void warnIfHubless(CommandSourceStack source, List<BoundBlock> found) {
        boolean probes = false;
        for (BoundBlock block : found) {
            if (block.net() instanceof ReconHubBlockEntity) {
                return;
            }
            probes |= block.net() instanceof ReconProbeBlockEntity;
        }
        if (probes) {
            source.sendSuccess(() -> Component.literal(
                            "  no hub in this set - a probe only reaches a net through a hub in link range, "
                                    + "so these read ORPHANED until one on the same net is in reach")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /** {@code auto}, {@code new}, and every identity already in play in this dimension. */
    public static CompletableFuture<Suggestions> suggestNets(CommandContext<CommandSourceStack> ctx,
                                                             SuggestionsBuilder builder) {
        Set<String> options = new LinkedHashSet<>();
        options.add("auto");
        options.add("new");
        ServerLevel level = ctx.getSource().getLevel();
        for (BlockPos pos : HubIndex.hubs(level)) {
            if (level.getBlockEntity(pos) instanceof ReconHubBlockEntity hub && hub.gridId() != null) {
                options.add(hub.gridId().toString());
            }
        }
        return SharedSuggestionProvider.suggest(options, builder);
    }

    /** Every bindable recon block within {@code radius}, nearest first. */
    private static List<BoundBlock> boundBlocks(ServerLevel level, BlockPos origin, double radius) {
        List<BoundBlock> out = new ArrayList<>();
        double radiusSq = radius * radius;
        int reach = Mth.ceil(radius);
        int minX = SectionPos.blockToSectionCoord(origin.getX() - reach);
        int maxX = SectionPos.blockToSectionCoord(origin.getX() + reach);
        int minZ = SectionPos.blockToSectionCoord(origin.getZ() - reach);
        int maxZ = SectionPos.blockToSectionCoord(origin.getZ() + reach);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (entry.getValue() instanceof ReconBound bound
                            && entry.getKey().distSqr(origin) <= radiusSq) {
                        out.add(new BoundBlock(entry.getKey().immutable(),
                                blockName(entry.getValue()), bound));
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(block -> block.pos().distSqr(origin)));
        return out;
    }

    /**
     * @return the block's registry path, not its display name. This runs on a server, where a translation key
     *      is as likely to come back as a word, and the id is the more useful answer in a diagnostic anyway.
     */
    private static String blockName(BlockEntity be) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock());
        return id.getPath();
    }

    /** A bindable block and the two things a readout needs about it, so no call site has to cast. */
    private record BoundBlock(BlockPos pos, String name, ReconBound net) {
    }

    @Nullable
    private static ReconHubBlockEntity nearestHub(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        ReconHubBlockEntity best = null;
        double bestSq = 64.0 * 64.0;
        for (BlockPos pos : HubIndex.hubs(level)) {
            double distSq = pos.distSqr(origin);
            if (distSq <= bestSq && level.getBlockEntity(pos) instanceof ReconHubBlockEntity hub) {
                bestSq = distSq;
                best = hub;
            }
        }
        return best;
    }

    /** What the weather is doing to every band, here, right now. */
    public static int weather(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        BlockPos at = BlockPos.containing(here);
        Atmosphere raw = ReconWeather.sample(level);
        Biome.ClimateSettings climate = level.getBiome(at).value().getModifiedClimateSettings();
        double ambient = raw.ambientC(climate.temperature(), climate.downfall());

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "recon weather: %s, sun %+.2f (%s)", ReconWeather.forecast(level, raw),
                        raw.solar(), ReconWeather.daypart(level, raw)))
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  here: ambient %.1f C  (biome base %.2f, humidity %.2f)  rain %.2f thunder %.2f",
                ambient, climate.temperature(), climate.downfall(), raw.rain(), raw.thunder())), false);
        source.sendSuccess(() -> Component.literal(
                "  band factors are multipliers on effective detection range; 1.00 is the plains reference")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal(
                bandFactors("  uncorrected", raw, ambient, climate.downfall()))
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal(
                bandFactors("  corrected  ", raw.withCoverage(1.0), ambient, climate.downfall()))
                .withStyle(ChatFormatting.GREEN), false);

        List<ReconNetwork> nets = ReconNet.networks(level);
        int stations = 0;
        for (ReconNetwork net : nets) {
            stations += net.stationCount();
            if (net.stationCount() == 0 && net.sensorCount() == 0) {
                continue;
            }
            int covered = 0;
            for (SensorHandle handle : net.sensorHandles()) {
                BlockPos p = handle.pos();
                if (net.coverageAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) > 0.0) {
                    covered++;
                }
            }
            int seen = covered;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  net %s: %d station(s), %d of %d sensor(s) corrected",
                    Long.toHexString(net.netId()), net.stationCount(), seen, net.sensorCount())), false);
            for (WeatherStation station : net.stationHandles()) {
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "    station %-18s %.0f blk sample", station.pos().toShortString(),
                        station.range())), false);
            }
        }
        if (stations == 0) {
            source.sendSuccess(() -> Component.literal(
                            "  no met stations in this dimension - every sensor is taking the penalties "
                                    + "and none of the bonuses")
                    .withStyle(ChatFormatting.RED), false);
        }
        return stations;
    }

    /**
     * @return one line of the three band multipliers, computed by calling the same statics the propagators
     *      call. A readout that could disagree with the physics would be worse than no readout.
     */
    private static String bandFactors(String label, Atmosphere atmos, double ambientC, double humidity) {
        return String.format(Locale.ROOT, "%s: radar x%.2f  thermal x%.2f  seismic x%.2f", label,
                atmos.correct(RadarPropagator.environment(atmos, humidity)),
                atmos.correct(ThermalPropagator.environment(atmos, ambientC)),
                atmos.correct(SeismicPropagator.environment(atmos)));
    }

    /**
     * Every track on the nearest network, as a defender would read it off a scope.
     */
    public static int tracks(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        List<ReconNetwork> nets = ReconNet.networks(level);
        if (nets.isEmpty()) {
            source.sendFailure(Component.literal("No sensor networks in this dimension."));
            return 0;
        }
        int shown = 0;
        for (ReconNetwork net : nets) {
            TrackPicture picture = net.picture();
            source.sendSuccess(() -> Component.literal("net " + Long.toHexString(net.netId())
                    + " - " + picture.tracks().size() + " track(s), " + picture.alerts().size() + " alert(s)")
                    .withStyle(ChatFormatting.GOLD), false);
            for (Track track : picture.tracks()) {
                double range = Math.sqrt(track.distanceSqTo(here.x, here.y, here.z));
                double closing = track.closingOn(here.x, here.y, here.z);
                String warning = closing > 1.0E-3
                        ? String.format(Locale.ROOT, "%.0fs", range / closing / 20.0) : "-";
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "  %s %s %s conf %.2f  %.0f,%.0f,%.0f  +/-%.1f  n=%d  bands %s hops %d  "
                                + "%.0f blocks, closing %.2f b/t, %s",
                        track.id(), track.guess(), track.quality(), track.confidence(),
                        track.x(), track.y(), track.z(), track.errorRadius(), track.countEstimate(),
                        bandTag(track.bandMask()), track.hops(),
                        range, closing, warning)), false);
                shown++;
            }
        }
        return shown;
    }

    /** What each target source has around here, whatever any sensor can make of it. */
    public static int collect(CommandSourceStack source, double radius) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        AABB volume = new AABB(here.x - radius, here.y - radius, here.z - radius,
                here.x + radius, here.y + radius, here.z + radius);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "collect: %.0f-block box at %.0f,%.0f,%.0f", radius, here.x, here.y, here.z))
                .withStyle(ChatFormatting.GOLD), false);

        int total = 0;
        for (ReconTargets.SourceCensus census : ReconTargets.census(level, volume)) {
            List<TargetSnapshot> found = census.found();
            total += found.size();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  %s: %d%s", census.source(), found.size(),
                    found.isEmpty() ? "" : " -> " + kindTally(found))), false);
            for (int i = 0; i < Math.min(found.size(), COLLECT_LIMIT); i++) {
                TargetSnapshot t = found.get(i);
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "    %s at %.0f,%.0f,%.0f  %.2f b/t  rcs %.5f  iff %d%s%s",
                        t.kind(), t.x(), t.y(), t.z(), t.speed(), t.signature().radarRcs(), t.iffCode(),
                        t.buried() ? "  buried" : "",
                        t.emcon().radioSilent() ? "  silent" : "")), false);
            }
        }
        int deduped = ReconTargets.collect(level, volume).size();
        int raw = total;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %d target(s) from %d source(s), %d after dedup",
                raw, ReconTargets.sourceCount(), deduped)), false);
        return deduped;
    }

    /**
     * @return the per-class tally of one source's answer, e.g. {@code MISSILE=1 DRONE=3}.
     */
    private static String kindTally(List<TargetSnapshot> found) {
        int[] counts = new int[ContactClass.values().length];
        for (int i = 0; i < found.size(); i++) {
            counts[found.get(i).kind().ordinal()]++;
        }
        StringBuilder out = new StringBuilder();
        ContactClass[] kinds = ContactClass.values();
        for (int i = 0; i < kinds.length; i++) {
            if (counts[i] > 0) {
                out.append(out.isEmpty() ? "" : " ").append(kinds[i]).append('=').append(counts[i]);
            }
        }
        return out.toString();
    }

    /** Stand up a working demonstration of everything in {@code recon.example} at the caller's position. */
    public static int demo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        BlockPos mast = BlockPos.containing(here.x, here.y, here.z);
        level.setBlockAndUpdate(mast, ModBlocks.RADAR_SURVEILLANCE.get().defaultBlockState());

        double y = here.y + 30.0;
        double out = 260.0;
        Vec3 target = new Vec3(here.x - 640.0, y, here.z);
        spawnDemoMissile(level, here.x + out, y, here.z - 24.0, target, "plain", null);
        spawnDemoMissile(level, here.x + out, y, here.z, target, "coated",
                ExampleCountermeasureProvider.TAG_COATING);
        spawnDemoMissile(level, here.x + out, y, here.z + 24.0, target, "faceted",
                ExampleCountermeasureProvider.TAG_FACETED);

        DecoyRegistry decoys = DecoyRegistry.get(level);
        long gameTime = level.getGameTime();
        for (int i = 0; i < 12; i++) {
            double angle = i * (Math.PI * 2.0 / 12.0);
            decoys.release(here.x + out * 0.6 + Math.cos(angle) * 3.0, y, here.z + 48.0 + Math.sin(angle) * 3.0,
                    -0.08, 0.0, 0.0, 0.05f, gameTime, 600);
        }

        source.sendSuccess(() -> Component.literal("recon demo").withStyle(ChatFormatting.GOLD), false);
        for (String line : new String[]{
                "  radar at " + mast.toShortString() + ": " + (int) RadarSurveillanceBlockEntity.RANGE
                        + " block range on a " + (int) RadarSurveillanceBlockEntity.MAST + " block mast",
                "  3 missiles inbound at " + (int) out + " blocks - plain, RAM-coated, faceted",
                "  12 decoys drifting in, each with a corner reflector",
                "  read it with: /wflib recon explain    (per-target cross-section and verdict)",
                "                /wflib recon tracks     (what the net believes)",
                "                /wflib recon collect    (which source produced what)"}) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /**
     * One demo missile, started in cruise so its geometry is known from the first tick and flown slowly enough to
     * watch.
     */
    private static void spawnDemoMissile(ServerLevel level, double x, double y, double z, Vec3 target,
                                         String name, @Nullable String tag) {
        MissileEntity missile = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .target(target)
                .cruiseSpeed(0.3)
                .highAltitude(y)
                .startInCruise()
                .build();
        missile.moveTo(x, y, z, -90.0f, 0.0f);
        missile.setCustomName(Component.literal(name));
        if (tag != null) {
            missile.addTag(tag);
        }
        level.addFreshEntity(missile);
    }

    /** Why the nearest sensor is or is not seeing what is around it. */
    public static int explain(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        SensorHandle nearest = null;
        ReconNetwork owner = null;
        double bestSq = Double.MAX_VALUE;
        for (ReconNetwork net : ReconNet.networks(level)) {
            for (SensorHandle handle : net.sensorHandles()) {
                double distSq = handle.pos().distToCenterSqr(here.x, here.y, here.z);
                if (distSq < bestSq) {
                    bestSq = distSq;
                    nearest = handle;
                    owner = net;
                }
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No sensors registered in this dimension."));
            return 0;
        }

        BlockPos at = nearest.pos();
        Atmosphere atmos = ReconWeather.sample(level).withCoverage(
                owner.coverageAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5));
        SensorSnapshot sensor = nearest.snapshot(level.getGameTime(),
                Math.max(0, owner.grid().hopsForSensor(nearest.pos())), atmos);
        AABB volume = new AABB(nearest.pos()).inflate(nearest.spec().baseRange());
        List<TargetSnapshot> targets = ReconTargets.collect(level, volume);
        SensorHandle sensorHandle = nearest;
        ReconNetwork net = owner;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "sensor at %s on net %s: %s %s, range %.0f, beam %.3f rad, sweep %d ticks, terrain %d cells",
                sensorHandle.pos().toShortString(), Long.toHexString(net.netId()),
                sensorHandle.spec().band(), sensorHandle.spec().role(), sensorHandle.spec().baseRange(),
                sensorHandle.spec().beamWidth(), sensorHandle.spec().sweepTicks(),
                sensorHandle.terrain().width() * sensorHandle.terrain().depth()))
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  conditions: %s, %s, coverage %.2f%s",
                atmos.conditions(), ReconWeather.daypart(level, atmos), atmos.coverage(),
                atmos.measured() ? "" : " (uncorrected - no met probe on this net)")), false);

        targets.sort(Comparator.comparingDouble(t -> t.distanceSqTo(sensor.x(), sensor.eyeY(), sensor.z())));
        int detected = 0;
        for (int i = 0; i < targets.size(); i++) {
            Explained line = explainOne(sensor, targets.get(i), sensorHandle.terrain());
            if (line.detected()) {
                detected++;
            }
            if (i < EXPLAIN_LIMIT) {
                source.sendSuccess(() -> Component.literal(line.text()), false);
            }
        }
        int shown = Math.min(EXPLAIN_LIMIT, targets.size());
        int total = targets.size();
        int seen = detected;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %d of %d target(s) detected this look (%d shown)", seen, total, shown)), false);
        return detected;
    }

    /** One target through the model of the band the sensor actually works in. */
    private static Explained explainOne(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain) {
        return switch (sensor.spec().band()) {
            case RADAR -> {
                RadarPropagator.Look look = RadarPropagator.INSTANCE.look(sensor, target, terrain);
                yield new Explained(look.detected(), String.format(Locale.ROOT,
                        "  %s rcs %.3f at %.0f/%.0f blocks, radial %.3f b/t, snr %.2f -> %s",
                        target.kind(), look.rcs(), look.range(), look.detectionRange(),
                        look.radial(), look.snr(), look.reason()));
            }
            case THERMAL -> {
                ThermalPropagator.Look look = ThermalPropagator.INSTANCE.look(sensor, target, terrain);
                yield new Explained(look.detected(), String.format(Locale.ROOT,
                        "  %s therm %.3f at %.0f/%.0f blocks, ambient %.0fC, weather x%.2f, snr %.2f -> %s",
                        target.kind(), look.thermal(), look.range(), look.detectionRange(),
                        look.ambientC(), look.environment(), look.snr(), look.reason()));
            }
            case SEISMIC -> {
                SeismicPropagator.Look look = SeismicPropagator.INSTANCE.look(sensor, target, terrain);
                yield new Explained(look.detected(), String.format(Locale.ROOT,
                        "  %s seis %.3f at %.0f blocks, absorption %.3f/16blk, weather x%.2f, snr %.2f -> %s",
                        target.kind(), look.energy(), look.range(), look.absorption(),
                        look.environment(), look.snr(), look.reason()));
            }
            case SONAR -> {
                SonarPropagator.Look look = SonarPropagator.INSTANCE.look(sensor, target, terrain);
                yield new Explained(look.detected(), String.format(Locale.ROOT,
                        "  %s %s %.3f at %.0f/%.0f blocks, %.0f blk of water, layer x%.2f, snr %.2f -> %s",
                        target.kind(), look.active() ? "echo" : "noise", look.strength(), look.range(),
                        look.detectionRange(), look.meanDepth(), look.layer(), look.snr(), look.reason()));
            }
            default -> new Explained(false, String.format(Locale.ROOT,
                    "  %s at %.0f blocks -> no propagation model for %s",
                    target.kind(), Math.sqrt(target.distanceSqTo(sensor.x(), sensor.eyeY(), sensor.z())),
                    sensor.spec().band()));
        };
    }

    private record Explained(boolean detected, String text) {
    }

    /**
     * The sonar band's own arms. Every one of these is a claim the band would still look plausible without:
     * a passive set that quietly reported a range, a ping that saw nothing more than listening did, or a
     * shoal that let a path through would all produce contacts and none of them would be the band described.
     */
    private static void checkSonar(List<String> failures) {
        SensorSnapshot ear = sensor(SensorSpec.sonarArray(0L, 384.0));
        SensorSnapshot pinger = sensor(SensorSpec.sonarArray(0L, 384.0).withRadiating(true));
        TargetSnapshot loud = wet(70L, new Signature(1.0f, 0.0f, 0.0f, 1.0f, 0.0f));
        TargetSnapshot quiet = wet(71L, new Signature(4.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        TargetSnapshot dry = new TargetSnapshot(72L, ContactClass.VEHICLE, 100.0, 80.0, 0.0,
                0.0, 0.0, 0.0, new Signature(1.0f, 0.0f, 0.0f, 1.0f, 0.0f),
                TargetSnapshot.NO_COUNTERMEASURES, EmconState.ACTIVE, 0L, false, false);

        SonarPropagator.Look listened = SonarPropagator.INSTANCE.look(ear, loud, ReconTerrain.EMPTY);
        if (!listened.detected()) {
            failures.add("a noisy hull 100 blocks from a 384-block hydrophone was not heard: "
                    + listened.reason());
        }
        List<Plot> heard = offThread(() -> DetectionPass.run(ear, List.of(loud), ReconTerrain.EMPTY,
                SonarPropagator.INSTANCE, 0L), failures, "sonar pass");
        if (heard != null && heard.size() == 1) {
            double error = heard.get(0).errorRadius();
            if (error < 70.0) {
                failures.add(String.format(Locale.ROOT,
                        "one hydrophone placed a target at 100 blocks to +/-%.0f - listening has no range",
                        error));
            }
        }

        // A ping buys range, and it is the only thing that does: the errors either side of this are the
        // whole difference between the two modes.
        SonarPropagator.Look pinged = SonarPropagator.INSTANCE.look(pinger, loud, ReconTerrain.EMPTY);
        List<Plot> ranged = offThread(() -> DetectionPass.run(pinger, List.of(loud), ReconTerrain.EMPTY,
                SonarPropagator.INSTANCE, 0L), failures, "active sonar pass");
        if (ranged != null && ranged.size() == 1
                && ranged.get(0).rangeError() > SonarPropagator.ACTIVE_RANGE_ERROR + 1.0E-6) {
            failures.add(String.format(Locale.ROOT,
                    "a ping ranged a target to +/-%.1f, expected the constant %.1f",
                    ranged.get(0).rangeError(), SonarPropagator.ACTIVE_RANGE_ERROR));
        }
        if (!pinged.active()) {
            failures.add("a radiating set did not read as active - the mode is not reaching the model");
        }

        if (SonarPropagator.INSTANCE.look(ear, quiet, ReconTerrain.EMPTY).detected()) {
            failures.add("a silent hull was heard by a passive set - noise is not what this band listens to");
        }
        if (!SonarPropagator.INSTANCE.look(pinger, quiet, ReconTerrain.EMPTY).detected()) {
            failures.add("a ping found nothing off a silent hull - the echo floor is dead, and active sonar "
                    + "is only passive with a range");
        }
        if (SonarPropagator.INSTANCE.look(ear, dry, ReconTerrain.EMPTY).detected()) {
            failures.add("a target out of the water was heard - the band has no medium");
        }

        // A coating quietens a hull and does not shrink it, which is why a ping still finds one. A fourfold
        // cut against a one-way law is exactly half the range: the unit the whole stealth economy is in.
        TargetSnapshot tiled = new TargetSnapshot(73L, ContactClass.VEHICLE, 100.0, 80.0, 0.0,
                0.0, 0.0, 0.0, new Signature(1.0f, 0.0f, 0.0f, 1.0f, 0.0f),
                new com.wf.wflib.recon.Countermeasure[]{AnechoicCoating.STANDARD},
                EmconState.ACTIVE, 0L, false, true);
        double bare = SonarPropagator.INSTANCE.look(ear, loud, ReconTerrain.EMPTY).detectionRange();
        double coated = SonarPropagator.INSTANCE.look(ear, tiled, ReconTerrain.EMPTY).detectionRange();
        double answered = SonarPropagator.INSTANCE.look(pinger, tiled, ReconTerrain.EMPTY).detectionRange();
        if (Math.abs(coated - bare * 0.5) > 1.0) {
            failures.add(String.format(Locale.ROOT,
                    "an anechoic coating took a hull from %.0f blocks to %.0f, expected half", bare, coated));
        }
        if (!(answered > coated)) {
            failures.add(String.format(Locale.ROOT,
                    "a coated hull answers a ping at %.0f and is heard at %.0f - the coating is reaching the "
                            + "echo, which reads off the raw hull", answered, coated));
        }

        // A set handed its own ping must not plot itself: it sits at range zero with every gate passed.
        TargetSnapshot itself = new TargetSnapshot(pinger.sensorId(), ContactClass.STRUCTURE,
                pinger.x(), pinger.eyeY(), pinger.z(), 0.0, 0.0, 0.0,
                new Signature(0.0f, 0.0f, 0.0f, SensorTargetSource.PING_SOURCE_LEVEL, 0.0f),
                TargetSnapshot.NO_COUNTERMEASURES, EmconState.ACTIVE, 0L, false, true);
        if (SonarPropagator.INSTANCE.look(pinger, itself, ReconTerrain.EMPTY).detected()) {
            failures.add("a pinging set detected its own ping");
        }
        SensorSnapshot other = new SensorSnapshot(2L, 0.0, 80.0, 0.0,
                SensorSpec.sonarArray(0L, 384.0), 0, Atmosphere.STANDARD, 0L);
        if (!SonarPropagator.INSTANCE.look(other, itself, ReconTerrain.EMPTY).detected()) {
            failures.add("somebody else's ping was inaudible - counter-detection is not wired up");
        }

        // A shoal across the path stops it, a seabed below it does not, and neither does the water carry a
        // path into the air above it.
        ReconTerrain open50 = channel(40, 62);
        ReconTerrain shoal = channel(55, 62);
        if (open50.waterPath(0.0, 50.0, 0.0, 100.0, 50.0, 0.0, SonarPropagator.BOTTOM_MARGIN).blocked()) {
            failures.add("a path down the middle of open water read as blocked");
        }
        if (!shoal.waterPath(0.0, 50.0, 0.0, 100.0, 50.0, 0.0, SonarPropagator.BOTTOM_MARGIN).blocked()) {
            failures.add("a path straight through a shoal read as open - the seabed is not stopping anything");
        }
        if (!open50.waterPath(0.0, 70.0, 0.0, 100.0, 70.0, 0.0, SonarPropagator.BOTTOM_MARGIN).blocked()) {
            failures.add("a path above the waterline read as open - sonar is working in air");
        }
        ReconTerrain deep = channel(20, 62);
        if (!(deep.waterPath(0.0, 50.0, 0.0, 100.0, 50.0, 0.0, SonarPropagator.BOTTOM_MARGIN).meanDepth()
                > open50.waterPath(0.0, 50.0, 0.0, 100.0, 50.0, 0.0,
                SonarPropagator.BOTTOM_MARGIN).meanDepth())) {
            failures.add("deeper water did not read as deeper - the shallow-water term has nothing to read");
        }

        checkFactor(failures, "sonar at the reference", SonarPropagator.environment(Atmosphere.STANDARD),
                1.0, 1.0E-9);
        if (!(SonarPropagator.environment(new Atmosphere(0.0, 1.0, 0.0, 0.0)) < 1.0)) {
            failures.add("rain did not raise the sea surface noise floor");
        }
    }

    /** A target standing in water, which is the only kind sonar has. */
    private static TargetSnapshot wet(long id, Signature signature) {
        return new TargetSnapshot(id, ContactClass.VEHICLE, 100.0, 80.0, 0.0, 0.0, 0.0, 0.0,
                signature, TargetSnapshot.NO_COUNTERMEASURES, EmconState.ACTIVE, 0L, false, true);
    }

    /**
     * A one-cell-wide field of uniform water: surface at {@code surface}, floor at {@code floor}. Wide enough
     * to cover the paths above, which run along the X axis from the origin.
     */
    private static ReconTerrain channel(int floor, int surface) {
        int cells = 32;
        short[] height = new short[cells * cells];
        short[] seabed = new short[cells * cells];
        java.util.Arrays.fill(height, (short) surface);
        java.util.Arrays.fill(seabed, (short) floor);
        return new ReconTerrain(-128, -128, cells, cells, height, seabed,
                new byte[cells * cells], new byte[cells * cells], floor);
    }

    /**
     * Checks on the parts that are pure functions, in the {@code DroneSelfTest} mould: run them with known inputs
     * and assert the answer, so a refactor that quietly changes the physics fails here instead of in a bug report
     * about turrets feeling wrong.
     */
    public static int selfTest(CommandSourceStack source) {
        List<String> failures = new ArrayList<>();

        checkDetectionRange(failures, 1.0f, 200.0, 200.0);
        checkDetectionRange(failures, 1.0f / 16.0f, 200.0, 100.0);
        checkDetectionRange(failures, 16.0f, 100.0, 200.0);

        // The old stealth boolean's fixed 32-block window against a battery, as a cross-section.
        double stealthRange = 200.0 * Math.pow(com.wf.wflib.sim.MissileSimConfig.STEALTH_RCS, 0.25);
        if (Math.abs(stealthRange - 32.0) > 2.0) {
            failures.add(String.format(Locale.ROOT,
                    "stealth rcs gives %.1f blocks against a 200-block set, expected ~32", stealthRange));
        }

        SensorSnapshot sensor = sensor(SensorSpec.surveillanceRadar(0L, 200.0));
        List<TargetSnapshot> cluster = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cluster.add(target(1L + i, 100.0 + i * 0.4, 80.0, i * 0.4));
        }
        List<Plot> merged = offThread(() -> DetectionPass.run(sensor, cluster, ReconTerrain.EMPTY,
                RadarPropagator.INSTANCE, 0L), failures, "merge pass");
        if (merged != null) {
            int counted = 0;
            for (Plot plot : merged) {
                counted += plot.merged();
            }
            if (merged.size() >= cluster.size()) {
                failures.add("six targets inside one resolution cell resolved as " + merged.size()
                        + " plot(s) - nothing merged");
            }
            if (counted != cluster.size()) {
                failures.add("merged plots account for " + counted + " targets, expected " + cluster.size());
            }
        }

        // The same two, far enough apart to resolve, must stay two.
        List<TargetSnapshot> apart = List.of(target(1L, 100.0, 80.0, 0.0), target(2L, 100.0, 80.0, 40.0));
        List<Plot> split = offThread(() -> DetectionPass.run(sensor, apart, ReconTerrain.EMPTY,
                RadarPropagator.INSTANCE, 0L), failures, "resolve pass");
        if (split != null && split.size() != 2) {
            failures.add("two targets 40 blocks apart resolved as " + split.size() + " plot(s), expected 2");
        }

        // A signature of zero is not a faint target, it is no target.
        List<Plot> none = offThread(() -> DetectionPass.run(sensor,
                List.of(TargetSnapshot.of(3L, ContactClass.MISSILE, 50.0, 80.0, 0.0, 0.0, 0.0, 0.0,
                        Signature.NONE, 0L, false)), ReconTerrain.EMPTY, RadarPropagator.INSTANCE, 0L),
                failures, "zero-signature pass");
        if (none != null && !none.isEmpty()) {
            failures.add("a zero-signature target produced a plot");
        }

        // Buried is a hard no at any range, which is the blind spot the seismic band exists for.
        List<Plot> buried = offThread(() -> DetectionPass.run(sensor,
                List.of(new TargetSnapshot(4L, ContactClass.GLYPHID, 20.0, 40.0, 0.0, 0.0, 0.0, 0.0,
                        Signature.radar(4.0f), TargetSnapshot.NO_COUNTERMEASURES, EmconState.ACTIVE, 0L, true)),
                ReconTerrain.EMPTY, RadarPropagator.INSTANCE, 0L), failures, "buried pass");
        if (buried != null && !buried.isEmpty()) {
            failures.add("a buried target was detected by radar");
        }

        Tracker tracker = new Tracker();
        TrackPicture picture = offThread(() -> {
            for (int tick = 0; tick <= 100; tick += 10) {
                List<Plot> plots = DetectionPass.run(sensor,
                        List.of(target(50L, 150.0 - tick * 0.5, 80.0, 0.0)),
                        ReconTerrain.EMPTY, RadarPropagator.INSTANCE, tick);
                tracker.update(plots, tick);
            }
            return tracker.publish(100L);
        }, failures, "tracker pass");
        if (picture == null) {
            picture = TrackPicture.EMPTY;
        }
        if (picture.tracks().size() != 1) {
            failures.add("a single target walking inbound produced " + picture.tracks().size() + " track(s)");
        } else {
            Track track = picture.tracks().get(0);
            if (Math.abs(track.vx() + 0.5) > 0.15) {
                failures.add(String.format(Locale.ROOT,
                        "tracker recovered vx=%.3f for a target closing at 0.5 b/t", track.vx()));
            }
            if (!track.quality().atLeast(com.wf.wflib.recon.track.TrackQuality.CONFIRMED)) {
                failures.add("ten looks at one target left the track " + track.quality());
            }
        }

        Plot along = Plot.single(Band.RADAR, 100.0, 80.0, 0.0, 1.0, 0.0, 20.0, 2.0, 8.0,
                ContactClass.MISSILE, false, 0, 0L);
        Plot across = Plot.single(Band.RADAR, 100.0, 80.0, 0.0, 0.0, 1.0, 20.0, 2.0, 8.0,
                ContactClass.MISSILE, false, 0, 0L);
        double one = fixedError(failures, List.of(along), "single-plot fix");
        double wide = fixedError(failures, List.of(along, across), "wide-baseline fix");
        double narrow = fixedError(failures, List.of(along, along), "collinear fix");
        if (one > 0 && Math.abs(one - 20.0) > 0.01) {
            failures.add(String.format(Locale.ROOT,
                    "one plot through CrossFix reports %.2f, expected its own error radius of 20", one));
        }
        if (wide > 0 && wide > 4.0) {
            failures.add(String.format(Locale.ROOT,
                    "two perpendicular looks gave %.2f blocks, expected to collapse to about the range "
                            + "error of 2", wide));
        }
        if (narrow > 0 && (narrow < 12.0 || narrow > 16.0)) {
            failures.add(String.format(Locale.ROOT,
                    "two collinear looks gave %.2f blocks, expected about 20/sqrt(2) = 14.1", narrow));
        }
        if (wide > 0 && narrow > 0 && wide >= narrow) {
            failures.add("a wide baseline did not beat a collinear one - the fix is not using bearing");
        }

        // Relay cost: error compounds per hop, and quality is capped independently of how long a track is held.
        SensorSnapshot relayed = sensor(SensorSpec.surveillanceRadar(0L, 200.0), 3);
        double expectedScale = Math.pow(SensorSnapshot.HOP_ERROR, 3);
        if (Math.abs(relayed.linkErrorScale() - expectedScale) > 1.0E-6) {
            failures.add(String.format(Locale.ROOT, "three hops scale error by %.4f, expected %.4f",
                    relayed.linkErrorScale(), expectedScale));
        }
        checkCap(failures, 0, TrackQuality.FIRM);
        checkCap(failures, 1, TrackQuality.FIRM);
        checkCap(failures, 2, TrackQuality.CONFIRMED);
        checkCap(failures, 5, TrackQuality.TENTATIVE);

        SensorSnapshot geophone = sensor(SensorSpec.seismicArray(0L, 384.0));
        TargetSnapshot digger = new TargetSnapshot(60L, ContactClass.GLYPHID, 100.0, 40.0, 0.0,
                0.05, 0.0, 0.0, new Signature(0.5f, 3.0f, 0.0f, 0.0f, 0.0f),
                TargetSnapshot.NO_COUNTERMEASURES, EmconState.ACTIVE, 0L, true);
        List<Plot> heard = offThread(() -> DetectionPass.run(geophone, List.of(digger), ReconTerrain.EMPTY,
                SeismicPropagator.INSTANCE, 0L), failures, "seismic pass");
        if (heard != null && heard.isEmpty()) {
            failures.add("a buried digger was inaudible to seismic - the band covers no blind spot");
        } else if (heard != null) {
            double error = heard.get(0).errorRadius();
            if (error < 90.0) {
                failures.add(String.format(Locale.ROOT,
                        "one geophone located a target at 100 blocks to +/-%.0f - it should have no bearing",
                        error));
            }
        }

        TargetSnapshot coated = new TargetSnapshot(61L, ContactClass.MISSILE, 120.0, 80.0, 0.0,
                -1.0, 0.0, 0.0, new Signature(1.0f, 0.0f, 2.25f, 0.0f, 0.0f),
                new com.wf.wflib.recon.Countermeasure[]{RadarAbsorbentCoating.STANDARD},
                EmconState.ACTIVE, 0L, false);
        List<Plot> byRadar = offThread(() -> DetectionPass.run(sensor, List.of(coated), ReconTerrain.EMPTY,
                RadarPropagator.INSTANCE, 0L), failures, "coated radar pass");
        List<Plot> byThermal = offThread(() -> DetectionPass.run(
                sensor(SensorSpec.thermalImager(0L, 96.0)), List.of(coated), ReconTerrain.EMPTY,
                ThermalPropagator.INSTANCE, 0L), failures, "coated thermal pass");
        if (byRadar != null && !byRadar.isEmpty()) {
            failures.add("a RAM-coated missile at 120 blocks was seen by a 200-block radar (range should "
                    + "have halved to 100)");
        }
        if (byThermal != null && byThermal.isEmpty()) {
            failures.add("a RAM-coated missile was invisible to thermal - a radar coating is affecting "
                    + "another band");
        }

        Atmosphere plains = Atmosphere.STANDARD;
        double refAmbient = plains.ambientC(ReconTerrain.DEFAULT_TEMP, ReconTerrain.DEFAULT_DOWNFALL);
        checkFactor(failures, "reference ambient", refAmbient, ThermalPropagator.REF_C, 1.0E-9);
        checkFactor(failures, "radar at the reference",
                RadarPropagator.environment(plains, ReconTerrain.DEFAULT_DOWNFALL), 1.0, 1.0E-9);
        checkFactor(failures, "thermal at the reference",
                ThermalPropagator.environment(plains, refAmbient), 1.0, 1.0E-9);
        checkFactor(failures, "seismic at the reference", SeismicPropagator.environment(plains), 1.0, 1.0E-9);

        Atmosphere blind = new Atmosphere(-1.0, 0.0, 0.0, 0.0);
        Atmosphere seeing = blind.withCoverage(1.0);
        checkFactor(failures, "an uncorrected gain is clamped away", blind.correct(1.5), 1.0, 1.0E-9);
        checkFactor(failures, "a corrected gain is taken in full", seeing.correct(1.5), 1.5, 1.0E-9);
        checkFactor(failures, "an uncorrected loss lands in full", blind.correct(0.4), 0.4, 1.0E-9);
        checkFactor(failures, "a corrected loss is partly recovered", seeing.correct(0.4),
                0.4 + 0.6 * Atmosphere.RECOVERY, 1.0E-9);
        checkFactor(failures, "half coverage recovers half as much", blind.withCoverage(0.5).correct(0.4),
                0.4 + 0.6 * Atmosphere.RECOVERY * 0.5, 1.0E-9);

        double cold = ThermalPropagator.environment(plains, -30.0);
        double hot = ThermalPropagator.environment(plains, 60.0);
        if (!(cold > 1.0 && hot < 1.0 && cold > hot)) {
            failures.add(String.format(Locale.ROOT,
                    "thermal reads x%.2f at -30C and x%.2f at 60C - contrast is not driving the band",
                    cold, hot));
        }
        Atmosphere noon = new Atmosphere(1.0, 0.0, 0.0, 0.0);
        Atmosphere midnight = new Atmosphere(-1.0, 0.0, 0.0, 0.0);
        double desertNoon = ThermalPropagator.environment(noon, noon.ambientC(2.0, 0.0));
        double desertNight = ThermalPropagator.environment(midnight, midnight.ambientC(2.0, 0.0));
        if (!(desertNight > desertNoon)) {
            failures.add(String.format(Locale.ROOT,
                    "a desert reads x%.2f at midnight and x%.2f at noon - the diurnal term is inverted or dead",
                    desertNight, desertNoon));
        }
        double swampSwing = Math.abs(noon.ambientC(0.8, 0.9) - midnight.ambientC(0.8, 0.9));
        double desertSwing = Math.abs(noon.ambientC(2.0, 0.0) - midnight.ambientC(2.0, 0.0));
        if (!(desertSwing > swampSwing * 4.0)) {
            failures.add(String.format(Locale.ROOT,
                    "desert swings %.1fC and swamp %.1fC between noon and midnight - humidity is not damping it",
                    desertSwing, swampSwing));
        }

        Atmosphere storm = new Atmosphere(0.0, 1.0, 1.0, 0.0);
        double stormRadar = storm.correct(RadarPropagator.environment(storm, ReconTerrain.DEFAULT_DOWNFALL));
        double stormThermal = storm.correct(ThermalPropagator.environment(storm,
                storm.ambientC(ReconTerrain.DEFAULT_TEMP, ReconTerrain.DEFAULT_DOWNFALL)));
        double stormSeismic = storm.correct(SeismicPropagator.environment(storm));
        if (!(stormRadar > stormSeismic && stormSeismic > stormThermal)) {
            failures.add(String.format(Locale.ROOT,
                    "in a thunderstorm radar x%.2f, seismic x%.2f, thermal x%.2f - the band that should "
                            + "survive a storm is no longer radar", stormRadar, stormSeismic, stormThermal));
        }
        if (stormRadar >= 1.0) {
            failures.add(String.format(Locale.ROOT,
                    "a thunderstorm left radar at x%.2f - weather is not reaching the band at all", stormRadar));
        }

        if (Math.abs(SeismicPropagator.environment(noon) - SeismicPropagator.environment(midnight)) > 1.0E-9) {
            failures.add("seismic responds to the time of day directly - it should only feel it through "
                    + "ground that freezes");
        }

        checkClimate(failures, -0.5, 0.0);
        checkClimate(failures, 0.8, 0.4);
        checkClimate(failures, 2.0, 1.0);
        if (ReconTerrain.packClimate(-0.5, 0.0) == ReconTerrain.CLIMATE_UNKNOWN) {
            failures.add("the coldest, driest biome packs to CLIMATE_UNKNOWN - it will read as plains");
        }
        if (ReconTerrain.EMPTY.baseTempAt(0.0, 0.0) != ReconTerrain.DEFAULT_TEMP) {
            failures.add("unsampled ground does not read as the reference climate");
        }

        SensorSnapshot warm = sensor(SensorSpec.thermalImager(0L, 96.0), 0,
                new Atmosphere(1.0, 0.0, 0.0, 1.0));
        SensorSnapshot chilled = sensor(SensorSpec.thermalImager(0L, 96.0), 0,
                new Atmosphere(-1.0, 0.0, 0.0, 1.0));
        TargetSnapshot hotBody = new TargetSnapshot(62L, ContactClass.MISSILE, 120.0, 80.0, 0.0,
                -1.0, 0.0, 0.0, new Signature(1.0f, 0.0f, 2.25f, 0.0f, 0.0f),
                TargetSnapshot.NO_COUNTERMEASURES, EmconState.ACTIVE, 0L, false);
        double warmRange = ThermalPropagator.INSTANCE.look(warm, hotBody, ReconTerrain.EMPTY).detectionRange();
        double coldRange = ThermalPropagator.INSTANCE.look(chilled, hotBody, ReconTerrain.EMPTY)
                .detectionRange();
        if (!(coldRange > warmRange)) {
            failures.add(String.format(Locale.ROOT,
                    "thermal reaches %.0f blocks at midnight and %.0f at noon - the environment term is not "
                            + "wired into the model", coldRange, warmRange));
        }

        checkSonar(failures);

        if (!WorldThread.armed()) {
            failures.add("WorldThread is not armed - the thread assertions mean nothing");
        } else {
            try {
                DetectionPass.run(sensor, List.of(), ReconTerrain.EMPTY, RadarPropagator.INSTANCE, 0L);
                failures.add("DetectionPass ran on the world thread without complaining");
            } catch (IllegalStateException expected) {
                // Correct: the pass refuses the server thread.
            }
        }

        if (failures.isEmpty()) {
            source.sendSuccess(() -> Component.literal("recon self-test: all checks passed")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        for (String failure : failures) {
            source.sendFailure(Component.literal("recon self-test: " + failure));
        }
        return 0;
    }

    /**
     * @return a compact tag naming every band holding a track, such as {@code "RS"}, or {@code "-"} for none.
     *      The one thing a fused picture would otherwise lose.
     */
    private static String bandTag(int mask) {
        StringBuilder out = new StringBuilder(Band.VALUES.length);
        for (int i = 0; i < Band.VALUES.length; i++) {
            if ((mask & (1 << i)) != 0) {
                out.append(Band.VALUES[i].tag());
            }
        }
        return out.isEmpty() ? "-" : out.toString();
    }

    /**
     * @return the error radius of the fix these plots produce, or {@code -1} if the pass could not run.
     */
    private static double fixedError(List<String> failures, List<Plot> plots, String what) {
        List<Plot> fused = offThread(() -> CrossFix.fuse(plots), failures, what);
        if (fused == null || fused.size() != 1) {
            failures.add(what + " produced " + (fused == null ? "nothing" : fused.size() + " plots")
                    + ", expected exactly one");
            return -1.0;
        }
        return fused.get(0).errorRadius();
    }

    private static void checkFactor(List<String> failures, String what, double actual, double expected,
                                    double tolerance) {
        if (Math.abs(actual - expected) > tolerance) {
            failures.add(String.format(Locale.ROOT, "%s is %.4f, expected %.4f", what, actual, expected));
        }
    }

    /**
     * A climate must survive one byte to within a bucket: 0.18 of a temperature unit, 0.07 of downfall.
     */
    private static void checkClimate(List<String> failures, double temp, double downfall) {
        byte packed = ReconTerrain.packClimate(temp, downfall);
        ReconTerrain one = new ReconTerrain(0, 0, 1, 1, new short[]{0}, new short[]{0},
                new byte[]{ReconTerrain.MAT_ROCK}, new byte[]{packed}, 0);
        double gotTemp = one.baseTempAt(0.0, 0.0);
        double gotFall = one.downfallAt(0.0, 0.0);
        if (Math.abs(gotTemp - temp) > 0.10 || Math.abs(gotFall - downfall) > 0.05) {
            failures.add(String.format(Locale.ROOT,
                    "climate (%.2f, %.2f) round-tripped to (%.2f, %.2f)", temp, downfall, gotTemp, gotFall));
        }
    }

    private static void checkCap(List<String> failures, int hops, TrackQuality expected) {
        TrackQuality actual = TrackQuality.capFor(hops);
        if (actual != expected) {
            failures.add(hops + " hop(s) caps quality at " + actual + ", expected " + expected);
        }
    }

    private static void checkDetectionRange(List<String> failures, float rcs, double baseRange, double expected) {
        double actual = baseRange * Math.pow(rcs, 0.25);
        if (Math.abs(actual - expected) > 0.5) {
            failures.add(String.format(Locale.ROOT,
                    "rcs %.4f on a %.0f-block set gives %.1f blocks, expected %.0f",
                    rcs, baseRange, actual, expected));
        }
    }

    /**
     * Run a pass on a worker and wait for it, because the pass refuses to run here, which is exactly the property
     * being tested a few lines further down.
     */
    private static <T> T offThread(java.util.concurrent.Callable<T> work, List<String> failures, String what) {
        java.util.concurrent.ExecutorService pool = DroneAiScheduler.pool();
        if (pool == null) {
            failures.add(what + " skipped: no worker pool");
            return null;
        }
        try {
            return pool.submit(work).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            failures.add(what + " threw " + e.getCause());
            return null;
        }
    }

    private static SensorSnapshot sensor(SensorSpec spec) {
        return sensor(spec, 0);
    }

    private static SensorSnapshot sensor(SensorSpec spec, int hops) {
        return sensor(spec, hops, Atmosphere.STANDARD);
    }

    /**
     * A sensor under {@link Atmosphere#STANDARD}, which over {@link ReconTerrain#EMPTY} puts every band's
     * environment factor at exactly 1.00.
     */
    private static SensorSnapshot sensor(SensorSpec spec, int hops, Atmosphere atmos) {
        return new SensorSnapshot(1L, 0.0, 80.0, 0.0, spec, hops, atmos, 0L);
    }

    /**
     * A reference target: cross-section 1.0, moving straight at the sensor so it is never in the notch.
     */
    private static TargetSnapshot target(long id, double x, double y, double z) {
        return TargetSnapshot.of(id, ContactClass.MISSILE, x, y, z, -1.0, 0.0, 0.0,
                Signature.radar(1.0f), 0L, false);
    }

    /**
     * @return the bands that currently have a model behind them, for the status line.
     */
    public static String implementedBands() {
        StringBuilder out = new StringBuilder();
        for (Band band : Band.VALUES) {
            if (band.hasPropagation()) {
                out.append(out.isEmpty() ? "" : ", ").append(band);
            }
        }
        return out.toString();
    }
}
