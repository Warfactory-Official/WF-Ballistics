package com.wf.wflib.orbital.payload;

import com.wf.wflib.orbital.OrbitalConfig;
import com.wf.wflib.orbital.SatPayload;
import com.wf.wflib.orbital.SatVerb;
import com.wf.wflib.orbital.Satellite;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.track.Track;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RelayPayload implements SatPayload {

    private final Set<Long> links = new LinkedHashSet<>();
    private int lastCarried;

    @Override
    public List<SatVerb> verbs() {
        return List.of(
                SatVerb.action("link", 1, "ok", "carry this network's picture to another, named in hex"),
                SatVerb.action("unlink", 1, "ok", "stop carrying to that network"),
                SatVerb.value("links", "text", "which networks this relay is joining"));
    }

    @Override
    public String command(ServerLevel level, Satellite self, String verb, String[] args) {
        switch (verb) {
            case "link" -> {
                Long net = hex(args[0]);
                if (net == null) {
                    return "err: '" + args[0] + "' is not a network id";
                }
                if (net == self.netId()) {
                    return "err: a relay cannot link a network to itself";
                }
                links.add(net);
                return "linking " + Long.toHexString(net);
            }
            case "unlink" -> {
                Long net = hex(args[0]);
                if (net == null || !links.remove(net)) {
                    return "err: not linked to " + args[0];
                }
                return "unlinked " + Long.toHexString(net);
            }
            case "links" -> {
                if (links.isEmpty()) {
                    return "none";
                }
                StringBuilder out = new StringBuilder();
                for (long net : links) {
                    out.append(out.isEmpty() ? "" : " ").append(Long.toHexString(net));
                }
                return out + " (" + lastCarried + " track(s) last carried)";
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public void tick(ServerLevel level, Satellite self) {
        if (links.isEmpty() || !self.inContact()) {
            return;
        }
        if (!self.spendPower(OrbitalConfig.RELAY_POWER_PER_SLOW_TICK)) {
            return;
        }
        long now = level.getGameTime();
        Vec3 at = self.position(now);
        int carried = 0;
        for (long other : links) {
            carried += carry(level, self.netId(), other, at, now);
            carried += carry(level, other, self.netId(), at, now);
        }
        lastCarried = carried;
    }

    private static int carry(ServerLevel level, long from, long to, Vec3 relay, long now) {
        List<Track> tracks = ReconNet.picture(level, from).tracks();
        if (tracks.isEmpty()) {
            return 0;
        }
        List<Plot> plots = new ArrayList<>();
        for (Track track : tracks) {
            if (track.hops() >= OrbitalConfig.RELAY_HOP_COST) {
                continue;
            }
            double dx = track.x() - relay.x;
            double dz = track.z() - relay.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            double bx = len > 1.0E-4 ? dx / len : 1.0;
            double bz = len > 1.0E-4 ? dz / len : 0.0;
            double error = Math.max(1.0, track.errorRadius() * OrbitalConfig.RELAY_ERROR);
            plots.add(new Plot(bandOf(track), track.bandMask(), track.x(), track.y(), track.z(),
                    bx, bz, error, error, 1.0, track.guess(), Math.max(1, track.countEstimate()),
                    false, track.hops() + OrbitalConfig.RELAY_HOP_COST, now));
        }
        ReconNet.contributePlots(level, to, plots);
        return plots.size();
    }

    private static Band bandOf(Track track) {
        int mask = track.bandMask();
        for (int i = 0; i < Band.VALUES.length; i++) {
            if ((mask & (1 << i)) != 0) {
                return Band.VALUES[i];
            }
        }
        return Band.RADAR;
    }

    private static Long hex(String raw) {
        try {
            return Long.parseUnsignedLong(raw.toLowerCase(Locale.ROOT).replaceFirst("^0x", ""), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        long[] out = new long[links.size()];
        int i = 0;
        for (long net : links) {
            out[i++] = net;
        }
        tag.putLongArray("Links", out);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        links.clear();
        for (long net : tag.getLongArray("Links")) {
            links.add(net);
        }
    }
}
