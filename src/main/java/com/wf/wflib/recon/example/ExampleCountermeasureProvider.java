package com.wf.wflib.recon.example;

import com.wf.wflib.recon.Countermeasure;
import com.wf.wflib.recon.CountermeasureProvider;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fits the example countermeasures from scoreboard tags, so anything in the game can be kitted out from a command
 * with no NBT surgery and no new item:
 */
public final class ExampleCountermeasureProvider implements CountermeasureProvider {

    public static final String TAG_COATING = "wf_ram_coating";
    public static final String TAG_FACETED = "wf_faceted";
    public static final String TAG_REFLECTOR = "wf_reflector";
    public static final String TAG_ANECHOIC = "wf_anechoic";

    /**
     * Above the built-ins but below anything a pack adds, so a pack can still override this wholesale.
     */
    @Override
    public int priority() {
        return 100;
    }

    @Override
    @Nullable
    public Countermeasure[] of(Entity entity) {
        Set<String> tags = entity.getTags();
        if (tags.isEmpty()) {
            return null;
        }
        List<Countermeasure> fitted = new ArrayList<>(4);
        if (tags.contains(TAG_COATING)) {
            fitted.add(RadarAbsorbentCoating.STANDARD);
        }
        if (tags.contains(TAG_FACETED)) {
            fitted.add(AspectShaping.FACETED_NOSE);
        }
        if (tags.contains(TAG_REFLECTOR)) {
            fitted.add(CornerReflector.STANDARD);
        }
        if (tags.contains(TAG_ANECHOIC)) {
            fitted.add(AnechoicCoating.STANDARD);
        }
        return fitted.isEmpty() ? null : fitted.toArray(TargetSnapshot.NO_COUNTERMEASURES);
    }
}
