package com.wf.wfballistics.recon;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The provider chain, and the fallback for anything nobody claimed. */
public final class SignatureRegistry {

    /** Reference cross-section, in blocks of frontal area. */
    private static final double REFERENCE_AREA = 4.0;

    private static final List<SignatureProvider> PROVIDERS = new ArrayList<>();

    private SignatureRegistry() {
    }

    public static void register(SignatureProvider provider) {
        PROVIDERS.add(provider);
        PROVIDERS.sort(Comparator.comparingInt(SignatureProvider::priority).reversed());
    }

    /**
     * @return the first provider's answer, or a size-derived default.
     */
    public static Signature of(Entity entity) {
        for (int i = 0; i < PROVIDERS.size(); i++) {
            Signature signature = PROVIDERS.get(i).of(entity);
            if (signature != null) {
                return signature;
            }
        }
        return fallback(entity);
    }

    /**
     * Frontal area against the reference, so an unregistered mob is detectable at a sensible range instead of being
     * invisible.
     */
    public static Signature fallback(Entity entity) {
        AABB box = entity.getBoundingBox();
        double area = box.getXsize() * box.getYsize();
        return Signature.radar((float) Math.max(0.01, area / REFERENCE_AREA));
    }
}
