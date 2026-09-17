package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.Iterator;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;

/** Gives every missile a Flywheel-instanced exhaust trail as it appears on the client. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class MissileTrailHandler {

    /**
     * Weak so a level unload drops anything still queued without this handler tracking the level.
     */
    private static final Set<MissileEntity> PENDING =
            Collections.newSetFromMap(new WeakHashMap<>());

    private MissileTrailHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof MissileEntity missile)) return;
        if (!FlywheelEffectManager.isAvailable(event.getLevel())) return;

        PENDING.add(missile);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) return;

        Iterator<MissileEntity> waiting = PENDING.iterator();
        while (waiting.hasNext()) {
            MissileEntity missile = waiting.next();
            waiting.remove();

            if (missile.isRemoved() || !missile.isAlive()) continue;

            FlywheelEffectManager.spawn(new InstancedTrailEffect(missile));
        }
    }
}
