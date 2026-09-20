package com.wf.wflib.recon.scope;

import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.track.IffState;
import com.wf.wflib.recon.track.Track;
import com.wf.wflib.recon.track.TrackPicture;
import com.wf.wflib.recon.track.TrackQuality;

import java.util.ArrayList;
import java.util.List;

/**
 * One network's picture, reduced to what a scope needs to draw it.
 *
 * @param netId which network this is, so a client can key its raster cache on it and twenty screens on one
 *      net share one texture
 * @param range the sensor range the scope is scaled to
 * @param gameTime the tick this belief was published at; a raster is only rebuilt when this changes
 */
public record ScopeFrame(long netId, double originX, double originY, double originZ,
                         double range, long gameTime, List<Blip> blips, List<Event> events) {

    /** Blips beyond this are dropped. */
    public static final int MAX_BLIPS = 128;
    /** Logged events a frame carries. */
    public static final int MAX_EVENTS = 6;

    public static final ScopeFrame EMPTY =
            new ScopeFrame(0L, 0.0, 0.0, 0.0, 1.0, 0L, List.of(), List.of());

    /**
     * One contact, as a scope draws it.
     *
     * @param error the track's error radius, drawn as the blip's size: a scope showing a fuzzy contact as
     *      a sharp dot is lying about the thing that matters most
     * @param quality ordinal into {@link TrackQuality}
     * @param kind ordinal into {@link ContactClass}
     * @param iff ordinal into {@link IffState}
     * @param count the merged-target estimate, so a cell holding sixteen bugs can say so
     * @param age ticks since this track was last actually seen, for phosphor decay. A coasting track
     *      fading is not decoration: it is the display telling the truth about its own staleness
     * @param bands which bands hold this, a bit per {@code Band} ordinal. Sent because a contact held on
     *      radar and seismic together is the one claim nothing in the game can fake, and a display
     *      that could not show it would be hiding the most useful thing the network knows
     * @param hops relay links behind the freshest measurement, so an operator can see which limb of the
     *      grid a contact is coming through, and therefore which one to defend
     */
    public record Blip(float x, float y, float z, float error, float confidence,
                       byte quality, byte kind, byte iff, short count, short age,
                       byte bands, byte hops) {

        public TrackQuality qualityValue() {
            return TrackQuality.values()[Math.floorMod(quality, TrackQuality.values().length)];
        }

        /**
         * @return true if more than one band holds this contact.
         */
        public boolean corroborated() {
            return Integer.bitCount(bands & 0xFF) > 1;
        }

        /**
         * @return a compact band tag such as {@code "RS"} for radar and seismic, or {@code "-"} for none.
         */
        public String bandTag() {
            StringBuilder out = new StringBuilder(3);
            com.wf.wflib.recon.Band[] all = com.wf.wflib.recon.Band.VALUES;
            for (int i = 0; i < all.length; i++) {
                if ((bands & (1 << i)) != 0) {
                    out.append(all[i].tag());
                }
            }
            return out.isEmpty() ? "-" : out.toString();
        }

        public ContactClass kindValue() {
            return ContactClass.values()[Math.floorMod(kind, ContactClass.values().length)];
        }

        public IffState iffValue() {
            return IffState.values()[Math.floorMod(iff, IffState.values().length)];
        }
    }

    /**
     * One logged explosion, as a scope shows it.
     *
     * @param error blocks. Vast for a one-station fix (a lone geophone has no bearing at all), and tens
     *      of blocks for an array. The ring drawn from it is the honest answer.
     * @param power the estimated blast size in the units the explosion framework uses, where TNT is 4.
     * @param ageSeconds seconds since it went off, which is what a readout wants; a tick count would be a
     *      number nobody converts in their head.
     * @param stations geophones that heard it. The field that says how much of the rest to believe.
     */
    public record Event(float x, float y, float z, float error, float power,
                        int ageSeconds, byte stations, boolean subsurface) {

        /**
         * @return whether this fix pins a direction at all, seen from the scope's own origin. One station
         *      never does, and a display that drew a bearing anyway would be inventing the one thing this band
         *      cannot measure.
         */
        public boolean hasBearing(double ox, double oy, double oz) {
            double dx = x - ox;
            double dy = y - oy;
            double dz = z - oz;
            return error * error < dx * dx + dy * dy + dz * dz;
        }
    }

    /** Build a frame from a picture, with no event history: the shape a sender that is not a hub uses. */
    public static ScopeFrame of(long netId, double originX, double originY, double originZ,
                                double range, TrackPicture picture) {
        return of(netId, originX, originY, originZ, range, picture, List.of(), picture.gameTime());
    }

    /**
     * Build a frame from a picture and a hub's event log.
     *
     * @param logged the newest fixes first, as {@code SeismicLog.recent} returns them
     * @param now the tick the ages are measured against, which is the level's clock rather than the
     *      picture's: a picture published a sweep ago should not make every event look a sweep
     *      younger than it is
     */
    public static ScopeFrame of(long netId, double originX, double originY, double originZ,
                                double range, TrackPicture picture,
                                List<com.wf.wflib.recon.event.SeismicFix> logged, long now) {
        List<Track> tracks = picture.tracks();
        List<Blip> blips = new ArrayList<>(Math.min(tracks.size(), MAX_BLIPS));
        for (int i = 0; i < tracks.size() && blips.size() < MAX_BLIPS; i++) {
            Track track = tracks.get(i);
            long age = Math.max(0L, picture.gameTime() - track.lastSeenTick());
            blips.add(new Blip((float) track.x(), (float) track.y(), (float) track.z(),
                    (float) track.errorRadius(), track.confidence(),
                    (byte) track.quality().ordinal(), (byte) track.guess().ordinal(),
                    (byte) track.iff().ordinal(),
                    (short) Math.min(Short.MAX_VALUE, track.countEstimate()),
                    (short) Math.min(Short.MAX_VALUE, age),
                    (byte) track.bandMask(), (byte) Math.max(0, Math.min(127, track.hops()))));
        }

        List<Event> events = new ArrayList<>(Math.min(logged.size(), MAX_EVENTS));
        for (int i = 0; i < logged.size() && events.size() < MAX_EVENTS; i++) {
            com.wf.wflib.recon.event.SeismicFix fix = logged.get(i);
            events.add(new Event((float) fix.x(), (float) fix.y(), (float) fix.z(),
                    (float) fix.positionError(), (float) fix.power(),
                    (int) Math.max(0L, (now - fix.gameTime()) / 20L),
                    (byte) Math.min(127, fix.stations()), fix.subsurface()));
        }
        return new ScopeFrame(netId, originX, originY, originZ, range, picture.gameTime(),
                List.copyOf(blips), List.copyOf(events));
    }
}
