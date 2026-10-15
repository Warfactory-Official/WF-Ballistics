package com.wf.wflib.pda.terminal;

import com.wf.wflib.drone.ControllableDrone;
import com.wf.wflib.drone.DroneTracker;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.orbital.OrbitalNet;
import com.wf.wflib.orbital.SatView;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.NamedNode;
import com.wf.wflib.recon.grid.ReconGrid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * PDA Terminal app, server end: one line -> reply lines. Drones = held PDA's channels (loaded); nodes and
 * satellites = own net, uplink required (as the Satellites app).
 */
public final class PdaTerminal {
    /** One token: refs and console addressing split on whitespace. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    /** < {@link TerminalReplyPacket} list cap. */
    private static final int MAX_LINES = 200;
    private static final String HELP = "drones | nodes | sats | rename <drone|node|sat> <#n|name> <new|->";

    private PdaTerminal() {
    }

    public static List<String> run(ServerPlayer player, String line) {
        if (!Pda.held(player)) {
            return List.of("err: no PDA in hand");
        }
        String[] w = line.trim().split("\\s+");
        return switch (w[0].toLowerCase(Locale.ROOT)) {
            case "help", "" -> List.of(HELP);
            case "drones" -> list(drones(player), d -> d.callsign() + "  " + d.flight().getDroneState().name()
                    + "  " + (int) player.distanceTo(d.asEntity()) + " m", "no linked drones in range");
            case "nodes" -> {
                List<GridNode> nodes = nodes(player);
                yield nodes == null ? List.of("err: no uplink") : list(nodes, n -> String.format(Locale.ROOT,
                        "%s  %s  %d %d %d", n.label(), n.online() ? "ONLINE " + n.hops() + " hop" : "OFFLINE",
                        n.pos().getX(), n.pos().getY(), n.pos().getZ()), "no nodes on this net");
            }
            case "sats" -> {
                List<SatView> sats = sats(player);
                yield sats == null ? List.of("err: no uplink") : list(sats, s -> s.callsign() + "  "
                        + s.payload().getPath(), "no satellites on this net");
            }
            case "rename" -> List.of(w.length == 4 ? rename(player, w[1].toLowerCase(Locale.ROOT), w[2], w[3])
                    : "err: " + HELP);
            default -> List.of("err: unknown '" + w[0] + "' (help)");
        };
    }

    private static String rename(ServerPlayer player, String kind, String ref, String name) {
        boolean reset = name.equals("-");
        if (!reset && !NAME.matcher(name).matches()) {
            return "err: name = 1-16 of A-Z 0-9 _ -";
        }
        switch (kind) {
            case "drone" -> {
                ControllableDrone drone = pick(drones(player), ref, ControllableDrone::callsign);
                if (drone == null) {
                    return "err: no linked drone '" + ref + "' in range";
                }
                drone.setCallsign(reset ? null : name);
                return "drone renamed " + drone.callsign();
            }
            case "node" -> {
                List<GridNode> nodes = nodes(player);
                if (nodes == null) {
                    return "err: no uplink";
                }
                GridNode node = pick(nodes, ref, GridNode::label);
                if (node == null || !(player.serverLevel().getBlockEntity(node.pos()) instanceof NamedNode named)) {
                    return "err: no renamable node '" + ref + "'";
                }
                named.rename(reset ? "" : name);
                return "node at " + node.pos().toShortString() + (reset ? " label reset" : " renamed " + name);
            }
            case "sat" -> {
                List<SatView> sats = sats(player);
                if (sats == null) {
                    return "err: no uplink";
                }
                SatView sat = pick(sats, ref, SatView::callsign);
                if (sat == null) {
                    return "err: no satellite '" + ref + "' on this net";
                }
                if (reset) {
                    return "err: satellites need a callsign";
                }
                return OrbitalNet.commandBy(player.serverLevel(), sat.id(), player.getGameProfile().getName(),
                        "callsign", new String[]{name});
            }
            default -> {
                return "err: rename drone|node|sat";
            }
        }
    }

    private static <T> List<String> list(List<T> items, Function<T, String> line, String none) {
        if (items.isEmpty()) {
            return List.of(none);
        }
        int shown = Math.min(items.size(), MAX_LINES);
        List<String> out = new ArrayList<>(shown + 1);
        for (int i = 0; i < shown; i++) {
            out.add("#" + (i + 1) + " " + line.apply(items.get(i)));
        }
        if (shown < items.size()) {
            out.add("... " + (items.size() - shown) + " more");
        }
        return out;
    }

    /** {@code #n} / {@code n} = 1-based listing index; else name, case-insensitive. */
    @Nullable
    private static <T> T pick(List<T> items, String ref, Function<T, String> name) {
        String digits = ref.startsWith("#") ? ref.substring(1) : ref;
        if (!digits.isEmpty() && digits.length() <= 3 && digits.chars().allMatch(Character::isDigit)) {
            int index = Integer.parseInt(digits) - 1;
            return index >= 0 && index < items.size() ? items.get(index) : null;
        }
        for (T item : items) {
            if (name.apply(item).equalsIgnoreCase(ref)) {
                return item;
            }
        }
        return null;
    }

    /** Held PDA's channel drones, loaded, channel order. */
    private static List<ControllableDrone> drones(ServerPlayer player) {
        CameraChannels channels = Pda.heldStack(player).getOrDefault(ModDataComponents.CHANNELS.get(),
                CameraChannels.EMPTY);
        List<ControllableDrone> out = new ArrayList<>();
        for (FeedTarget target : channels.targets()) {
            UUID id = target.drone().orElse(null);
            ControllableDrone drone = id == null ? null : DroneTracker.byId(player.serverLevel(), id);
            if (drone != null && drone.carrierAlive()) {
                out.add(drone);
            }
        }
        return out;
    }

    /** Own net, this level, by node id; null = no uplink. */
    @Nullable
    private static List<GridNode> nodes(ServerPlayer player) {
        long net = ReconOwners.netOf(player);
        if (!Uplink.allows(player, net)) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        ReconGrid grid = ReconNet.grid(level, net);
        List<GridNode> out = grid == null ? new ArrayList<>() : new ArrayList<>(grid.nodes());
        out.sort(Comparator.comparingLong(GridNode::id));
        return out;
    }

    /** Own net; null = no uplink. */
    @Nullable
    private static List<SatView> sats(ServerPlayer player) {
        long net = ReconOwners.netOf(player);
        return Uplink.allows(player, net) ? OrbitalNet.onNet(player.serverLevel(), net) : null;
    }
}
