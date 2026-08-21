package com.wf.wfballistics.exchange;

/**
 * How a delivery hides, or doesn't, where it came from.
 *
 * <p>The three modes trade battery for deniability, and they answer three different questions an observer at
 * the drop point might be trying to answer.
 */
public enum ExchangeMode {

    /**
     * Straight there, drop, straight home. Cheapest, and tells anyone watching the drop exactly which way the
     * sender lives.
     */
    DIRECT("Direct"),

    /**
     * Same destination, but approached from a bearing chosen to be a poor guide back to the sender, and left
     * on another one. Costs whatever the dogleg costs.
     *
     * <p>The approach bearing is <em>weighted</em> away from home rather than forbidden from it: see
     * {@link Obfuscation#approachBearing}. An observer who learns that drones never arrive from the north has
     * learned something; one who learns they rarely do has not.
     */
    INDIRECT("Indirect"),

    /**
     * The two stations never meet and neither drone ever visits the other's station. Both fly to a neutral
     * point arranged between them, drop, and leave; each then collects what the other left. Watching the
     * drop tells you about the rendezvous and nothing about either endpoint.
     */
    HANDSHAKE("Handshake");

    private final String label;

    ExchangeMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * @return true if this mission is trying to hide. Classified missions keep no telemetry, report no
     * distances, and are redacted from the drone listing: anything that would let the information be read
     * off a screen rather than off the sky.
     */
    public boolean classified() {
        return this != DIRECT;
    }

    /**
     * @return true if the destination is resolved server-side from a station code rather than typed in.
     */
    public boolean resolvesDestination() {
        return this == HANDSHAKE;
    }

    public ExchangeMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static ExchangeMode byName(String name) {
        for (ExchangeMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return DIRECT;
    }
}
