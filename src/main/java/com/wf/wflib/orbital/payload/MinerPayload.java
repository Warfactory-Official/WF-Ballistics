package com.wf.wflib.orbital.payload;

import com.wf.wflib.orbital.CargoTable;
import com.wf.wflib.orbital.OrbitalConfig;
import com.wf.wflib.orbital.OrbitalNet;
import com.wf.wflib.orbital.SatPayload;
import com.wf.wflib.orbital.SatVerb;
import com.wf.wflib.orbital.Satellite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

public final class MinerPayload implements SatPayload {

    private double units;
    private boolean propelled;
    private int dropped;

    @Override
    public List<SatVerb> verbs() {
        return List.of(
                SatVerb.action("drop", 2, "ok", "send the hold down to x z"),
                SatVerb.action("ballistic", 0, "ok", "cheap capsule: scattered, and it hits hard"),
                SatVerb.action("propelled", 0, "ok", "burn fuel to land on a pad, intact"),
                SatVerb.value("cargo", "number", "units in the hold, and how it will come down"));
    }

    @Override
    public String command(ServerLevel level, Satellite self, String verb, String[] args) {
        switch (verb) {
            case "ballistic" -> {
                propelled = false;
                return "ballistic capsule selected";
            }
            case "propelled" -> {
                propelled = true;
                return "propelled lander selected";
            }
            case "cargo" -> {
                return String.format(Locale.ROOT, "%d/%d units, %s, %d dropped",
                        (int) units, OrbitalConfig.MINER_HOLD,
                        propelled ? "propelled" : "ballistic", dropped);
            }
            case "drop" -> {
                return drop(level, self, args);
            }
            default -> {
                return null;
            }
        }
    }

    private String drop(ServerLevel level, Satellite self, String[] args) {
        Double x = parse(args[0]);
        Double z = parse(args[1]);
        if (x == null || z == null) {
            return "err: drop takes two coordinates";
        }
        if (units < 1.0) {
            return "err: the hold is empty";
        }
        if (CargoTable.isEmpty()) {
            return "err: no cargo table registered";
        }
        double aimX = x;
        double aimZ = z;
        String how;
        if (propelled) {
            if (!self.spendFuel(OrbitalConfig.LANDER_FUEL)) {
                return "err: not enough fuel for a powered descent";
            }
            Vec3 pad = LandingPads.nearest(level, self.netId(), x, z, OrbitalConfig.PAD_CAPTURE_RANGE);
            if (pad != null) {
                aimX = pad.x;
                aimZ = pad.z;
                how = String.format(Locale.ROOT, "propelled onto the pad at %.0f %.0f", pad.x, pad.z);
            } else {
                how = "propelled, no pad in reach - landing on the coordinate";
            }
        } else {
            double cep = OrbitalConfig.BALLISTIC_CEP_PER_KM * self.elements().altitude() / 1000.0;
            aimX += level.getRandom().nextGaussian() * cep;
            aimZ += level.getRandom().nextGaussian() * cep;
            how = String.format(Locale.ROOT, "ballistic, scattered to about %.0f blocks", cep);
        }

        List<ItemStack> manifest = CargoTable.roll(level.getRandom(), (int) units);
        if (manifest.isEmpty()) {
            return "err: the cargo table produced nothing";
        }
        OrbitalNet.dropCargo(level, self, aimX, aimZ, manifest, propelled);
        units = 0.0;
        dropped++;
        return String.format(Locale.ROOT, "%d stack(s) away, %s", manifest.size(), how);
    }

    @Override
    public void tick(ServerLevel level, Satellite self) {
        if (units >= OrbitalConfig.MINER_HOLD) {
            return;
        }
        if (!self.spendPower(OrbitalConfig.MINER_POWER_PER_SLOW_TICK)) {
            return;
        }
        units = Math.min(OrbitalConfig.MINER_HOLD, units + OrbitalConfig.MINER_UNITS_PER_SLOW_TICK);
    }

    private static Double parse(String raw) {
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Units", units);
        tag.putBoolean("Propelled", propelled);
        tag.putInt("Dropped", dropped);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        units = tag.getDouble("Units");
        propelled = tag.getBoolean("Propelled");
        dropped = tag.getInt("Dropped");
    }
}
