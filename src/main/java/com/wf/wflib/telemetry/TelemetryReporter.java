package com.wf.wflib.telemetry;

import org.jetbrains.annotations.Nullable;

/** An entity with a datalink. Implemented by entity classes; tracked from level join to leave. Server side. */
public interface TelemetryReporter {

    /** Null = not transmitting now. */
    @Nullable
    Telemetry datalink();
}
