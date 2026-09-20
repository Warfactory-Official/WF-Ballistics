package com.wf.wflib.warhead;

import com.wf.wflib.aef.standard.BlockAllocatorShapedCharge;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public interface WarheadCarrier {

    Level level();

    int getFragmentCount();

    /**
     * The WarForge faction this warhead belongs to (a missile's {@code teamId}), or {@code null} when it has none.
     */
    default UUID igniterFactionId() {
        return null;
    }

    /**
     * Unit direction the warhead is travelling at the moment it detonates: the jet axis for directional warheads
     * such as the shaped charge (see {@link WarheadRegistry#SHAPED_CHARGE}).
     */
    Vec3 angle();

    /**
     * Half-angle, in degrees, of the cone a directional warhead fires into (see {@link
     * WarheadRegistry#SHAPED_CHARGE}).
     */
    default float blastHalfAngleDeg() {
        return BlockAllocatorShapedCharge.DEFAULT_HALF_ANGLE_DEG;
    }
}
