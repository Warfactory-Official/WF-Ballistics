package com.wf.wflib.recon.alert;

import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.track.TrackId;

/** Something happened that a player or a redstone line should hear about. */
public record Alert(TrackId track, AlertTier tier, EngagementAuthority authority,
                    double x, double y, double z, ContactClass kind, long gameTime) {
}
