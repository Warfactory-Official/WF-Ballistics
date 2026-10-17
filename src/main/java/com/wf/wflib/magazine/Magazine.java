package com.wf.wflib.magazine;

import com.wf.wflib.ammo.AmmoVariant;
import net.minecraft.resources.ResourceLocation;

/** Item holding {@link MagazineItems#CONTENTS}: rounds of one calibre. */
public interface Magazine {
    ResourceLocation calibre();

    /** Rounds one link holds; box = whole capacity. */
    int perLink();

    default boolean accepts(AmmoVariant round) {
        return calibre().equals(round.calibre());
    }
}
