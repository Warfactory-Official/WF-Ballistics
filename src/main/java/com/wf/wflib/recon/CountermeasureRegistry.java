package com.wf.wflib.recon;

import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The countermeasure chain, and the empty answer for anything nobody claimed. */
public final class CountermeasureRegistry {

    private static final List<CountermeasureProvider> PROVIDERS = new ArrayList<>();

    private CountermeasureRegistry() {
    }

    public static void register(CountermeasureProvider provider) {
        PROVIDERS.add(provider);
        PROVIDERS.sort(Comparator.comparingInt(CountermeasureProvider::priority).reversed());
    }

    public static int providerCount() {
        return PROVIDERS.size();
    }

    /**
     * @return what this entity carries, or {@link TargetSnapshot#NO_COUNTERMEASURES} if nobody claimed it.
     *      Never null, so callers can hand the result straight to a snapshot.
     */
    public static Countermeasure[] of(Entity entity) {
        for (int i = 0; i < PROVIDERS.size(); i++) {
            Countermeasure[] carried = PROVIDERS.get(i).of(entity);
            if (carried != null) {
                return carried;
            }
        }
        return TargetSnapshot.NO_COUNTERMEASURES;
    }
}
