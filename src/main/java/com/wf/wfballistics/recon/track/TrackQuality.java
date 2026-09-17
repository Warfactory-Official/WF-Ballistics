package com.wf.wfballistics.recon.track;

/** How much a track is worth acting on. */
public enum TrackQuality {
    /**
     * One return. Could be anything, including nothing. Log it.
     */
    TENTATIVE,
    /**
     * Seen repeatedly in the place the last look predicted. Worth an alarm.
     */
    CONFIRMED,
    /**
     * Held long enough that its velocity is trustworthy. Worth shooting at.
     */
    FIRM;

    public boolean atLeast(TrackQuality other) {
        return ordinal() >= other.ordinal();
    }

    /** The best a track may be called when its measurements crossed this many relay links. */
    public static TrackQuality capFor(int hops) {
        if (hops <= 1) {
            return FIRM;
        }
        return hops == 2 ? CONFIRMED : TENTATIVE;
    }

    /**
     * @return the lower of the two. Named rather than inlined because {@code compareTo} on an enum reads as an
     *      ordering question and this is a ceiling.
     */
    public TrackQuality cappedAt(TrackQuality ceiling) {
        return ordinal() <= ceiling.ordinal() ? this : ceiling;
    }
}
