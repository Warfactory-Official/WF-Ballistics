package com.wf.wflib.audio.client;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundSchedulerTest {
    private static SoundInstance sound() {
        return new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("wflib", "test")), SoundSource.MASTER, 1, 1,
                RandomSource.create(0), 0, 0, 0);
    }

    /** Full queue drops the latest due, never the next to play; cancel dequeues an unstarted sound. */
    @Test
    void queueKeepsTheSoonest() {
        SoundScheduler.reset();
        for (int i = 0; i < SoundScheduler.MAX_QUEUED; i++) {
            SoundScheduler.schedule(sound(), 10 + i, 0, null);
        }
        SoundInstance first = sound();
        SoundScheduler.schedule(first, 1, 0, null);
        assertEquals(SoundScheduler.MAX_QUEUED, SoundScheduler.QUEUE.size());
        assertSame(first, SoundScheduler.QUEUE.peek().sound(), "sooner sound queued");
        assertTrue(SoundScheduler.QUEUE.stream().noneMatch(d -> d.at() == 10 + SoundScheduler.MAX_QUEUED - 1),
                "latest dropped");
        SoundScheduler.schedule(sound(), 1e6, 0, null);
        assertTrue(SoundScheduler.QUEUE.stream().noneMatch(d -> d.at() == 1e6), "later than all: not queued");
        assertTrue(SoundScheduler.cancel(first));
        assertFalse(SoundScheduler.cancel(first), "cancelled once");
        assertEquals(10, SoundScheduler.QUEUE.peek().at());
        SoundScheduler.reset();
    }

    @Test
    void travelTimeIsDistanceOverC() {
        SoundScheduler.reset();
        SoundScheduler.schedule(sound(), 2, 343, null);
        assertEquals(3, SoundScheduler.QUEUE.peek().at(), 1e-12);
        SoundScheduler.reset();
    }
}
