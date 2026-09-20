package com.wf.wflib.recon.alert;

import com.wf.wflib.recon.track.IffState;
import com.wf.wflib.recon.track.Track;
import com.wf.wflib.recon.track.TrackQuality;

/** What the network may do about a track with nobody watching. */
public enum EngagementAuthority {
    /**
     * A single weak return. Write it down and do nothing.
     */
    LOG_ONLY,
    /** A confirmed track that has not identified itself. */
    ALARM,
    /**
     * Confirmed, and something that can tell friend from foe has said foe.
     */
    WEAPONS_RELEASE;

    public boolean permits(EngagementAuthority required) {
        return ordinal() >= required.ordinal();
    }

    /** The most this track earns on its own. */
    public static EngagementAuthority of(Track track) {
        if (track.iff() == IffState.FRIENDLY) {
            return LOG_ONLY;
        }
        if (!track.quality().atLeast(TrackQuality.CONFIRMED)) {
            return LOG_ONLY;
        }
        return track.iff() == IffState.HOSTILE ? WEAPONS_RELEASE : ALARM;
    }
}
