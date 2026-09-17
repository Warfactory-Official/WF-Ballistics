package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.config.WFClientConfig;
import com.wf.wfballistics.entity.MistEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gives every {@link MistEntity} something that draws it, mirroring what {@link FireFlameHandler} does for fire.
 */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class MistCloudHandler {

    /** How long to wait for the fluid to sync before building the cloud anyway. */
    private static final int SYNC_WAIT_TICKS = 40;

    /**
     * Weak so that a level unload, which drops every entity, also drops anything still waiting here without this
     * handler needing to know the level went away.
     */
    private static final Map<MistEntity, Integer> PENDING = new WeakHashMap<>();

    /** Live clusters, held so a newly arrived cell can be offered to them. */
    private static final List<GasVolumeCluster> CLUSTERS = new ArrayList<>();

    private MistCloudHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof MistEntity mist)) return;
        if (!FlywheelEffectManager.isAvailable(event.getLevel())) return;

        PENDING.put(mist, 0);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        CLUSTERS.removeIf(GasVolumeCluster::isExpired);

        if (PENDING.isEmpty()) return;

        boolean volumetric = WFClientConfig.volumetricGas();

        Iterator<Map.Entry<MistEntity, Integer>> waiting = PENDING.entrySet().iterator();
        while (waiting.hasNext()) {
            Map.Entry<MistEntity, Integer> entry = waiting.next();
            MistEntity mist = entry.getKey();

            if (mist.isRemoved() || !mist.isAlive()) {
                waiting.remove();
                continue;
            }

            int waited = entry.getValue() + 1;
            if (mist.getFluid() == Fluids.EMPTY && waited < SYNC_WAIT_TICKS) {
                entry.setValue(waited);
                continue;
            }

            waiting.remove();

            if (volumetric) {
                admit(mist);
            } else {
                FlywheelEffectManager.spawn(new InstancedMistEffect(mist));
            }
        }
    }

    /** Puts one cell into a cluster, starting a new one if nothing adjacent will take it. */
    private static void admit(MistEntity cell) {
        for (GasVolumeCluster cluster : CLUSTERS) {
            if (cluster.offer(cell)) {
                return;
            }
        }

        GasVolumeCluster cluster = new GasVolumeCluster(cell);
        CLUSTERS.add(cluster);
        FlywheelEffectManager.spawn(cluster);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            CLUSTERS.clear();
        }
    }
}
