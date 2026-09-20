package com.wf.wflib.orbital;

/** Why a bird left the sky. */
public enum DeorbitReason {
    /** Somebody said so: a controlled retirement, and the only clean one. */
    COMMAND,
    /** Shot down, or otherwise destroyed on orbit. The case that should eventually pollute the band. */
    KILLED,
    /** The orbit gave out. */
    DECAYED,
    /** Removed administratively: a debug command, a world edit, or a station that no longer exists. */
    REMOVED
}
