package com.wf.wflib.ammo;

/** Cartridge case look; {@code rgb} = model colour. */
public enum CaseMaterial {
    BRASS("brass", 0xC8A24A),
    STEEL("steel", 0x5F6B55),
    ALUMINIUM("aluminium", 0xB9BDC2),
    POLYMER("polymer", 0xB02020),
    COMBUSTIBLE("combustible", 0x9C8B6A),
    NONE("none", 0);

    public final String key;
    public final int rgb;

    CaseMaterial(String key, int rgb) {
        this.key = key;
        this.rgb = rgb;
    }

    public static CaseMaterial byKey(String key) {
        for (CaseMaterial m : values()) {
            if (m.key.equals(key)) {
                return m;
            }
        }
        throw new IllegalArgumentException("case material '" + key
                + "' not one of brass, steel, aluminium, polymer, combustible, none");
    }
}
