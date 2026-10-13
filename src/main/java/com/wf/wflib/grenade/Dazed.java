package com.wf.wflib.grenade;

import com.wf.wflib.WFLib;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/** Flashed mobs: no target, no navigation until the game time stored here. Server. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class Dazed {

    private static final Map<Mob, Long> UNTIL = new WeakHashMap<>();

    private Dazed() {
    }

    public static void daze(Mob mob, int ticks) {
        long until = mob.level().getGameTime() + ticks;
        UNTIL.merge(mob, until, Math::max);
        mob.setTarget(null);
        mob.getNavigation().stop();
    }

    public static boolean dazed(Mob mob) {
        Long until = UNTIL.get(mob);
        return until != null && until > mob.level().getGameTime();
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && event.getEntity() instanceof Mob mob && dazed(mob)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Pre event) {
        if (UNTIL.isEmpty() || !(event.getEntity() instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }
        Long until = UNTIL.get(mob);
        if (until == null) {
            return;
        }
        if (until <= mob.level().getGameTime()) {
            UNTIL.remove(mob);
            return;
        }
        mob.getNavigation().stop();
        mob.setTarget(null);
    }
}
