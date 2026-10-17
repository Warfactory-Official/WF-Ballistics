package com.wf.wflib.ammo;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * WFLib base calibres + variants (nominal service loads; dims mm). Mass + muzzle velocity: published service loads
 * (Wikipedia cartridge articles, TM 43-0001-27, Nammo handbook); grenade / rocket Cd + motor fitted to published range,
 * peak speed or self-destruct distance; gravity 9.8. {@code damagePerKJ} = median of converted packs; {@code blockPen}:
 * guide §29b anchors, else {@code 31.58 (E kJ / mm)^0.8517}, AP x1.5.
 */
public final class AmmoCatalogue {
    public static final String SOURCE = WFLib.MODID;
    static final double RHO = 1.225;
    static final Map<String, Float> PEN_ANCHORS = Map.of("9x19", 3f, "5.56x45", 10f, "7.62x51", 16f, "12.7x99", 40f);
    static final int RED = 0xFF5020;
    static final int GREEN = 0x40FF40;
    static final int TIP_GREEN = 0x2E8B3A;
    static final int TIP_ORANGE = 0xE8751A;
    static final int TIP_BLACK = 0x1A1A1A;
    static final int TIP_RED = 0xB22222;
    static final int TIP_VIOLET = 0x7A3E9D;
    static final int TIP_YELLOW = 0xD8C23A;
    static final int TIP_GOLD = 0xC9A227;
    static final int TIP_BLUE = 0x2F5DA8;
    private static final ResourceLocation STANDARD = rl("standard");
    private static final ResourceLocation SHAPED = rl("shaped_charge");
    private static final ResourceLocation FRAG = rl("fragmentation");
    private static final ResourceLocation THERMOBARIC = rl("thermobaric");

    private AmmoCatalogue() {
    }

    static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    /** {@code 0.5 rho Cd A / m}, 1/m. */
    public static float quadDrag(float mm, float grams, float cd) {
        double area = Math.PI * Math.pow(mm / 2000.0, 2);
        return (float) (0.5 * RHO * cd * area / (grams / 1000.0));
    }

    /** blockPen (mm steel-eq) of a ball load; rounded to 0.5. */
    static float ballPen(String calibre, float mm, float grams, float ms) {
        Float anchor = PEN_ANCHORS.get(calibre);
        double e = 0.5 * grams / 1000.0 * ms * ms / 1000.0;
        double v = anchor != null ? anchor : 31.58 * Math.pow(e / mm, 0.8517);
        return Math.round(v * 2) / 2f;
    }

    private record Cal(Calibre c, String key, float dpk) {
        AmmoVariant.Builder variant(String role, String name, float grams, float ms, float cd) {
            return AmmoVariant.builder(rl(key + "/" + role), c.id(), name)
                    .flight(ms, grams / 1000f, quadDrag(c.bulletMm(), grams, cd)).damagePerKJ(dpk);
        }

        /** Kinetic small-arms load: pen scaled from ball by energy, x {@code factor}. */
        AmmoVariant.Builder kinetic(String role, String name, float grams, float ms, float cd, float factor,
                                    float ballGrams, float ballMs) {
            float ball = ballPen(key, c.bulletMm(), ballGrams, ballMs);
            double ratio = Math.pow((grams * ms * ms) / (ballGrams * ballMs * ballMs), 0.8517);
            return variant(role, name, grams, ms, cd).blockPen(Math.round(ball * ratio * factor * 2) / 2f);
        }
    }

    private static Cal cal(String key, String name, CalibreKind kind, float bullet, float caseLen, float caseD,
                           float neck, float rim, float oal, float projLen, CaseMaterial mat, float cartGrams,
                           float dpk) {
        Calibre c = new Calibre(rl(key), name, kind, bullet, caseLen, caseD, neck, rim, oal, projLen, mat,
                Calibre.defaultCasing(kind, bullet, mat), cartGrams / 1000f, Calibre.defaultStack(kind));
        Ammo.register(c, SOURCE);
        return new Cal(c, key, dpk);
    }

    private static void add(AmmoVariant.Builder b) {
        Ammo.register(b.build(), SOURCE);
    }

