package com.wf.wflib.ammo;

/** {@link #visible}: kind forces a visible look regardless of calibre; full rule in {@link ProjectileLook#resolve}. */
public enum CalibreKind {
    PISTOL("pistol", false),
    RIFLE("rifle", false),
    SHOTSHELL("shotshell", false),
    GRENADE("grenade", true),
    AUTOCANNON("autocannon", true),
    TANK("tank", true),
    ROCKET("rocket", true);

    public final String key;
    final boolean visible;

    CalibreKind(String key, boolean visible) {
        this.key = key;
        this.visible = visible;
    }

    public static CalibreKind byKey(String key) {
        for (CalibreKind k : values()) {
            if (k.key.equals(key)) {
                return k;
            }
        }
        throw new IllegalArgumentException("calibre kind '" + key + "' not one of pistol, rifle, shotshell, grenade,"
                + " autocannon, tank, rocket");
    }
}
