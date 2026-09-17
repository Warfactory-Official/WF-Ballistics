package com.wf.wfballistics.recon.example;

import com.wf.wfballistics.recon.Signature;
import com.wf.wfballistics.recon.SignatureProvider;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** A worked {@link SignatureProvider}, and a demonstration that the chain is really a chain. */
public final class ExampleSignatureProvider implements SignatureProvider {

    public static final String TAG_HUGE = "wf_signature_huge";
    public static final String TAG_NONE = "wf_signature_none";
    public static final String TAG_LOUD = "wf_signature_loud";

    /** A barn door: seen at ~1.7x the range a reference target is. */
    private static final float HUGE_RCS = 8.0f;
    /** A hull with its machinery running: well over a vehicle engine, a little under a torpedo's screws. */
    private static final float LOUD_ACOUSTIC = 4.0f;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    @Nullable
    public Signature of(Entity entity) {
        Set<String> tags = entity.getTags();
        if (tags.contains(TAG_HUGE)) {
            return Signature.radar(HUGE_RCS);
        }
        if (tags.contains(TAG_NONE)) {
            return Signature.NONE;
        }
        // Loud on both acoustic bands, because it is one noise: whether anything hears it is the band's
        // business, and in water that means this is a sonar target and nothing else's.
        if (tags.contains(TAG_LOUD)) {
            return new Signature(1.0f, 0.0f, 0.0f, LOUD_ACOUSTIC, 0.0f);
        }
        return null;
    }
}
