package com.wf.wfballistics.fire;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * Per-entity custom-fire state: how many ticks of burning remain and which {@link FireType} is active. Held
 * as a Forge capability (see {@link WFFire}) so it persists across saves and rides along with the entity.
 */
public class WFFireData implements INBTSerializable<CompoundTag> {

    private int ticks;
    private FireType type = FireType.NORMAL;

    /**
     * Sets the entity alight, or refreshes an existing burn. The longer remaining duration wins, and a
     * hotter type upgrades a cooler one (so a balefire hit isn't downgraded by a subsequent normal hit).
     */
    public void ignite(FireType type, int ticks) {
        if (!isBurning() || type.ordinal() > this.type.ordinal()) {
            this.type = type;
        }
        if (ticks > this.ticks) {
            this.ticks = ticks;
        }
    }

    public boolean isBurning() {
        return ticks > 0;
    }

    public int getTicks() {
        return ticks;
    }

    public FireType getType() {
        return type;
    }

    /** Advances the burn by one tick. Call once per entity tick on the server. */
    public void tick() {
        if (ticks > 0 && --ticks <= 0) {
            type = FireType.NORMAL;
        }
    }

    public void clear() {
        ticks = 0;
        type = FireType.NORMAL;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ticks", ticks);
        tag.putByte("type", (byte) type.ordinal());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        ticks = nbt.getInt("ticks");
        FireType[] types = FireType.values();
        type = types[Math.floorMod(nbt.getByte("type"), types.length)];
    }
}
