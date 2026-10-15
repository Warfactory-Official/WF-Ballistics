package com.wf.wflib.ammo;

import net.minecraft.resources.ResourceLocation;

/**
 * Cartridge shape + carried data. Lengths mm; case diameters: {@code caseMm} head (body at the base), {@code neckMm},
 * {@code rimMm}; straight wall => {@code neckMm == caseMm}. {@code projectileLengthMm}: whole projectile (seated
 * part included). {@code massKg}: one cartridge carried.
 */
public record Calibre(ResourceLocation id, String name, CalibreKind kind, float bulletMm, float caseLengthMm,
                      float caseMm, float neckMm, float rimMm, float overallMm, float projectileLengthMm,
                      CaseMaterial caseMaterial, Casing casing, float massKg, int stackSize) {

    public Calibre {
        positive(id, "bulletMm", bulletMm);
        positive(id, "caseLengthMm", caseLengthMm);
        positive(id, "caseMm", caseMm);
        positive(id, "neckMm", neckMm);
        positive(id, "rimMm", rimMm);
        positive(id, "projectileLengthMm", projectileLengthMm);
        if (overallMm < caseLengthMm) {
            throw new IllegalArgumentException(id + ": overallMm " + overallMm + " < caseLengthMm " + caseLengthMm);
        }
        if (neckMm > caseMm + 1e-3f) {
            throw new IllegalArgumentException(id + ": neckMm " + neckMm + " > caseMm " + caseMm);
        }
        if (massKg < 0 || stackSize < 1 || stackSize > 99) {
            throw new IllegalArgumentException(id + ": massKg " + massKg + " / stackSize " + stackSize);
        }
    }

    private static void positive(ResourceLocation id, String field, float v) {
        if (!(v > 0)) {
            throw new IllegalArgumentException(id + ": " + field + " " + v + " <= 0");
        }
    }

    /** {@code <ns>:cartridge_<path>}, {@code . / -} -> {@code _}. */
    public ResourceLocation itemId() {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                "cartridge_" + id.getPath().replace('.', '_').replace('/', '_').replace('-', '_'));
    }

    /** Case drawn outside a projectile (rockets, caseless). */
    public boolean hasCase() {
        return caseMaterial != CaseMaterial.NONE;
    }

    /** Cased grenade (40x46 / 40x53 launcher) => LARGE; caseless (VOG-25, dischargers) => NONE. */
    public static Casing defaultCasing(CalibreKind kind, float bulletMm, CaseMaterial material) {
        return switch (kind) {
            case SHOTSHELL -> Casing.SHOTSHELL;
            case PISTOL -> bulletMm >= 12 ? Casing.LARGE : Casing.PISTOL;
            case RIFLE -> bulletMm >= 12 ? Casing.LARGE : Casing.RIFLE;
            case GRENADE -> material == CaseMaterial.NONE ? Casing.NONE : Casing.LARGE;
            case AUTOCANNON, TANK, ROCKET -> Casing.NONE;
        };
    }

    public static int defaultStack(CalibreKind kind) {
        return switch (kind) {
            case PISTOL, RIFLE -> 64;
            case SHOTSHELL -> 32;
            case GRENADE, AUTOCANNON -> 16;
            case TANK, ROCKET -> 4;
        };
    }
}
