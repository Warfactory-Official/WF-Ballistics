package com.wf.wfballistics.client.particle;

import com.wf.wfballistics.WFBallistics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;



@Mod.EventBusSubscriber(modid = WFBallistics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WFParticleProviders {

    private WFParticleProviders() { }

    @SubscribeEvent
    public static void registerProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(WFParticles.EXPLOSION_SMALL.get(), ExplosionSmallParticle.Provider::new);
        event.registerSpriteSet(WFParticles.ROCKET_FLAME.get(), RocketFlameParticle.Provider::new);
        event.registerSpriteSet(WFParticles.ASH.get(), AshParticle.Provider::new);
        event.registerSpriteSet(WFParticles.MIST.get(), MistParticle.Provider::new);
    }
}
