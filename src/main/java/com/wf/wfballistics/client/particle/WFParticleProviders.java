package com.wf.wfballistics.client.particle;

import com.wf.wfballistics.WFBallistics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;


@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WFParticleProviders {

    private WFParticleProviders() {
    }

    @SubscribeEvent
    public static void registerProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(WFParticles.EXPLOSION_SMALL.get(), ExplosionSmallParticle.Provider::new);
        event.registerSpriteSet(WFParticles.ROCKET_FLAME.get(), RocketFlameParticle.Provider::new);
        event.registerSpriteSet(WFParticles.FLAME.get(), FlameParticle.Provider::new);
        event.registerSpriteSet(WFParticles.ASH.get(), AshParticle.Provider::new);
        event.registerSpriteSet(WFParticles.MIST.get(), MistParticle.Provider::new);
        event.registerSpriteSet(WFParticles.SMOKE_PLUME.get(), SmokePlumeParticle.Provider::new);
        // No sprite set: a debris chunk is built from the block's own baked model, not from a particle sheet.
        event.registerSpecial(WFParticles.BLOCK_DEBRIS.get(), new BlockDebrisParticle.Provider());
    }
}
