package com.wf.wflib.orbital.payload;

import com.wf.wflib.orbital.OrbitalConfig;
import com.wf.wflib.orbital.OrbitalNet;
import com.wf.wflib.orbital.SatPayload;
import com.wf.wflib.orbital.SatVerb;
import com.wf.wflib.orbital.Satellite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Locale;

public final class RodsPayload implements SatPayload {

    private int salvo = OrbitalConfig.ROD_SALVO;
    private int fired;

    @Override
    public List<SatVerb> verbs() {
        return List.of(
                SatVerb.action("fire", 2, "ok", "drop a salvo on x z"),
                SatVerb.action("salvo", 1, "ok", "rods per salvo"),
                SatVerb.value("rounds", "number", "rods left, and the salvo size"));
    }

    @Override
    public String command(ServerLevel level, Satellite self, String verb, String[] args) {
        switch (verb) {
            case "rounds" -> {
                return String.format(Locale.ROOT, "%d rod(s), salvo %d, %d fired",
                        self.magazine(), salvo, fired);
            }
            case "salvo" -> {
                Integer n = integer(args[0]);
                if (n == null || n < 1 || n > 12) {
                    return "err: salvo takes 1 to 12";
                }
                salvo = n;
                return "salvo " + salvo;
            }
            case "fire" -> {
                return fire(level, self, args);
            }
            default -> {
                return null;
            }
        }
    }

    private String fire(ServerLevel level, Satellite self, String[] args) {
        Double x = number(args[0]);
        Double z = number(args[1]);
        if (x == null || z == null) {
            return "err: fire takes two coordinates";
        }
        if (self.magazine() <= 0) {
            return "err: no rods left - resupply is another launch";
        }
        double dx = x - self.position(level.getGameTime()).x;
        double dz = z - self.position(level.getGameTime()).z;
        double swath = self.swath();
        if (dx * dx + dz * dz > swath * swath) {
            return String.format(Locale.ROOT, "err: target is outside the footprint (%.0f blocks of %.0f)",
                    Math.sqrt(dx * dx + dz * dz), swath);
        }

        int away = 0;
        for (int i = 0; i < salvo; i++) {
            if (!self.spendRound()) {
                break;
            }
            if (!self.spendPower(OrbitalConfig.ROD_POWER)) {
                break;
            }
            double spreadX = away == 0 ? 0.0 : level.getRandom().nextGaussian() * OrbitalConfig.ROD_DISPERSION;
            double spreadZ = away == 0 ? 0.0 : level.getRandom().nextGaussian() * OrbitalConfig.ROD_DISPERSION;
            OrbitalNet.dropRod(level, self, x + spreadX, z + spreadZ);
            away++;
            fired++;
        }
        if (away == 0) {
            return "err: no power to release";
        }
        return String.format(Locale.ROOT, "%d rod(s) away, %d left, impact in about %ds",
                away, self.magazine(),
                (int) (OrbitalConfig.RELEASE_DISTANCE / OrbitalConfig.ROD_SPEED / 20.0));
    }

    private static Double number(String raw) {
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Salvo", salvo);
        tag.putInt("Fired", fired);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        salvo = Math.max(1, tag.getInt("Salvo"));
        fired = tag.getInt("Fired");
    }
}
