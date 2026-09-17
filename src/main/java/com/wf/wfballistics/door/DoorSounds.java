package com.wf.wfballistics.door;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Every sound a door makes. */
public final class DoorSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, WFBallistics.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> TRANSITION_SEAL_OPEN = door("transition_seal_open");
    public static final DeferredHolder<SoundEvent, SoundEvent> ALARM = door("alarm6");
    public static final DeferredHolder<SoundEvent, SoundEvent> GARAGE_MOVE = door("garage_move");
    public static final DeferredHolder<SoundEvent, SoundEvent> GARAGE_STOP = door("garage_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> LEVER = door("lever1");
    public static final DeferredHolder<SoundEvent, SoundEvent> QE_OPENED = door("doorslide_opened1");
    public static final DeferredHolder<SoundEvent, SoundEvent> QE_OPENING = door("doorslide_opening1");
    public static final DeferredHolder<SoundEvent, SoundEvent> QE_SHUT = door("doorshut_1");
    public static final DeferredHolder<SoundEvent, SoundEvent> SLIDING_OPENED = door("sliding_door_opened");
    public static final DeferredHolder<SoundEvent, SoundEvent> SLIDING_OPENING = door("sliding_door_opening");
    public static final DeferredHolder<SoundEvent, SoundEvent> SLIDING_SHUT = door("sliding_door_shut");
    public static final DeferredHolder<SoundEvent, SoundEvent> SEAL_MOVE = door("doormove2");
    public static final DeferredHolder<SoundEvent, SoundEvent> SEAL_STOP = door("metal_stop1");
    public static final DeferredHolder<SoundEvent, SoundEvent> WGH_START = door("wgh_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> WGH_STOP = door("wgh_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> WGH_BIG_START = door("door_wgh_big_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> WGH_BIG_STOP = door("door_wgh_big_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> MOTOR_START = door("motor_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> MOTOR_STOP = door("motor_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> VAULT_SCRAPE = door("vault_scrape");
    public static final DeferredHolder<SoundEvent, SoundEvent> VAULT_THUD = door("vault_thud");

    private DoorSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> door(String name) {
        String id = "door." + name;
        return SOUND_EVENTS.register(id, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, id)));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }

    /**
     * The four one-shots and two loops a door plays, as a value.
     *
     * @param loop2 a second loop played alongside {@code loopOpen}; the fire door's alarm
     */
    public record Set(DeferredHolder<SoundEvent, SoundEvent> startOpen,
                      DeferredHolder<SoundEvent, SoundEvent> loopOpen,
                      DeferredHolder<SoundEvent, SoundEvent> endOpen,
                      DeferredHolder<SoundEvent, SoundEvent> startClose,
                      DeferredHolder<SoundEvent, SoundEvent> loopClose,
                      DeferredHolder<SoundEvent, SoundEvent> endClose,
                      DeferredHolder<SoundEvent, SoundEvent> loop2,
                      float volume) {

        public static final Set NONE = new Set(null, null, null, null, null, null, null, 1.0f);

        /** One motor: the same start, loop and stop whichever way the door is going. */
        public static Set motor(DeferredHolder<SoundEvent, SoundEvent> loop,
                                DeferredHolder<SoundEvent, SoundEvent> end, float volume) {
            return new Set(null, loop, end, null, loop, end, null, volume);
        }

        public Set withStart(DeferredHolder<SoundEvent, SoundEvent> start) {
            return new Set(start, loopOpen, endOpen, start, loopClose, endClose, loop2, volume);
        }

        public Set withLoop2(DeferredHolder<SoundEvent, SoundEvent> second) {
            return new Set(startOpen, loopOpen, endOpen, startClose, loopClose, endClose, second, volume);
        }
    }
}
