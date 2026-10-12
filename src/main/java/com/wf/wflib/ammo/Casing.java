package com.wf.wflib.ammo;

/** Spent case a gun ejects. */
public enum Casing {
    NONE("none"), PISTOL("pistol"), RIFLE("rifle"), LARGE("large"), SHOTSHELL("shotshell");

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
        throw new IllegalArgumentException("casing '" + key + "' not one of none, pistol, rifle, large, shotshell");
    }
}
