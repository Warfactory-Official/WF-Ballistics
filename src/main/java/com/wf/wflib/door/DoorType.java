package com.wf.wflib.door;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** The doors, as a table. */
public enum DoorType {

    /** The Black Mesa iris. Twenty-four seconds, twenty-six blocks wide, and the only imported clip. */
    TRANSITION_SEAL("transition_seal", 480, 1, 0,
            new int[] {23, 0, 0, 0, 13, 12},
            new int[][] {{-9, 2, 0, 20, 20, 1}},
            DoorSounds.Set.NONE.withStart(DoorSounds.TRANSITION_SEAL_OPEN)) {
        @Override
        public float soundVolume() {
            return 6.0f;
        }
    },

    /** A bank vault: the plug pulls clear of the frame, then rolls aside on its rail. */
    VAULT_DOOR("vault_door", 120, 7, 0,
            new int[] {4, 0, 0, 0, 2, 2},
            new int[][] {{-1, 1, 0, 3, 3, 2}},
            DoorSounds.Set.NONE) {
        @Override
        public int[][] extraDimensions() {
            return new int[][] {{0, 0, 1, -1, 2, 2}};
        }

        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            return !open || y == 0 ? FULL : super.blockBound(x, y, z, open, forCollision);
        }

        @Override
        public void onTick(DoorBlockEntity door, Level level) {
            int ticks = door.openTicks();
            DoorState state = door.state();
            if (state == DoorState.OPENING && ticks == 0 || state == DoorState.CLOSING && ticks == 30) {
                door.play(level, DoorSounds.VAULT_SCRAPE, 1.0f);
            }
            if (state.moving() && ticks >= 45 && ticks <= 115 && (ticks - 45) % 10 == 0) {
                door.play(level, DoorSounds.VAULT_THUD, 1.0f);
            }
        }
    },

    /** Two leaves that part sideways behind a rising shutter, with an alarm while it moves. */
    FIRE_DOOR("fire_door", 160, 5, 0,
            new int[] {2, 0, 0, 0, 2, 1},
            new int[][] {{-1, 0, 0, 3, 4, 1}},
            DoorSounds.Set.motor(DoorSounds.WGH_START, DoorSounds.WGH_STOP, 2.0f)
                    .withLoop2(DoorSounds.ALARM)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (!open) {
                return FULL;
            }
            if (z == 1) {
                return new AABB(0.5, 0, 0, 1, 1, 1);
            }
            if (z == -2) {
                return new AABB(0, 0, 0, 0.5, 1, 1);
            }
            if (y > 1) {
                return new AABB(0, 0.75, 0, 1, 1, 1);
            }
            if (y == 0) {
                return new AABB(0, 0, 0, 1, forCollision ? 0 : 0.1, 1);
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** The sliding blast door: locks turn, then the leaf lifts. Just over a second, and the fastest big one. */
    SLIDING_BLAST_DOOR("sliding_blast_door", 24, 1, 0,
            new int[] {3, 0, 0, 0, 3, 3},
            new int[][] {{-2, 0, 0, 4, 5, 1}},
            new DoorSounds.Set(null, DoorSounds.SLIDING_OPENING, DoorSounds.SLIDING_OPENED,
                    null, DoorSounds.SLIDING_OPENING, DoorSounds.SLIDING_SHUT,
                    DoorSounds.SLIDING_OPENING, 2.0f)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (open) {
                if (y == 3) {
                    return new AABB(0, 0.5, 0, 1, 1, 1);
                }
                if (y == 0) {
                    return new AABB(0, 0, 0, 1, forCollision ? 0 : 0.08, 1);
                }
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** A one-block pressure seal that sinks into the floor. The smallest door in the table. */
    SLIDING_SEAL_DOOR("sliding_seal_door", 20, 1, 0,
            new int[] {1, 0, 0, 0, 0, 0},
            new int[][] {{0, 0, 0, 1, 2, 2}},
            DoorSounds.Set.motor(null, DoorSounds.SEAL_STOP, 2.0f).withStart(DoorSounds.SEAL_MOVE)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            return forCollision && open ? EMPTY : new AABB(0, 0, 0.75, 1, 1, 1);
        }
    },

    /** A shutter on a garage motor, five wide. */
    SECURE_ACCESS_DOOR("secure_access_door", 120, 4, 0,
            new int[] {4, 0, 0, 0, 2, 2},
            new int[][] {{-2, 1, 0, 4, 5, 1}},
            DoorSounds.Set.motor(DoorSounds.GARAGE_MOVE, DoorSounds.GARAGE_STOP, 2.0f)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (!open) {
                return y > 0 ? new AABB(0, 0, 0.375, 1, 1, 0.625) : FULL;
            }
            if (y == 1) {
                return new AABB(0, 0, 0, 1, forCollision ? 0 : 0.0625, 1);
            }
            if (y == 4) {
                return new AABB(0, 0.5, 0.15, 1, 1, 0.85);
            }
            if (y == 0) {
                return FULL;
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** Two half-discs that part sideways into the wall. Two open ranges, one each way. */
    ROUND_AIRLOCK_DOOR("round_airlock_door", 60, 3, 0,
            new int[] {3, 0, 0, 0, 2, 1},
            new int[][] {{0, 0, 0, -2, 4, 2}, {0, 0, 0, 3, 4, 2}},
            DoorSounds.Set.motor(DoorSounds.GARAGE_MOVE, DoorSounds.GARAGE_STOP, 2.0f)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (!open) {
                return super.blockBound(x, y, z, open, forCollision);
            }
            if (z == 1) {
                return new AABB(0.4, 0, 0, 1, 1, 1);
            }
            if (z == -2) {
                return new AABB(0, 0, 0, 0.6, 1, 1);
            }
            if (y == 3) {
                return new AABB(0, 0.5, 0, 1, 1, 1);
            }
            if (y == 0) {
                return new AABB(0, 0, 0, 1, forCollision ? 0 : 0.0625, 1);
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** Half a second, two leaves, one block of doorway. The one to use where a door is just a door. */
    QE_SLIDING_DOOR("qe_sliding_door", 10, 1, 0,
            new int[] {1, 0, 0, 0, 1, 0},
            new int[][] {{0, 0, 0, 2, 2, 2}},
            new DoorSounds.Set(null, DoorSounds.QE_OPENING, DoorSounds.QE_OPENED,
                    null, DoorSounds.QE_OPENING, DoorSounds.QE_SHUT, null, 2.0f)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (forCollision && open) {
                return z == 0
                        ? new AABB(0.875, 0, 0.8125, 1, 1, 1)
                        : new AABB(0, 0, 0.8125, 0.125, 1, 1);
            }
            return new AABB(0, 0, 0.8125, 1, 1, 1);
        }
    },

    /** A containment shutter that lifts. Eight seconds for two blocks of doorway. */
    QE_CONTAINMENT("qe_containment", 160, 3, 0,
            new int[] {2, 0, 0, 0, 1, 1},
            new int[][] {{-1, 0, 0, 3, 3, 1}},
            DoorSounds.Set.motor(DoorSounds.WGH_START, DoorSounds.WGH_STOP, 2.0f)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (!open) {
                return new AABB(0, 0, 0.5, 1, 1, 1);
            }
            if (y > 1) {
                return new AABB(0, 0.25, 0.5, 1, 1, 1);
            }
            if (y == 0) {
                return new AABB(0, 0, 0.5, 1, forCollision ? 0 : 0.125, 1);
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** A bulkhead: the dogs spin off, then the leaf swings. Its doorway clears late, at tick 35. */
    WATER_DOOR("water_door", 60, 2, 0,
            new int[] {2, 0, 0, 0, 1, 1},
            new int[][] {{1, 0, 0, -3, 3, 2}},
            new DoorSounds.Set(DoorSounds.LEVER, DoorSounds.WGH_BIG_START, DoorSounds.WGH_BIG_STOP,
                    null, DoorSounds.WGH_BIG_START, DoorSounds.LEVER, null, 2.0f)) {
        @Override
        public float rangeOpenTime(int ticks, int index) {
            return normTime(ticks, 35, 40);
        }

        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (!open) {
                return new AABB(0, 0, 0.75, 1, 1, 1);
            }
            if (y > 1) {
                return new AABB(0, 0.85, 0.75, 1, 1, 1);
            }
            if (y == 0) {
                return new AABB(0, 0, 0.75, 1, forCollision ? 0 : 0.15, 1);
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** A 5x5 pad in the ground with a hatch that lifts and folds back. */
    SILO_HATCH("silo_hatch", 60, 1, 2,
            new int[] {0, 0, 2, 2, 2, 2},
            new int[][] {{1, 0, 1, -3, 3, 0}, {0, 0, 1, -3, 3, 0}, {-1, 0, 1, -3, 3, 0}},
            DoorSounds.Set.motor(DoorSounds.WGH_BIG_START, DoorSounds.WGH_BIG_STOP, 2.0f)) {
        @Override
        public float rangeOpenTime(int ticks, int index) {
            return normTime(ticks, 20, 20);
        }
    },

    /** The same hatch at 7x7. */
    SILO_HATCH_LARGE("silo_hatch_large", 60, 1, 3,
            new int[] {0, 0, 3, 3, 3, 3},
            new int[][] {{2, 0, 1, -3, 3, 0}, {1, 0, 2, -5, 3, 0}, {0, 0, 2, -5, 3, 0},
                    {-1, 0, 2, -5, 3, 0}, {-2, 0, 1, -3, 3, 0}},
            DoorSounds.Set.motor(DoorSounds.WGH_BIG_START, DoorSounds.WGH_BIG_STOP, 2.0f)) {
        @Override
        public float rangeOpenTime(int ticks, int index) {
            return normTime(ticks, 20, 20);
        }
    },

    /** Seven wide and six tall: the one a vehicle fits through. */
    LARGE_VEHICLE_DOOR("large_vehicle_door", 60, 1, 0,
            new int[] {5, 0, 0, 0, 3, 3},
            new int[][] {{0, 0, 0, -4, 6, 2}, {0, 0, 0, 4, 6, 2}},
            DoorSounds.Set.motor(DoorSounds.GARAGE_MOVE, DoorSounds.GARAGE_STOP, 2.0f)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (!open) {
                return super.blockBound(x, y, z, open, forCollision);
            }
            if (z == 3) {
                return new AABB(0.4, 0, 0, 1, 1, 1);
            }
            if (z == -3) {
                return new AABB(0, 0, 0, 0.6, 1, 1);
            }
            if (y == 0) {
                return new AABB(0, 0, 0, 1, forCollision ? 0 : 0.0625, 1);
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** Two shutters stacked, the bottom leading the top by half the run. */
    CARGO_DOOR("cargo_door", 60, 1, 0,
            new int[] {2, 0, 0, 0, 1, 1},
            new int[][] {{-1, -1, 0, 3, 3, 1}},
            DoorSounds.Set.motor(DoorSounds.GARAGE_MOVE, DoorSounds.GARAGE_STOP, 2.0f)) {
        @Override
        public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
            if (!open) {
                return new AABB(0, 0, 0.375, 1, 1, 0.625);
            }
            if (y > 1) {
                return new AABB(0, 0.25, 0.375, 1, 1, 0.625);
            }
            if (y == 0) {
                return new AABB(0, 0, 0.375, 1, forCollision ? 0 : 0.125, 0.625);
            }
            return super.blockBound(x, y, z, open, forCollision);
        }
    },

    /** The portcullis: a one-block doorway under a mast that telescopes down six blocks. */
    BLAST_DOOR("blast_door", 100, 1, 0,
            new int[] {6, 0, 0, 0, 0, 0},
            new int[][] {{0, 1, 0, 6, 1, 1}},
            DoorSounds.Set.motor(null, DoorSounds.MOTOR_STOP, 0.5f).withStart(DoorSounds.MOTOR_START));

    static final AABB FULL = new AABB(0, 0, 0, 1, 1, 1);
    static final AABB EMPTY = new AABB(0, 0, 0, 0, 0, 0);

    private final String id;
    private final int timeToOpen;
    private final int skins;
    private final int blockOffset;
    private final int[] dimensions;
    private final int[][] openRanges;
    private final DoorSounds.Set sounds;

    DoorType(String id, int timeToOpen, int skins, int blockOffset, int[] dimensions, int[][] openRanges,
             DoorSounds.Set sounds) {
        this.id = id;
        this.timeToOpen = timeToOpen;
        this.skins = skins;
        this.blockOffset = blockOffset;
        this.dimensions = dimensions;
        this.openRanges = openRanges;
        this.sounds = sounds;
    }

    public String id() {
        return id;
    }

    /** Ticks from shut to fully open. The same count runs the other way. */
    public int timeToOpen() {
        return timeToOpen;
    }

    /** How many textures this door can wear. Chosen when it is placed; see {@code DoorItem}. */
    public int skins() {
        return skins;
    }

    /** How far past the clicked block the core sits. */
    public int blockOffset() {
        return blockOffset;
    }

    public int[] dimensions() {
        return dimensions;
    }

    /** Extra boxes of placeholder blocks outside the main one. Empty for all but the vault door. */
    public int[][] extraDimensions() {
        return new int[0][];
    }

    public int[][] openRanges() {
        return openRanges;
    }

    public DoorSounds.Set sounds() {
        return sounds;
    }

    public float soundVolume() {
        return sounds.volume();
    }

    /** Runs every tick on both sides. The vault door's scrape and thuds; nothing else uses it. */
    public void onTick(DoorBlockEntity door, Level level) {
    }

    /** How far through its clearing an open range is at {@code ticks}, as a fraction. */
    public float rangeOpenTime(int ticks, int index) {
        return normTime(ticks);
    }

    /**
     * The box one block of the door collides and is selected as.
     *
     * @param x block position relative to the core, in the door's own frame
     * @param forCollision true for the collision box, false for the one the cursor picks
     */
    public AABB blockBound(int x, int y, int z, boolean open, boolean forCollision) {
        return open ? EMPTY : FULL;
    }

    public float normTime(float ticks) {
        return normTime(ticks, 0, timeToOpen);
    }

    /** {@code min == max} is a step rather than a divide by zero, which is what the silo hatches want. */
    public float normTime(float ticks, float min, float max) {
        if (max <= min) {
            return ticks >= max ? 1.0f : 0.0f;
        }
        return Mth.clamp((ticks - min) / (max - min), 0.0f, 1.0f);
    }
}
