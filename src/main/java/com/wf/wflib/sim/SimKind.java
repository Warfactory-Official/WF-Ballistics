package com.wf.wflib.sim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * One kind of off-world record (missile, drone, glyphid, shell...), registered in {@link SimKinds}. Per level per
 * tick: {@link #prepare} (tick thread, level tick Pre) -> {@link #advance} (worker when {@link #async}, else inline;
 * overlaps the vanilla level tick) -> {@link #resolve} (tick thread, level tick Post, in {@link #slot} order).
 *
 * <p>{@link #advance} FORBIDDEN: world access, entity access. Everything it needs is captured in prepare.
 */
public interface SimKind<R> {

    ResourceLocation id();

    /** false => records are dropped on save (in-flight rounds). */
    default boolean persistent() {
        return true;
    }

    CompoundTag save(R record);

    R load(CompoundTag tag);

    default boolean async() {
        return false;
    }

    default Slot slot() {
        return Slot.EARLY;
    }

    default void prepare(ServerLevel level, SimTier<R> tier) {
    }

    default void advance(SimTier<R> tier, long gameTime) {
    }

    void resolve(ServerLevel level, SimTier<R> tier);

    /** Resolve point in the level's Post tick. */
    enum Slot {
        /** Before the drone AI and the colony tier. */
        EARLY,
        /** After the colony tier / squads (they reassign what the materialiser placed). */
        LATE
    }
}
