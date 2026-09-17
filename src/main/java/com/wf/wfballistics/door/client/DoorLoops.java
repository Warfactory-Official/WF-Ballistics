package com.wf.wfballistics.door.client;

import com.wf.wfballistics.door.DoorBlockEntity;
import com.wf.wfballistics.door.DoorSounds;
import com.wf.wfballistics.door.DoorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.jetbrains.annotations.Nullable;

/** The motor a door runs while it moves. */
public final class DoorLoops {

    private DoorLoops() {
    }

    /** Called from the block entity when a packet changes its state. */
    public static void onStateChanged(DoorBlockEntity door, DoorState from, DoorState to) {
        if (!to.moving()) {
            return;
        }
        DoorSounds.Set sounds = door.type().sounds();
        start(door, to == DoorState.OPENING ? sounds.loopOpen() : sounds.loopClose());
        start(door, sounds.loop2());
    }

    private static void start(DoorBlockEntity door, @Nullable DeferredHolder<SoundEvent, SoundEvent> sound) {
        if (sound == null) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(new Loop(door, sound.get()));
    }

    private static final class Loop extends AbstractTickableSoundInstance {

        private final DoorBlockEntity door;

        private Loop(DoorBlockEntity door, SoundEvent event) {
            super(event, SoundSource.BLOCKS, RandomSource.create());
            this.door = door;
            this.looping = true;
            this.delay = 0;
            this.volume = door.type().soundVolume();
            this.pitch = 1.0f;
            this.x = door.getBlockPos().getX() + 0.5;
            this.y = door.getBlockPos().getY() + 0.5;
            this.z = door.getBlockPos().getZ() + 0.5;
        }

        @Override
        public void tick() {
            if (door.isRemoved() || !door.state().moving()) {
                stop();
            }
        }
    }
}
