package com.wf.wfballistics.orbital.payload;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.aef.standard.EntityProcessorCross;
import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import com.wf.wfballistics.orbital.OrbitalConfig;
import com.wf.wfballistics.orbital.OrbitalDrop;
import com.wf.wfballistics.orbital.SatPayload;
import com.wf.wfballistics.orbital.SatVerb;
import com.wf.wfballistics.orbital.Satellite;
import com.wf.wfballistics.recon.env.Atmosphere;
import com.wf.wfballistics.recon.env.ReconWeather;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

public final class LaserPayload implements SatPayload {

    private double charge;
    private boolean charging = true;
    private int shots;

    @Override
    public boolean emitting() {
        return charging || charge >= OrbitalConfig.LASER_CHARGE;
    }

    @Override
    public List<SatVerb> verbs() {
        return List.of(
                SatVerb.action("fire", 2, "ok", "discharge onto x z"),
                SatVerb.action("charge", 0, "ok", "spend the battery on the capacitor"),
                SatVerb.action("hold", 0, "ok", "stop charging and keep the power"),
                SatVerb.value("canfire", "text", "charge, weather, and whether the shot would land"));
    }

    @Override
    public String command(ServerLevel level, Satellite self, String verb, String[] args) {
        switch (verb) {
            case "charge" -> {
                charging = true;
                return "charging";
            }
            case "hold" -> {
                charging = false;
                return "holding at " + (int) charge;
            }
            case "canfire" -> {
                double transmission = transmission(level);
                return String.format(Locale.ROOT, "%d/%d charge, air %.2f, %s, %d fired",
                        (int) charge, (int) OrbitalConfig.LASER_CHARGE, transmission,
                        ready() ? (transmission < OrbitalConfig.LASER_MIN_TRANSMISSION
                                ? "too much weather" : "ready") : "charging",
                        shots);
            }
            case "fire" -> {
                return fire(level, self, args);
            }
            default -> {
                return null;
            }
        }
    }

    private boolean ready() {
        return charge >= OrbitalConfig.LASER_CHARGE;
    }

    private String fire(ServerLevel level, Satellite self, String[] args) {
        Double x = number(args[0]);
        Double z = number(args[1]);
        if (x == null || z == null) {
            return "err: fire takes two coordinates";
        }
        if (!ready()) {
            return String.format(Locale.ROOT, "err: %d of %d charge",
                    (int) charge, (int) OrbitalConfig.LASER_CHARGE);
        }
        Vec3 at = self.position(level.getGameTime());
        double dx = x - at.x;
        double dz = z - at.z;
        double swath = self.swath();
        if (dx * dx + dz * dz > swath * swath) {
            return String.format(Locale.ROOT, "err: target is outside the footprint (%.0f blocks of %.0f)",
                    Math.sqrt(dx * dx + dz * dz), swath);
        }
        double transmission = transmission(level);
        if (transmission < OrbitalConfig.LASER_MIN_TRANSMISSION) {
            return String.format(Locale.ROOT,
                    "err: weather is taking %.0f%% of the beam - hold for clear air", (1.0 - transmission) * 100.0);
        }

        charge = 0.0;
        shots++;
        double y = OrbitalDrop.groundAt(level, x, z);
        float size = (float) (OrbitalConfig.LASER_BLAST * transmission);
        ExplosionAEF blast = new ExplosionAEF(level, x, y + 1.0, z, size);
        blast.setBlockAllocator(new BlockAllocatorStandard(24));
        blast.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        blast.setEntityProcessor(new EntityProcessorCross());
        blast.setPlayerProcessor(new PlayerProcessorStandard());
        blast.igniterFaction(self.owner());
        blast.explode();
        return String.format(Locale.ROOT, "discharged on %.0f %.0f, %.0f%% through the air, recharging",
                x, z, transmission * 100.0);
    }

    /** Rain and thunder take the beam. */
    public static double transmission(Atmosphere atmos) {
        return Math.max(0.0, 1.0 - atmos.rain() * 0.35 - atmos.thunder() * 0.4);
    }

    private static double transmission(ServerLevel level) {
        return transmission(ReconWeather.sample(level));
    }

    @Override
    public void tick(ServerLevel level, Satellite self) {
        if (!charging || ready()) {
            return;
        }
        if (!self.spendPower(OrbitalConfig.LASER_CHARGE_RATE)) {
            return;
        }
        charge = Math.min(OrbitalConfig.LASER_CHARGE, charge + OrbitalConfig.LASER_CHARGE_RATE);
    }

    private static Double number(String raw) {
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Charge", charge);
        tag.putBoolean("Charging", charging);
        tag.putInt("Shots", shots);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        charge = tag.getDouble("Charge");
        charging = !tag.contains("Charging") || tag.getBoolean("Charging");
        shots = tag.getInt("Shots");
    }
}
