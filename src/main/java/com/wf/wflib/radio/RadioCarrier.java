package com.wf.wflib.radio;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Entity carrying a radio set. Station = {@code <ns>:<folder>} under {@code assets/<ns>/radio/}; "" = off.
 * Synced to clients by the implementor.
 */
public interface RadioCarrier {

    /**
     * Station id gate: "" or {@code <ns>:<folder>}, one path segment, no dots, <= {@link RadioTunePacket#MAX_STATION}.
     * An id is only ever a catalogue key: never a file path.
     */
    static boolean validStation(String id) {
        if (id.isEmpty()) {
            return true;
        }
        ResourceLocation rl = id.length() <= RadioTunePacket.MAX_STATION ? ResourceLocation.tryParse(id) : null;
        return rl != null && rl.getPath().matches("[a-z0-9_-]+");
    }

    /** Null = no set fitted now. */
    @Nullable
    RadioVoice radioVoice();

    String radioStation();

    /** Server; only after {@link RadioTunePacket} checks; {@code station} passes {@link #validStation}. */
    void tuneRadio(String station);
}
