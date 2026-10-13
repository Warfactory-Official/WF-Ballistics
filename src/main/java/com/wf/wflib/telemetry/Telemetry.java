package com.wf.wflib.telemetry;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One reporter's datalink identity this tick. Receivers: {@code operator}, and PDA carriers whose side
 * ({@link com.wf.wflib.recon.ReconOwners#owningEntity}) is {@code team} or a WarForge ally of it.
 */
public record Telemetry(TelemetryKind kind, String label, @Nullable UUID team, @Nullable UUID operator) {
}