    /** Small arms: ball first (calibre default), tracer, AP, subsonic where fielded. */
    private static void smallArms(Cal c, String ball, float g, float ms, float cd, String tracer, int tracerRgb,
                                  int tracerTip, String ap, float apGrams, float apMs, String sub, float subGrams,
                                  float subMs) {
        add(c.kinetic("ball", ball, g, ms, cd, 1f, g, ms).tip(c.key().equals("5.56x45") ? TIP_GREEN
                : AmmoVariant.NONE));
        if (tracer != null) {
            add(c.kinetic("tracer", tracer, g, ms, cd, 1f, g, ms).tracer(tracerRgb).tip(tracerTip));
        }
        if (ap != null) {
            add(c.kinetic("ap", ap, apGrams, apMs, cd, 1.5f, g, ms).tip(TIP_BLACK));
        }
        if (sub != null) {
            add(c.kinetic("subsonic", sub, subGrams, subMs, cd, 1f, g, ms).tip(c.key().equals("9x19")
                    ? AmmoVariant.NONE : TIP_GREEN));
        }
    }

    static void register() {
        CaseMaterial br = CaseMaterial.BRASS, st = CaseMaterial.STEEL;
        CalibreKind p = CalibreKind.PISTOL, r = CalibreKind.RIFLE;
        smallArms(cal("9x19", "9x19mm Parabellum", p, 9.02f, 19.15f, 9.93f, 9.65f, 9.96f, 29.69f, 15.6f, br, 12f,
                15.4f), "FMJ", 8f, 370, 0.25f, null, 0, 0, "7N21", 5.2f, 460, "147 gr subsonic", 9.5f, 300);
        smallArms(cal("9x18", "9x18mm Makarov", p, 9.27f, 18f, 9.95f, 9.91f, 9.95f, 25f, 12f, st, 10f, 30.5f),
                "57-N-181S", 6f, 298, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("9x21", "9x21mm IMI", p, 9.02f, 21.15f, 9.96f, 9.65f, 9.96f, 33f, 15f, br, 12.5f, 10.45f),
                "FMJ", 7.45f, 390, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("7.62x25", "7.62x25mm Tokarev", p, 7.85f, 25f, 9.8f, 8.5f, 9.98f, 34.3f, 14f, st, 10.7f,
                9.36f), "Type P", 5.5f, 450, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("11.43x23", ".45 ACP", p, 11.48f, 22.8f, 12f, 12f, 12.2f, 32.4f, 17f, br, 21f, 13.4f),
                "M1911 ball", 15f, 255, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("9x33r", ".357 Magnum", p, 9.07f, 32.8f, 9.63f, 9.63f, 11.2f, 40f, 15f, br, 16f, 13.5f),
                "JSP", 10.24f, 378, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("10.9x33r", ".44 Magnum", p, 10.9f, 32.6f, 11.6f, 11.6f, 13.1f, 40.9f, 17f, br, 22f, 11.3f),
                "JSP", 15.6f, 440, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("12.7x33", ".50 Action Express", p, 12.7f, 32.6f, 13.9f, 13.4f, 13.9f, 40.9f, 16f, br, 33f,
                14.3f), "JHP", 19.4f, 470, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("12.7x41r", ".500 S&W Magnum", p, 12.7f, 41.3f, 13.6f, 13.6f, 14.3f, 57.2f, 20f, br, 38f,
                12.4f), "JSP", 25.92f, 495, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("5.7x28", "FN 5.7x28mm", p, 5.7f, 28.9f, 7.95f, 6.3f, 7.8f, 40.5f, 21.6f, br, 6f, 14.3f),
                "SS190", 2f, 715, 0.30f, "L191", RED, TIP_RED, null, 0, 0, null, 0, 0);
        smallArms(cal("4.6x30", "HK 4.6x30mm", p, 4.65f, 30.5f, 8f, 5.3f, 8f, 38.5f, 18f, br, 6.5f, 9.53f),
                "DM11", 2f, 720, 0.30f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("5.6x27r", ".22 WMR", p, 5.7f, 26.8f, 6.1f, 6.1f, 7.4f, 34f, 12f, br, 4.5f, 15f),
                "JHP", 2.6f, 580, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("5.56x45", "5.56x45mm NATO", r, 5.7f, 44.7f, 9.6f, 6.43f, 9.6f, 57.4f, 23f, br, 12.3f, 5.94f),
                "M855", 4f, 930, 0.30f, "M856", RED, TIP_ORANGE, "M995", 3.4f, 1030, null, 0, 0);
        smallArms(cal("5.45x39", "5.45x39mm", r, 5.6f, 39.82f, 10f, 6.29f, 10f, 57f, 25.5f, st, 10.2f, 8.0f),
                "7N6", 3.4f, 880, 0.30f, "7T3M", GREEN, TIP_GREEN, "7N22", 3.7f, 890, "7U1", 5.2f, 303);
        smallArms(cal("5.8x42", "5.8x42mm DBP10", r, 6f, 42f, 10.4f, 6.6f, 10.4f, 58f, 24f, st, 12.9f, 12.9f),
                "DBP10", 4.6f, 915, 0.30f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("6.8x43", "6.8mm SPC", r, 7f, 42.6f, 10.7f, 7.8f, 10.7f, 58f, 25f, br, 16f, 4.13f),
                "OTM", 7.5f, 790, 0.30f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("6.8x51", ".277 Fury", r, 7f, 51.2f, 12f, 7.9f, 12f, 71f, 32f, br, 23f, 2.24f),
                "XM1186", 8.4f, 910, 0.28f, "XM1184", RED, TIP_ORANGE, null, 0, 0, null, 0, 0);
        smallArms(cal("7.62x39", "7.62x39mm", r, 7.92f, 38.7f, 11.35f, 8.6f, 11.35f, 56f, 26.8f, st, 16.3f, 5.46f),
                "57-N-231", 7.9f, 715, 0.33f, "57-T-231P", GREEN, TIP_GREEN, "7N23", 7.9f, 740, "US", 12.5f,
                295);
        smallArms(cal("7.62x51", "7.62x51mm NATO", r, 7.82f, 51.18f, 11.94f, 8.77f, 12.01f, 71.12f, 29f, br, 24f,
                3.65f), "M80", 9.5f, 840, 0.30f, "M62", RED, TIP_ORANGE, "M993", 8.2f, 910, null, 0, 0);
        smallArms(cal("7.62x54r", "7.62x54mmR", r, 7.92f, 53.72f, 12.37f, 8.53f, 14.48f, 77.16f, 32f, st, 22f,
                4.83f), "57-N-323S", 9.6f, 825, 0.30f, "T-46", GREEN, TIP_GREEN, "7N13", 9.4f, 830, null, 0, 0);
        smallArms(cal("7.62x63", ".30-06 Springfield", r, 7.82f, 63.3f, 11.96f, 8.63f, 12.01f, 84.8f, 30f, br, 25f,
                8.57f), "M2 ball", 9.72f, 835, 0.30f, "M25", RED, TIP_ORANGE, "M2 AP", 10.74f, 828, null, 0, 0);
        smallArms(cal("7.92x33", "7.92x33mm Kurz", r, 8.2f, 33f, 11.9f, 8.9f, 11.9f, 48f, 26f, st, 17f, 2.9f),
                "Pist Patr 43", 8.1f, 685, 0.33f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("7.92x57", "7.92x57mm Mauser", r, 8.2f, 57f, 11.95f, 9f, 11.95f, 82f, 28f, br, 26f, 6.34f),
                "sS", 12.8f, 760, 0.30f, null, 0, 0, "SmK", 11.85f, 785, null, 0, 0);
        smallArms(cal("8.6x70", ".338 Lapua Magnum", r, 8.61f, 69.2f, 14.9f, 9.46f, 14.93f, 93.5f, 40f, br, 46f,
                3.88f), "Lock Base", 16.2f, 900, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("9x39", "9x39mm", r, 9.25f, 38.7f, 11.35f, 10f, 11.35f, 56f, 34f, st, 23f, 14f),
                "SP-5", 16f, 295, 0.25f, null, 0, 0, "SP-6", 16f, 310, null, 0, 0);
        smallArms(cal("11.6x53r", ".45-70 Government", r, 11.63f, 53.5f, 12.7f, 12.3f, 15.2f, 64.8f, 25f, br, 39f,
                12.3f), "405 gr", 26.2f, 400, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("12.7x55", "12.7x55mm", r, 12.7f, 55f, 15f, 13.8f, 15f, 81f, 40f, st, 100f, 5.1f),
                "STs-130", 59f, 295, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("12.7x99", ".50 BMG", r, 12.95f, 99.3f, 20.4f, 14.2f, 20.4f, 138.4f, 58f, br, 115f, 2.33f),
                "M33", 42f, 890, 0.28f, "M17", RED, TIP_RED, "M2 AP", 45.8f, 856, null, 0, 0);
        smallArms(cal("12.7x108", "12.7x108mm", r, 12.98f, 107.8f, 21.75f, 13.95f, 21.75f, 147.5f, 64f, st, 130f,
                2.33f), "57-B-541", 48f, 820, 0.28f, "BZT-44", GREEN, TIP_VIOLET, "B-32", 48.3f, 830, null, 0, 0);
        smallArms(cal("14.5x114", "14.5x114mm", r, 14.88f, 114f, 26.95f, 16.4f, 26.95f, 155.8f, 66f, st, 200f,
                2f), "B-32 API", 64f, 1000, 0.28f, "BZT", GREEN, TIP_VIOLET, "BS-41", 65f, 976, null, 0, 0);
        smallArms(cal("7.62x33", ".30 Carbine", r, 7.85f, 32.8f, 9.04f, 8.4f, 9.14f, 42.67f, 17f, br, 12.5f, 7.2f),
                "M1 ball", 7.13f, 607, 0.30f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("7.65x17", ".32 ACP", p, 7.94f, 17.3f, 8.6f, 8.6f, 9.1f, 25f, 12f, br, 7.5f, 40f), "FMJ",
                4.6f, 276, 0.25f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("8x50r", "8x50mmR Lebel", r, 8.3f, 50.5f, 13.8f, 8.9f, 16f, 70f, 39f, br, 29f, 6.34f),
                "Balle D", 12.8f, 730, 0.28f, null, 0, 0, null, 0, 0, null, 0, 0);
        smallArms(cal("7.62x67", ".300 Winchester Magnum", r, 7.82f, 66.5f, 13f, 8.6f, 13.5f, 84.8f, 38f, br, 33f,
                4.1f), "Mk 248 Mod 1", 14.26f, 869, 0.26f, null, 0, 0, null, 0, 0, null, 0, 0);

        Cal ga = cal("12ga", "12 gauge", CalibreKind.SHOTSHELL, 18.5f, 65f, 20.2f, 20.2f, 22.8f, 65f, 18f,
                CaseMaterial.POLYMER, 40f, 27.75f);
        add(AmmoVariant.builder(rl("12ga/buck"), ga.c().id(), "00 buck")
                .flight(400, 0.0035f, quadDrag(8.38f, 3.5f, 0.47f)).pellets(9).damagePerKJ(27.75f)
                .blockPen(ballPen("12ga", 8.38f, 3.5f, 400)));
        add(AmmoVariant.builder(rl("12ga/slug"), ga.c().id(), "Foster slug")
                .flight(480, 0.028f, quadDrag(18.5f, 28f, 0.50f)).damagePerKJ(5.5f)
                .blockPen(ballPen("12ga", 18.5f, 28f, 480)));

        Cal g40 = cal("40x46", "40x46mm grenade", CalibreKind.GRENADE, 40f, 46f, 43.5f, 43.5f, 43.5f, 99f, 70f,
                CaseMaterial.ALUMINIUM, 230f, 7.37f);
        // Cd: 400 m published max range.
        add(g40.variant("he", "M433 HEDP", 180, 76, 0.27f).explosive(STANDARD, 2f).armorPen(63).tip(TIP_GOLD));
        Cal g53 = cal("40x53", "40x53mm grenade", CalibreKind.GRENADE, 40f, 53f, 43.8f, 43.8f, 43.8f, 112f, 80f,
                CaseMaterial.ALUMINIUM, 340f, 7.37f);
        add(g53.variant("he", "M430 HEDP", 240, 241, 0.30f).explosive(STANDARD, 2f).armorPen(76).tip(TIP_GOLD));
        // Vehicle discharger: lobbed short, bursts near the hull (gravity = launcher's arc, not ballistics).
        Cal dis = cal("76mm_discharger", "76mm vehicle grenade", CalibreKind.GRENADE, 76f, 1f, 76f, 76f, 76f, 200f,
                200f, CaseMaterial.NONE, 500f, 1.6f);
        add(dis.variant("frag", "Frag", 500, 30, 0.40f).gravity(28f).explosive(FRAG, 3f));
        Cal vog = cal("vog25", "VOG-25 grenade", CalibreKind.GRENADE, 40f, 1f, 40f, 40f, 40f, 102f, 102f,
                CaseMaterial.NONE, 250f, 7.37f);
        add(vog.variant("he", "VOG-25", 250, 76.5f, 0.40f).explosive(FRAG, 2f));
        Cal vog17 = cal("30x29", "30x29mm grenade", CalibreKind.GRENADE, 30f, 29f, 30f, 30f, 30f, 132f, 113f,
                CaseMaterial.STEEL, 350f, 7.37f);
        add(vog17.variant("he", "VOG-17M", 280, 185, 0.40f).explosive(FRAG, 2f));

        autocannon(cal("20x102", "20x102mm", CalibreKind.AUTOCANNON, 20f, 102f, 29.95f, 21.7f, 29.95f, 166f, 75f,
                br, 258f, 0.5f), "M56 HEI", 102, 1030, 0.8f, "M53 API", 100, 1030, 25, "M220 TP-T");
        autocannon(cal("25x137", "25x137mm", CalibreKind.AUTOCANNON, 25f, 137f, 38.6f, 27.3f, 38.6f, 223f, 100f, st,
                500f, 0.3f), "M792 HEI-T", 185, 1100, 1.2f, "M791 APDS-T", 134, 1345, 55, null);
        autocannon(cal("30x173", "30x173mm", CalibreKind.AUTOCANNON, 30f, 173f, 44.5f, 32f, 44.5f, 290f, 120f,
                CaseMaterial.ALUMINIUM, 680f, 0.25f), "PGU-13 HEI", 360, 1010, 1.6f, "PGU-14 API", 425, 1010, 69,
                null);

        autocannon(cal("23x115", "23x115mm", CalibreKind.AUTOCANNON, 23f, 115f, 36.4f, 25.6f, 36.4f, 199f, 100f, st,
                320f, 0.6f), "OFZ HEI", 175, 715, 0.9f, "BZ API", 200, 690, 30, null);
        autocannon(cal("23x152b", "23x152mmB", CalibreKind.AUTOCANNON, 23f, 152f, 39f, 25.5f, 41f, 236f, 106f, st,
                450f, 0.33f), "OFZ HEI", 188.5f, 980, 1.0f, "BZT API-T", 190, 970, 40, null);
        autocannon(cal("27x145b", "27x145mmB", CalibreKind.AUTOCANNON, 27f, 145f, 40f, 29f, 40f, 231f, 105f, st, 516f,
                0.26f), "HEI", 260, 1025, 1.2f, "APHE", 260, 1100, 50, null);
        autocannon(cal("30x165", "30x165mm", CalibreKind.AUTOCANNON, 30f, 165f, 42.3f, 32.5f, 43.4f, 283f, 121f, st,
                830f, 0.22f), "OF-84 HE-FI", 389, 960, 1.4f, "3UBR8 APDS-T", 304, 1120, 60, null);
        autocannon(cal("30x150b", "30x150mmB", CalibreKind.AUTOCANNON, 30f, 150f, 42f, 32f, 42f, 263f, 115f, br, 880f,
                0.31f), "HEI", 244, 1025, 1.2f, "APFSDS-T", 145, 1350, 60, null);

        tank(cal("105mm", "105mm L7 tank", CalibreKind.TANK, 105f, 617f, 150f, 106f, 150f, 1000f, 420f, br, 18000f,
                0.3f), "M833 APFSDS", 3.7f, 1490, 420, "M456 HEAT", 10.5f, 1174, 400, "L35 HESH", 11.3f, 730);
        tank(cal("120mm", "120mm smoothbore tank", CalibreKind.TANK, 120f, 570f, 150f, 121f, 160f, 980f, 480f,
                CaseMaterial.COMBUSTIBLE, 22000f, 0.3f), "M829A3 APFSDS", 4.6f, 1555, 800, "M830 HEAT", 13.5f,
                1140, 600, "M908 HE-OR", 11.4f, 1400);
        tank(cal("125mm", "125mm smoothbore tank", CalibreKind.TANK, 125f, 455f, 145f, 126f, 145f, 1000f, 500f,
                CaseMaterial.COMBUSTIBLE, 30000f, 0.3f), "3BM42 APFSDS", 4.85f, 1700, 650, "3BK29 HEAT", 19f, 905,
                700, "3OF26 HE-FRAG", 23f, 850);

        // Indirect fire: linear drag only (CA firing solver is exact for it).
        Cal m81 = cal("81mm", "81mm mortar", CalibreKind.TANK, 81f, 1f, 81f, 81f, 81f, 500f, 500f,
                CaseMaterial.NONE, 4100f, 5.4f);
        add(m81.variant("he", "M821 HE", 4100, 120, 0f).drag(0.01f).explosive(STANDARD, 5f).tip(TIP_YELLOW));
        Cal m60 = cal("60mm", "60mm mortar", CalibreKind.TANK, 60f, 1f, 60f, 60f, 60f, 350f, 350f,
                CaseMaterial.NONE, 1720f, 3.3f);
        add(m60.variant("he", "M720 HE", 1720, 237, 0f).drag(0.01f).explosive(STANDARD, 4f).tip(TIP_YELLOW));
        // Flak 18/36/37: Sprgr.Patr. L/4.5 (time fuze not modelled: contact HE), PzGr.Patr. 39 APCBC.
        Cal f88 = cal("88x571r", "8.8 cm Flak", CalibreKind.TANK, 88f, 571f, 107f, 90f, 112f, 931f, 380f, br, 14700f,
                0.3f);
        add(f88.variant("he", "Sprgr.Patr. L/4.5", 9400, 820, 0.30f).damagePerKJ(0.03f).explosive(STANDARD, 4f)
                .tip(TIP_YELLOW));
        add(f88.variant("ap", "PzGr.Patr. 39", 10200, 773, 0.28f).armorPen(160).blockPen(160).tip(TIP_BLACK));
        Cal n127 = cal("127mm_naval", "5-inch/54 naval", CalibreKind.TANK, 127f, 835f, 150f, 128f, 160f, 1500f,
                660f, br, 47000f, 0.105f);
        add(n127.variant("he", "Mk 64 HE-PD", 31750, 600, 0f).drag(0.005f).explosive(STANDARD, 5f).tip(TIP_YELLOW));
        Cal n406 = cal("406mm_naval", "16-inch/50 naval", CalibreKind.TANK, 406f, 1f, 406f, 406f, 406f, 1850f, 1850f,
                CaseMaterial.NONE, 862000f, 0.0102f);
        add(n406.variant("he", "Mk 13 HC", 862000, 800, 0f).drag(0.002f).explosive(STANDARD, 12f)
                .tip(TIP_YELLOW));
        add(n406.variant("ap", "Mk 8 APC", 1225000, 762, 0f).drag(0.002f).explosive(STANDARD, 6f).armorPen(800)
                .tip(TIP_BLACK));

        Cal rpg = cal("rpg7", "RPG-7 rocket", CalibreKind.ROCKET, 85f, 1f, 40f, 40f, 40f, 950f, 950f,
                CaseMaterial.NONE, 2600f, 0.315f);
        // 115 m/s launch, 295 peak, 920 m at 4.5 s (self-destruct) => Cd, motor.
        add(rpg.variant("heat", "PG-7V", 2200, 115, 0.50f).explosive(SHAPED, 2.5f).armorPen(260)
                .motor(430, 0.45f));
        // No sustainer.
        add(AmmoVariant.builder(rl("rpg7/frag"), rpg.c().id(), "OG-7V").flight(152, 1.35f, quadDrag(40f, 1350, 0.30f))
                .damagePerKJ(rpg.dpk()).explosive(FRAG, 3f)
                .look(ProjectileLook.Override.NONE.withShape(ProjectileLook.Shape.ROCKET, 590f, 40f)));
        // 4.5 kg complete round; motor = PG-7V thrust / 4.1 kg; Cd estimated.
        add(AmmoVariant.builder(rl("rpg7/thermobaric"), rpg.c().id(), "TBG-7V")
                .flight(110, 4.1f, quadDrag(105f, 4100, 0.50f)).damagePerKJ(rpg.dpk()).explosive(THERMOBARIC, 5f)
                .motor(230, 0.45f)
                .look(ProjectileLook.Override.NONE.withShape(ProjectileLook.Shape.ROCKET, 1100f, 105f)));
        Cal pzf = cal("pzf3", "Panzerfaust 3 rocket", CalibreKind.ROCKET, 110f, 1f, 60f, 60f, 60f, 1200f, 1200f,
                CaseMaterial.NONE, 3900f, 0.3f);
        // 160 m/s launch, 243 peak; burn not published (0.3 s).
        add(pzf.variant("heat", "PzF3 HEAT", 3900, 160, 0.40f).explosive(SHAPED, 3f).armorPen(700).motor(300, 0.3f));
        // SPG-9 recoilless: booster charge in a combustible case; 435 m/s launch, sustainer to ~700 (PG-9V).
        Cal spg9 = cal("73mm_spg9", "SPG-9 73 mm", CalibreKind.ROCKET, 73f, 1f, 73f, 73f, 73f, 1120f, 650f,
                CaseMaterial.COMBUSTIBLE, 4400f, 0.315f);
        add(spg9.variant("heat", "PG-9V", 2600, 435, 0.30f).explosive(SHAPED, 2.5f).armorPen(300).motor(600, 0.45f));
        // No sustainer; 316 m/s, 4.5 km max.
        add(AmmoVariant.builder(rl("73mm_spg9/frag"), spg9.c().id(), "OG-9V")
                .flight(316, 3.7f, quadDrag(73f, 3700, 0.30f)).damagePerKJ(spg9.dpk()).explosive(FRAG, 4f)
                .look(ProjectileLook.Override.NONE.withShape(ProjectileLook.Shape.ROCKET, 700f, 73f)));
    }

