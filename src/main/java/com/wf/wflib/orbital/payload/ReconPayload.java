package com.wf.wflib.orbital.payload;

import com.wf.wflib.orbital.OrbitalConfig;
import com.wf.wflib.orbital.SatPayload;
import com.wf.wflib.orbital.SatVerb;
import com.wf.wflib.orbital.Satellite;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconTargets;
import com.wf.wflib.recon.SensorRole;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.env.Atmosphere;
import com.wf.wflib.recon.env.ReconWeather;
import com.wf.wflib.recon.propagate.RadarPropagator;
import com.wf.wflib.recon.snapshot.ReconTerrain;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReconPayload implements SatPayload {

    private boolean active = true;
    private long lastSweep = Long.MIN_VALUE;
    private int lastContacts;
    private int lastMasked;

    @Override
    public boolean emitting() {
        return active;
    }

    @Override
    public List<SatVerb> verbs() {
        return List.of(
                SatVerb.action("active", 0, "ok", "radiate and sweep every pass"),
                SatVerb.action("standby", 0, "ok", "go quiet: no sweeps, no power drawn, nothing to home on"),
                SatVerb.value("mode", "text", "active or standby, and what the last sweep found"));
    }

    @Override
    public String command(ServerLevel level, Satellite self, String verb, String[] args) {
        switch (verb) {
            case "active" -> {
                active = true;
                return "active";
            }
            case "standby" -> {
                active = false;
                return "standby";
            }
            case "mode" -> {
                return String.format(Locale.ROOT, "%s, %d contact(s), %d masked, swath %.0f, fix +/-%.0f",
                        active ? "active" : "standby", lastContacts, lastMasked,
                        self.swath(), self.resolution());
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public void tick(ServerLevel level, Satellite self) {
        if (!active) {
            return;
        }
        long now = level.getGameTime();
        if (now % OrbitalConfig.RECON_SWEEP_TICKS != 0 || now == lastSweep) {
            return;
        }
        if (!self.inContact()) {
            return;
        }
        if (!self.spendPower(OrbitalConfig.RECON_SWEEP_POWER)) {
            self.report("sweep skipped: battery flat");
            return;
        }
        lastSweep = now;
        sweep(level, self, now);
    }

    private void sweep(ServerLevel level, Satellite self, long now) {
        Vec3 at = self.position(now);
        double swath = self.swath();
        double error = self.resolution();
        Atmosphere atmos = ReconWeather.sample(level);
        double environment = atmos.correct(
                RadarPropagator.environment(atmos, ReconTerrain.DEFAULT_DOWNFALL));
        double reach = Math.max(0.05, environment);
        double minRcs = OrbitalConfig.minRcsFor(self.elements().altitude()) / (reach * reach * reach * reach);
        SensorSnapshot sensor = new SensorSnapshot(self.rawId(), at.x, at.y, at.z,
                new SensorSpec(self.netId(), Band.RADAR, SensorRole.SURVEILLANCE, swath,
                        0.0, error, 0.0, OrbitalConfig.RECON_SWEEP_TICKS, 0.0, true, true),
                self.contactHops(), atmos, now);
        AABB volume = new AABB(at.x - swath, level.getMinBuildHeight(), at.z - swath,
                at.x + swath, level.getMaxBuildHeight(), at.z + swath);
        List<TargetSnapshot> targets = ReconTargets.collect(level, volume);
        RandomSource random = level.getRandom();
        List<Plot> plots = new ArrayList<>();
        int masked = 0;
        for (int i = 0; i < targets.size(); i++) {
            TargetSnapshot target = targets.get(i);
            double dx = target.x() - at.x;
            double dz = target.z() - at.z;
            if (dx * dx + dz * dz > swath * swath) {
                continue;
            }
            if (target.buried()) {
                masked++;
                continue;
            }
            double rcs = target.presentedTo(sensor).radarRcs();
            if (rcs < minRcs) {
                continue;
            }
            if (roofed(level, target)) {
                masked++;
                continue;
            }
            double len = Math.sqrt(dx * dx + dz * dz);
            double bx = len > 1.0E-4 ? dx / len : 1.0;
            double bz = len > 1.0E-4 ? dz / len : 0.0;
            double strength = rcs / minRcs;
            plots.add(Plot.single(Band.RADAR,
                    target.x() + random.nextGaussian() * error * 0.5,
                    target.y(),
                    target.z() + random.nextGaussian() * error * 0.5,
                    bx, bz, error, error, strength,
                    strength >= 2.0 ? target.kind() : ContactClass.UNKNOWN,
                    self.netId() != 0L && target.iffCode() == self.netId(),
                    self.contactHops(), now));
        }
        lastContacts = plots.size();
        lastMasked = masked;
        ReconNet.contributePlots(level, self.netId(), plots);
    }

    private static boolean roofed(ServerLevel level, TargetSnapshot target) {
        int x = Mth.floor(target.x());
        int z = Mth.floor(target.z());
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        if (chunk == null) {
            return false;
        }
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1 > target.y() + 1.0;
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Active", active);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        active = !tag.contains("Active") || tag.getBoolean("Active");
    }
}
