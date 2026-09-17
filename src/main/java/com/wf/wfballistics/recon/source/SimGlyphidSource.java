package com.wf.wfballistics.recon.source;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphid;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidRegistry;
import com.wf.wfballistics.recon.ContactClass;
import com.wf.wfballistics.recon.SourceIds;
import com.wf.wfballistics.recon.TargetSink;
import com.wf.wfballistics.recon.TargetSource;
import com.wf.wfballistics.recon.snapshot.TargetSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Glyphids that are off the world, walking as records rather than entities. */
public final class SimGlyphidSource implements TargetSource {

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        List<SimGlyphid> glyphids = SimGlyphidRegistry.get(level).view();
        for (int i = 0; i < glyphids.size(); i++) {
            SimGlyphid glyphid = glyphids.get(i);
            if (!volume.contains(glyphid.x, glyphid.y, glyphid.z)) {
                continue;
            }
            sink.accept(TargetSnapshot.of(SourceIds.ofGlyphid(glyphid.id), ContactClass.GLYPHID,
                    glyphid.x, glyphid.y, glyphid.z,
                    glyphid.x - glyphid.prevX, glyphid.y - glyphid.prevY, glyphid.z - glyphid.prevZ,
                    ReconSignatures.glyphid(glyphid.caste,
                            Integer.bitCount(glyphid.armor & EntityGlyphid.FULL_ARMOR)),
                    0L, false));
        }
    }
}
