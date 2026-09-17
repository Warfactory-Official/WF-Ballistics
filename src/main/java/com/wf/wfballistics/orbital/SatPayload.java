package com.wf.wfballistics.orbital;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/** What a satellite is <em>for</em>. */
public interface SatPayload {

    /** Called once, on the tick the bird reaches orbit. */
    default void onOrbit(ServerLevel level, Satellite self) {
    }

    /** The slow tick. */
    default void tick(ServerLevel level, Satellite self) {
    }

    /**
     * Run a verb that was addressed to this bird and has already cleared the bus: the arity was checked, the link
     * was in place a tick ago, and the issuer was recorded.
     *
     * @return a line to hand back to whoever asked, in the string-in/string-out shape NTM's RoR uses. Return
     *      null for "not my verb" so the core can answer instead of the payload having to know what the core does.
     */
    default String command(ServerLevel level, Satellite self, String verb, String[] args) {
        return null;
    }

    /**
     * @return every verb this payload answers, so a GUI can build itself and a mistyped command fails at parse
     *      rather than silently.
     */
    default List<SatVerb> verbs() {
        return List.of();
    }

    /**
     * @return true while this payload is radiating. Firing an active instrument is an emission, and an
     *      emitting bird can be geolocated without ever being caught by radar: detection costs R⁴, being detected
     *      costs R². The same EMCON asymmetry as on the ground, one tier up.
     */
    default boolean emitting() {
        return false;
    }

    /** Payload state that has to survive a restart. */
    default CompoundTag save() {
        return new CompoundTag();
    }

    default void load(CompoundTag tag) {
    }
}
