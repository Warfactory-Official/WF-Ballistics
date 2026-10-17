package com.wf.wflib.ammo;

/**
 * Spent case a gun ejects; model {@code wflib:models/casing/<key>.gltf} ({@code tools/casings.py}). {@code CANNON}:
 * 88x571R shape, drawn scaled by case length.
 */
public enum Casing {
    NONE("none"), PISTOL("pistol"), RIFLE("rifle"), LARGE("large"), SHOTSHELL("shotshell"), CANNON("cannon");

    public final String key;

    Casing(String key) {
        this.key = key;
    }

    public static Casing byKey(String key) {
        for (Casing c : values()) {
            if (c.key.equals(key)) {
                return c;
            }
        }
        throw new IllegalArgumentException("casing '" + key + "' not one of none, pistol, rifle, large, shotshell, cannon");
    }
}
