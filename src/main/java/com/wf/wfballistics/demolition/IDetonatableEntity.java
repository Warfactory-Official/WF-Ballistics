package com.wf.wfballistics.demolition;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** An entity that can be set off by a {@link DetonatorItem}, the way {@link IDetonatable} is a block that can. */
public interface IDetonatableEntity {

    /**
     * Set this off now, whatever it was waiting for.
     *
     * @param detonator the player who pressed it, or null when fired without one
     * @return true if it actually went off
     */
    boolean detonateOnCommand(ServerLevel level, @Nullable Player detonator);

    /** Whether {@code player} may wire this to a detonator. */
    default boolean canWireDetonator(Player player) {
        return true;
    }

    /** A short name for the wiring confirmation, so five linked things are five distinguishable rows. */
    default String detonatorLabel() {
        return "charge";
    }

    /**
     * Fire the entity {@code id} if it is still around and still detonatable.
     *
     * @return true if something went off
     */
    static boolean tryDetonate(ServerLevel level, UUID id, @Nullable Player cause) {
        Entity entity = level.getEntity(id);
        if (!(entity instanceof IDetonatableEntity detonatable) || !entity.isAlive()) {
            return false;
        }
        return detonatable.detonateOnCommand(level, cause);
    }
}
