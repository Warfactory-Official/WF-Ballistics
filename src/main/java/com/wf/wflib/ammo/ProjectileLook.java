package com.wf.wflib.ammo;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * How a round in flight is drawn. {@code model} null => procedural {@code shape} from {@code lengthMm} x
 * {@code diameterMm}; else glTF, nose +Z, 1 unit = 1 block, drawn as authored. Colours rgb; {@code tip} -1 = body.
 * {@code spinRps}: drawn roll rate (not the rifling's). {@code trail}: smoke trail (GemRender particles);
 * {@code flame}: emissive cone at the nozzle while the motor burns (motor only).
 */
public record ProjectileLook(boolean visible, Shape shape, @Nullable ResourceLocation model, float lengthMm,
                             float diameterMm, int body, int tip, int band, float spinRps, boolean trail,
                             boolean flame) {

    public static final int OLIVE = 0x4B5320;
    public static final int COPPER = 0xB87333;
    public static final int STEEL = 0x6E7378;
    public static final int DARK = 0x2E3033;
    public static final int ALUMINIUM = 0xB9BDC2;

    public enum Shape {
        /** Boat tail + ogive. */
        BULLET("bullet"),
        /** Spun shell: driving band + ogive + fuze. */
        SHELL("shell"),
        /** Short spun grenade, rounded nose. */
        GRENADE("grenade"),
        /** Long rod + tail fins (APFSDS). */
        DART("dart"),
        /** Fin-stabilised shell with a standoff probe (HEAT). */
        FINNED("finned"),
        /** Warhead + motor tube + fins. */
        ROCKET("rocket");

        public final String key;

        Shape(String key) {
            this.key = key;
        }

        public static Shape byKey(String key) {
            for (Shape s : values()) {
                if (s.key.equals(key)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("look shape '" + key
                    + "' not one of bullet, shell, grenade, dart, finned, rocket");
        }
    }

    /** Variant overrides; null field => default. */
    public record Override(@Nullable Boolean visible, @Nullable Shape shape, @Nullable ResourceLocation model,
                           @Nullable Float lengthMm, @Nullable Float diameterMm, @Nullable Integer body,
                           @Nullable Integer band, @Nullable Float spinRps, @Nullable Boolean trail,
                           @Nullable Boolean flame) {
        public static final Override NONE = new Override(null, null, null, null, null, null, null, null, null, null);

        public static Override visible(boolean v) {
            return new Override(v, null, null, null, null, null, null, null, null, null);
        }

        public Override withShape(Shape s, @Nullable Float lengthMm, @Nullable Float diameterMm) {
            return new Override(visible, s, model, lengthMm, diameterMm, body, band, spinRps, trail, flame);
        }
    }

    /** Visible: calibre kind grenade/autocannon/tank/rocket, bullet >= 20 mm, or a motor. */
    public static ProjectileLook resolve(Calibre c, AmmoVariant v) {
        Override o = v.look();
        boolean explosive = v.explosive() != null;
        boolean shaped = explosive && v.explosive().warhead().getPath().equals("shaped_charge");
        Shape shape = o.shape() != null ? o.shape() : switch (c.kind()) {
            case GRENADE -> Shape.GRENADE;
            case AUTOCANNON -> Shape.SHELL;
            case TANK -> shaped ? Shape.FINNED : explosive ? Shape.SHELL : Shape.DART;
            case ROCKET -> Shape.ROCKET;
            default -> v.motor() != null ? Shape.ROCKET : Shape.BULLET;
        };
        float length = o.lengthMm() != null ? o.lengthMm()
                : shape == Shape.DART ? c.bulletMm() * 6.5f : c.projectileLengthMm();
        float diameter = o.diameterMm() != null ? o.diameterMm()
                : shape == Shape.DART ? c.bulletMm() * 0.22f : c.bulletMm();
        int body = o.body() != null ? o.body() : switch (shape) {
            case BULLET -> COPPER;
            case DART -> DARK;
            case GRENADE, ROCKET -> OLIVE;
            case SHELL, FINNED -> explosive ? OLIVE : v.armorPenMm() > 0 ? DARK : STEEL;
        };
        int band = o.band() != null ? o.band() : shape == Shape.DART || shape == Shape.FINNED || shape == Shape.ROCKET
                ? ALUMINIUM : COPPER;
        float spin = o.spinRps() != null ? o.spinRps() : switch (shape) {
            case BULLET -> 6f;
            case SHELL -> 4f;
            case GRENADE -> 3f;
            case ROCKET -> 2f;
            case DART, FINNED -> 1f;
        };
        boolean visible = o.visible() != null ? o.visible()
                : c.kind().visible || c.bulletMm() >= 20 || v.motor() != null;
        boolean trail = o.trail() != null ? o.trail() : v.motor() != null;
        if (Boolean.TRUE.equals(o.flame()) && v.motor() == null) {
            throw new IllegalArgumentException("look flame without a motor");
        }
        boolean flame = v.motor() != null && !Boolean.FALSE.equals(o.flame());
        return new ProjectileLook(visible, shape, o.model(), length, diameter, body, v.tip(), band, spin, trail,
                flame);
    }
}