    private static void autocannon(Cal c, String he, float heG, float heMs, float blast, String ap, float apG,
                                   float apMs, float apPen, String tp) {
        add(c.variant("he", he, heG, heMs, 0.30f).explosive(STANDARD, blast).tip(TIP_RED)
                .tracer(he.endsWith("-T") ? RED : AmmoVariant.NONE));
        add(c.variant("ap", ap, apG, apMs, 0.28f).armorPen(apPen).blockPen(apPen * 2).tip(TIP_BLACK)
                .tracer(ap.endsWith("-T") ? RED : AmmoVariant.NONE));
        if (tp != null) {
            add(c.variant("tracer", tp, heG, heMs, 0.30f).blockPen(apPen).tracer(RED).tip(TIP_BLUE));
        }
    }

    private static void tank(Cal c, String apfsds, float dartKg, float dartMs, float dartPen, String heat,
                             float heatKg, float heatMs, float heatPen, String he, float heKg, float heMs) {
        float rod = c.c().bulletMm() * 0.22f;
        add(AmmoVariant.builder(rl(c.key() + "/apfsds"), c.c().id(), apfsds)
                .flight(dartMs, dartKg, quadDrag(rod, dartKg * 1000, 0.25f)).damagePerKJ(c.dpk()).armorPen(dartPen)
                .blockPen(300).tracer(RED));
        add(c.variant("heat", heat, heatKg * 1000, heatMs, 0.30f).damagePerKJ(0.06f).explosive(SHAPED, 2.5f)
                .armorPen(heatPen).tip(TIP_YELLOW));
        add(c.variant("he", he, heKg * 1000, heMs, 0.30f).damagePerKJ(0.03f).explosive(STANDARD, 5f)
                .tip(TIP_YELLOW));
    }
}
