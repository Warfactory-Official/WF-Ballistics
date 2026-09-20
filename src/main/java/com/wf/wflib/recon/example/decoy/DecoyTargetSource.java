package com.wf.wflib.recon.example.decoy;

import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.Countermeasure;
import com.wf.wflib.recon.Signature;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.TargetSink;
import com.wf.wflib.recon.TargetSource;
import com.wf.wflib.recon.example.CornerReflector;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** A worked {@link TargetSource}: how an addon adds a whole new kind of target. */
public final class DecoyTargetSource implements TargetSource {

    /** Every decoy carries one, so a cheap object presents like an expensive one. */
    private static final Countermeasure[] REFLECTOR = {CornerReflector.STANDARD};

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        long gameTime = level.getGameTime();
        List<Decoy> decoys = DecoyRegistry.get(level).view(gameTime);
        for (int i = 0; i < decoys.size(); i++) {
            Decoy decoy = decoys.get(i);
            double x = decoy.xAt(gameTime);
            double y = decoy.yAt(gameTime);
            double z = decoy.zAt(gameTime);
            if (!volume.contains(x, y, z)) {
                continue;
            }
            sink.accept(new TargetSnapshot(SourceIds.ofDecoy(decoy.id()), ContactClass.UNKNOWN,
                    x, y, z, decoy.vx(), decoy.vy(), decoy.vz(),
                    Signature.radar(decoy.rcs()), REFLECTOR,
                    com.wf.wflib.recon.EmconState.ACTIVE, 0L, false));
        }
    }
}
