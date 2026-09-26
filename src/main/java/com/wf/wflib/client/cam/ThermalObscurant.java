package com.wf.wflib.client.cam;

/**
 * Marker for a {@code PARTICLE_SHEET_TRANSLUCENT} particle a thermal sight cannot see through (smoke screen):
 * heat stencil behind it *= 1 - alpha. Thermal passes draw no particles => the obscurant itself stays invisible.
 */
public interface ThermalObscurant {
}
