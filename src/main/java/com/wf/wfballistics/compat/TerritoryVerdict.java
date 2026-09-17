package com.wf.wfballistics.compat;

/** Whether a faction may have drones change blocks somewhere, and if not, why not. */
public enum TerritoryVerdict {
    /**
     * Nobody's, ours, or a friend's.
     */
    ALLOWED(null),
    /**
     * Inside the inner zone of an active siege. The most emphatic no: this is a battle in progress.
     */
    SIEGE("a siege is in progress there"),
    /**
     * Inside a siege's outer battle zone, or a chunk conquered and not yet released.
     */
    WAR_ZONE("it is a war zone"),
    /**
     * Admin-protected ground.
     */
    SAFE_ZONE("it is a safe zone"),
    /**
     * Claimed by a faction that is neither us, an ally, nor under truce.
     */
    FOREIGN_CLAIM("it is claimed by another faction");

    private final String reason;

    TerritoryVerdict(String reason) {
        this.reason = reason;
    }

    public boolean allowed() {
        return this == ALLOWED;
    }

    /**
     * @return why this was refused, phrased to follow "cannot build there because…", or null when allowed.
     */
    public String reason() {
        return this.reason;
    }
}
