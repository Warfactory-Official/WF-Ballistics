package com.wf.wflib.radio.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Emitter;
import com.wf.wflib.radio.RadioVoice;
import net.minecraft.Util;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

/**
 * One carrier's set at the station playhead. Aboard: stereo, unmuffled. Off board: mono at the carrier, gain x
 * {@link RadioVoice#outside}, hull voices low-passed. {@code wflib:radio} (sounds.json, stream) only routes to
 * {@link #getStream}.
 */
final class RadioEmitter extends Emitter {
    private static final ResourceLocation EVENT = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "radio");

    private final Entity carrier;
    private final RadioVoice voice;
    private final boolean aboard;
    private final RadioCatalog.Station station;
    private final long sample;

    RadioEmitter(Entity carrier, RadioVoice voice, boolean aboard, RadioCatalog.Station station, long sample) {
        super(SoundSpec.of(EVENT, 1, voice.range()), AudioCategory.RADIO, RandomSource.create(), false);
        this.carrier = carrier;
        this.voice = voice;
        this.aboard = aboard;
        this.station = station;
        this.sample = sample;
    }

    @Override
    protected Vec3 locate() {
        return carrier.isRemoved() ? null : carrier.getBoundingBox().getCenter();
    }

    @Override
    protected float muffle() {
        return aboard ? 0 : super.muffle();
    }

    @Override
    public boolean refresh() {
        if (!super.refresh()) {
            return false;
        }
        if (!aboard) {
            volume *= voice.outside();
            if (voice.hull()) {
                low *= 0.8F;
                high *= 0.2F;
            }
        }
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new StationStream(station, sample, aboard ? 2 : 1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor());
    }
}
