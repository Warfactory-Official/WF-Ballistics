package com.wf.wflib.recon.source;

import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.EmconState;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconNetwork;
import com.wf.wflib.recon.SensorHandle;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.Signature;
import com.wf.wflib.recon.TargetSink;
import com.wf.wflib.recon.TargetSource;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Sensors that are giving themselves away, as targets.
 *
 * <p>This is the EMCON asymmetry made real: detection costs the fourth power of range and being detected
 * costs the square, so a set that pings is heard about twice as far as it can see. Everything else in the
 * design pays for radiating with power; this is the half that makes it a decision.
 *
 * <p>Only sonar is offered. Radars are {@link SensorSpec#radiating()} too, and the day {@link Band#EM} gets a
 * propagation model this is where they would join; making every radar in the world a contact today would be a
 * balance change of a different size, with no band able to act on it.
 */
public final class SensorTargetSource implements TargetSource {

    /**
     * How loud a ping is, on the same scale as a signature. Four, so the square-root law a listener works
     * under puts the pinger's own reach at roughly double the range its fourth-root law buys it.
     */
    public static final float PING_SOURCE_LEVEL = 4.0f;

    private static final Signature PING = new Signature(0.0f, 0.0f, 0.0f, PING_SOURCE_LEVEL, 0.0f);

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        List<ReconNetwork> nets = ReconNet.networks(level);
        for (int i = 0; i < nets.size(); i++) {
            for (SensorHandle handle : nets.get(i).sensorHandles()) {
                SensorSpec spec = handle.spec();
                if (spec.band() != Band.SONAR || !spec.radiating()) {
                    continue;
                }
                BlockPos at = handle.pos();
                double y = at.getY() + 0.5 + spec.mastHeight();
                if (!volume.contains(at.getX() + 0.5, y, at.getZ() + 0.5)) {
                    continue;
                }
                // The id is the handle's own, so a set handed its own ping back recognises it exactly rather
                // than by position: see SonarPropagator.Look.self.
                sink.accept(new TargetSnapshot(handle.id(), ContactClass.STRUCTURE,
                        at.getX() + 0.5, y, at.getZ() + 0.5, 0.0, 0.0, 0.0,
                        PING, TargetSnapshot.NO_COUNTERMEASURES, EmconState.ACTIVE,
                        spec.netId(), false, true));
            }
        }
    }
}
