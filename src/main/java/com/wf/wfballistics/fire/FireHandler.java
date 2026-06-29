package com.wf.wfballistics.fire;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.damage.DamageClass;
import com.wf.wfballistics.damage.WFDamageSources;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drives the custom-fire lifecycle: attaches the capability to every living entity, burns them each tick,
 * and routes fire deaths to the cremation effect.
 *
 * <p>While an entity has custom fire it is also kept visually on fire (vanilla fire ticks are topped up) so
 * players see it burning, and it takes {@link FireType#damage} on a fixed interval rather than vanilla's
 * faster fire-tick cadence — that slower, heavier burn is what makes custom fire read as "hotter".
 */
@Mod.EventBusSubscriber(modid = WFBallistics.MODID)
public final class FireHandler {

    /** Ticks between fire-damage applications (1 second). */
    private static final int DAMAGE_INTERVAL = 20;

    private FireHandler() { }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof LivingEntity) {
            WFFireProvider provider = new WFFireProvider();
            event.addCapability(WFFire.ID, provider);
            event.addListener(provider::invalidate);
        }
    }

    @SubscribeEvent
    public static void tick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;

        WFFireData fire = WFFire.get(entity);
        if (fire == null || !fire.isBurning()) return;

        entity.setRemainingFireTicks(Math.max(entity.getRemainingFireTicks(), 2));

        if (!entity.fireImmune() && entity.tickCount % DAMAGE_INTERVAL == 0) {
            DamageSource source = WFDamageSources.create(entity.level(), DamageClass.FIRE, null);
            entity.hurt(source, fire.getType().damage);
        }

        fire.tick();
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        AshHandler.decideGore(event.getEntity(), event.getSource());
    }
}
