package com.wf.wfballistics.orbital;

/**
 * One thing a satellite can be told or asked, described well enough for a GUI to build itself.
 *
 * @param name the verb, lower case. Addressed to a callsign: {@code KH-1!park:100:-2400}.
 * @param arity exactly how many arguments it takes. Checked before anything runs.
 * @param returns a word describing what comes back, for a UI to render: {@code "ok"}, {@code "number"},
 *      {@code "coords"}.
 * @param pure true if this only reads. The split matters: a panel polling {@code canfire} every tick must
 *      not be able to change anything, and a pure verb also skips the one-tick bus delay because
 *      there is nothing to sequence.
 * @param describe one line of help.
 */
public record SatVerb(String name, int arity, String returns, boolean pure, String describe) {

    /** A verb that does something. Goes through the bus, so it lands a tick later and needs contact. */
    public static SatVerb action(String name, int arity, String returns, String describe) {
        return new SatVerb(name, arity, returns, false, describe);
    }

    /** A verb that only reads. Answered immediately, and safe to poll. */
    public static SatVerb value(String name, String returns, String describe) {
        return new SatVerb(name, 0, returns, true, describe);
    }
}
