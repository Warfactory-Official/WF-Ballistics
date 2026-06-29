package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.world.level.Level;

/**
 * Final stage of the explosion pipeline: sound and particles.
 * <p>
 * An explosion can carry several SFX entries ({@link ExplosionAEF#setSFX}); each one is a single concern
 * (the bang, the smoke cloud, the screen flash, ...) so effects can be mixed freely. Invoked server-side
 * after all gameplay stages — implementations are expected to broadcast a packet to nearby clients rather
 * than spawn particles directly, since particles only exist client-side.
 */
public interface IExplosionSFX {

    void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size);
}
