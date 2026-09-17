package com.wf.wfballistics.debug;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.wf.wfballistics.block.ModBlocks;
import com.wf.wfballistics.block.entity.ReconHubBlockEntity;
import com.wf.wfballistics.orbital.DeorbitReason;
import com.wf.wfballistics.orbital.OrbitElements;
import com.wf.wfballistics.orbital.OrbitalConfig;
import com.wf.wfballistics.orbital.OrbitalNet;
import com.wf.wfballistics.orbital.OrbitalSelfTest;
import com.wf.wfballistics.orbital.SatHandle;
import com.wf.wfballistics.orbital.SatId;
import com.wf.wfballistics.orbital.SatPayloads;
import com.wf.wfballistics.orbital.SatSpec;
import com.wf.wfballistics.orbital.SatVerb;
import com.wf.wfballistics.orbital.SatView;
import com.wf.wfballistics.orbital.TrackedObject;
import com.wf.wfballistics.recon.ReconNet;
import com.wf.wfballistics.recon.grid.HubIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class OrbitalDebug {

    private static final double STATION_SEARCH = 192.0;

    private OrbitalDebug() {
    }

    public static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        List<SatView> birds = OrbitalNet.all(level);
        long now = level.getGameTime();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "orbital: %d bird(s), %s, payloads %s", birds.size(),
                        level.isDay() ? "daylight - panels charging" : "night - panels dead",
                        SatPayloads.ids().size()))
                .withStyle(ChatFormatting.GOLD), false);
        if (birds.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                            "  nothing in orbit: /wfballistics orbital demo stands up a ground station and a bird")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        for (SatView bird : birds) {
            source.sendSuccess(() -> Component.literal(describe(bird, now))
                    .withStyle(bird.inContact() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY), false);
        }
        return birds.size();
    }

    private static String describe(SatView bird, long now) {
        double revs = bird.elements().revolutions(now);
        return String.format(Locale.ROOT,
                "  %-10s %-12s net %-10s %8.0f,%-8.0f alt %-6.0f fuel %3.0f%% pwr %3.0f%% %s%s pass %d %.0f%%%s%s",
                bird.callsign(), bird.payload().getPath(), Long.toHexString(bird.netId()),
                bird.x(), bird.z(), bird.y(), bird.fuelFraction() * 100.0, bird.powerFraction() * 100.0,
                bird.inContact() ? "linked" : "  dark",
                bird.parked() ? " PARKED" : "       ",
                bird.elements().passAt(now), (revs - Math.floor(revs)) * 100.0,
                bird.pointDefence() > 0 ? "  pd " + bird.pointDefence() : "",
                bird.threatTicks() >= 0
                        ? String.format(Locale.ROOT, "  INBOUND %ds", bird.threatTicks() / 20) : "");
    }

    public static int launch(CommandSourceStack source, String payloadName, String orbitClass) {
        ServerLevel level = source.getLevel();
        ResourceLocation payload = ResourceLocation.tryParse(
                payloadName.contains(":") ? payloadName : "wfballistics:" + payloadName);
        if (payload == null || !SatPayloads.contains(payload)) {
            source.sendFailure(Component.literal("No payload registered as " + payloadName
                    + ". Known: " + SatPayloads.ids()));
            return 0;
        }

        Vec3 here = source.getPosition();
        BlockPos station = nearestHub(level, BlockPos.containing(here));
        long netId = ReconNet.UNAFFILIATED;
        if (station != null && level.getBlockEntity(station) instanceof ReconHubBlockEntity hub) {
            netId = hub.netId();
        }

        long now = level.getGameTime();
        OrbitElements elements = switch (orbitClass.toLowerCase(Locale.ROOT)) {
            case "meo" -> OrbitElements.meo(now, here.x, here.z, 0.0);
            case "heo" -> OrbitElements.heo(now, here.x, here.z, 0.0);
            default -> OrbitElements.leo(now, here.x, here.z, 0.0);
        };
        String callsign = payload.getPath().toUpperCase(Locale.ROOT).replace("_RADAR", "")
                + "-" + (OrbitalNet.all(level).size() + 1);
        SatSpec spec = SatSpec.of(payload).netId(netId).owner(null).station(station).callsign(callsign);
        if (SatPayloads.RODS.equals(payload)) {
            spec = spec.magazine(12);
        }
        if (SatPayloads.RECON_RADAR.equals(payload) || SatPayloads.MINER.equals(payload)
                || SatPayloads.LASER.equals(payload)) {
            spec = spec.pointDefence(2);
        }
        SatHandle handle = OrbitalNet.orbit(level, spec, elements);

        long boundNet = netId;
        BlockPos boundStation = station;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "launched %s (%s) into %s on net %s", callsign, handle.id(),
                        orbitClass.toUpperCase(Locale.ROOT), Long.toHexString(boundNet)))
                .withStyle(ChatFormatting.GOLD), true);
        if (boundStation == null) {
            source.sendSuccess(() -> Component.literal(
                            "  no hub within " + (int) STATION_SEARCH + " blocks: this bird is on the "
                                    + "unaffiliated net and will never be in contact. Build a recon hub, or "
                                    + "run /wfballistics orbital demo.")
                    .withStyle(ChatFormatting.YELLOW), false);
        } else {
            source.sendSuccess(() -> Component.literal("  station " + boundStation.toShortString()
                    + " - whoever holds that chunk holds this bird"), false);
        }
        return 1;
    }

    public static int demo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        int ox = Mth.floor(here.x);
        int oz = Mth.floor(here.z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox, oz);
        BlockPos hubPos = new BlockPos(ox, y, oz);
        level.setBlockAndUpdate(hubPos, ModBlocks.RECON_HUB.get().defaultBlockState());

        long netId = level.getBlockEntity(hubPos) instanceof ReconHubBlockEntity hub
                ? hub.netId() : ReconNet.UNAFFILIATED;
        long now = level.getGameTime();
        OrbitalNet.orbit(level, SatSpec.of(SatPayloads.RECON_RADAR)
                        .netId(netId).station(hubPos).callsign("KH-1"),
                OrbitElements.leo(now, here.x, here.z, 0.0));
        OrbitalNet.orbit(level, SatSpec.of(SatPayloads.RELAY)
                        .netId(netId).station(hubPos).callsign("RELAY-1"),
                OrbitElements.meo(now, here.x, here.z, Math.PI / 2.0));

        source.sendSuccess(() -> Component.literal("orbital demo").withStyle(ChatFormatting.GOLD), false);
        for (String line : new String[]{
                "  recon hub at " + hubPos.toShortString() + " on net " + Long.toHexString(netId),
                "  KH-1  radar in LEO, overhead now, sweeping every "
                        + OrbitalConfig.RECON_SWEEP_TICKS + " ticks while it is in contact",
                "  RELAY-1 relay in MEO, crossing the other way",
                "  read it with: /wfballistics orbital                    (both birds, fuel, power, contact)",
                "                /wfballistics orbital cmd KH-1 status",
                "                /wfballistics recon tracks               (what the radar contributed)",
                "  KH-1 is over you for about "
                        + (int) (2.0 * OrbitalConfig.swathFor(OrbitalConfig.LEO_ALTITUDE)
                        / (OrbitElements.TRACK_LENGTH / OrbitalConfig.LEO_PERIOD))
                        + " ticks per pass, then out of contact until it comes round in "
                        + (int) OrbitalConfig.LEO_PERIOD + ".",
                "  to keep it: /wfballistics orbital cmd KH-1 park:" + ox + ":" + oz,
                "  and watch the fuel go. Parking is the whole economy in one command.",
                "  put a roof over something and sweep again: an orbital radar is defeated by any block above"}) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 2;
    }

    public static int command(CommandSourceStack source, String callsign, String script) {
        ServerLevel level = source.getLevel();
        SatView bird = OrbitalNet.byCallsign(level, callsign).orElse(null);
        if (bird == null) {
            source.sendFailure(Component.literal("No satellite answering to " + callsign));
            return 0;
        }
        String[] parts = script.split(":");
        String verb = parts[0];
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        String reply = OrbitalNet.commandBy(level, bird.id(), source.getTextName(), verb, args);
        source.sendSuccess(() -> Component.literal(callsign + "! " + script + "  ->  " + reply)
                .withStyle(reply.startsWith("err:") ? ChatFormatting.RED : ChatFormatting.GREEN), true);
        return reply.startsWith("err:") ? 0 : 1;
    }

    public static int verbs(CommandSourceStack source, String callsign) {
        ServerLevel level = source.getLevel();
        SatView bird = OrbitalNet.byCallsign(level, callsign).orElse(null);
        if (bird == null) {
            source.sendFailure(Component.literal("No satellite answering to " + callsign));
            return 0;
        }
        List<SatVerb> verbs = OrbitalNet.verbs(level, bird.id());
        source.sendSuccess(() -> Component.literal(callsign + ": " + verbs.size() + " verb(s)")
                .withStyle(ChatFormatting.GOLD), false);
        for (SatVerb verb : verbs) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "  %-10s %d arg(s) -> %-7s %s  %s", verb.name(), verb.arity(), verb.returns(),
                            verb.pure() ? "read " : "write", verb.describe()))
                    .withStyle(verb.pure() ? ChatFormatting.GRAY : ChatFormatting.WHITE), false);
        }
        return verbs.size();
    }

    public static int at(CommandSourceStack source, String callsign, long offset) {
        ServerLevel level = source.getLevel();
        SatView bird = OrbitalNet.byCallsign(level, callsign).orElse(null);
        if (bird == null) {
            source.sendFailure(Component.literal("No satellite answering to " + callsign));
            return 0;
        }
        long when = level.getGameTime() + offset;
        Vec3 at = bird.parked()
                ? new Vec3(bird.parkX(), bird.elements().altitude(), bird.parkZ())
                : bird.elements().at(when);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s at t%+d (%.1fs): %.0f,%.0f alt %.0f, pass %d%s",
                callsign, offset, offset / 20.0, at.x, at.z, at.y, bird.elements().passAt(when),
                bird.parked() ? " (parked - the same place at every tick)" : "")), false);
        return 1;
    }

    public static int catalogue(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos hubPos = nearestHub(level, BlockPos.containing(source.getPosition()));
        if (hubPos == null || !(level.getBlockEntity(hubPos) instanceof ReconHubBlockEntity hub)) {
            source.sendFailure(Component.literal("No recon hub within " + (int) STATION_SEARCH + " blocks."));
            return 0;
        }
        long now = level.getGameTime();
        List<TrackedObject> book = OrbitalNet.catalogue(level, hub.netId());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "net %s space catalogue: %d object(s)", Long.toHexString(hub.netId()), book.size()))
                .withStyle(ChatFormatting.GOLD), false);
        if (book.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                            "  nothing seen: another faction's bird has to pass within "
                                    + (int) OrbitalConfig.SURVEILLANCE_RANGE + " blocks of this hub")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        for (TrackedObject object : book) {
            Vec3 guess = object.positionAt(now);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "  %06X  %d pass(es), last seen %ds ago, propagated to %.0f,%.0f alt %.0f%s",
                            object.designator(), object.observations(), object.age(now) / 20L,
                            guess.x, guess.z, guess.y, object.emitting() ? "  EMITTING" : ""))
                    .withStyle(object.age(now) > 2400L ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW),
                    false);
        }
        source.sendSuccess(() -> Component.literal(
                        "  these are elements, not positions: they are wrong from the instant the target burns")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return book.size();
    }

    public static int survey(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos hubPos = nearestHub(level, BlockPos.containing(source.getPosition()));
        if (hubPos == null || !(level.getBlockEntity(hubPos) instanceof ReconHubBlockEntity hub)) {
            source.sendFailure(Component.literal("No recon hub within " + (int) STATION_SEARCH + " blocks."));
            return 0;
        }
        long now = level.getGameTime();
        List<com.wf.wfballistics.orbital.survey.SurveyTile> tiles =
                com.wf.wfballistics.orbital.survey.SurveyRaster.get(level).tiles(hub.netId());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "net %s survey: %d tile(s) of %d x %d blocks",
                        Long.toHexString(hub.netId()), tiles.size(),
                        com.wf.wfballistics.orbital.survey.SurveyTile.BLOCKS,
                        com.wf.wfballistics.orbital.survey.SurveyTile.BLOCKS))
                .withStyle(ChatFormatting.GOLD), false);
        int pixels = com.wf.wfballistics.orbital.survey.SurveyTile.PIXELS;
        for (com.wf.wfballistics.orbital.survey.SurveyTile tile : tiles) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  tile %d,%d  %3d%% imaged  %ds old",
                    tile.tileX(), tile.tileZ(), tile.imaged() * 100 / (pixels * pixels),
                    Math.max(0L, now - tile.stamped()) / 20L)), false);
        }
        if (tiles.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                            "  nothing imaged: put an imagery payload up and park it over something")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new com.wf.wfballistics.network.SurveyOpenPacket(hub.netId(),
                            Mth.floor(source.getPosition().x), Mth.floor(source.getPosition().z)));
        }
        return tiles.size();
    }

    public static int deorbit(CommandSourceStack source, String callsign) {
        ServerLevel level = source.getLevel();
        SatView bird = OrbitalNet.byCallsign(level, callsign).orElse(null);
        if (bird == null) {
            source.sendFailure(Component.literal("No satellite answering to " + callsign));
            return 0;
        }
        OrbitalNet.deorbit(level, bird.id(), DeorbitReason.REMOVED);
        source.sendSuccess(() -> Component.literal("deorbited " + callsign)
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    public static int selfTest(CommandSourceStack source) {
        List<String> failures = OrbitalSelfTest.run(source.getLevel());
        if (failures.isEmpty()) {
            source.sendSuccess(() -> Component.literal("orbital self-test: all checks passed")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        for (String failure : failures) {
            source.sendFailure(Component.literal("orbital self-test: " + failure));
        }
        return 0;
    }

    public static CompletableFuture<Suggestions> suggestPayloads(CommandContext<CommandSourceStack> ctx,
                                                                 SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        for (ResourceLocation id : SatPayloads.ids()) {
            names.add(id.getPath());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    public static CompletableFuture<Suggestions> suggestCallsigns(CommandContext<CommandSourceStack> ctx,
                                                                  SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        for (SatView bird : OrbitalNet.all(ctx.getSource().getLevel())) {
            names.add(bird.callsign());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    @Nullable
    private static BlockPos nearestHub(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestSq = STATION_SEARCH * STATION_SEARCH;
        for (BlockPos pos : HubIndex.hubs(level)) {
            double distSq = pos.distSqr(origin);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = pos.immutable();
            }
        }
        return best;
    }
}
