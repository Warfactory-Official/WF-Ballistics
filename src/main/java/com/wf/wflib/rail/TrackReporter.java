package com.wf.wflib.rail;

import com.wf.wflib.WFLib;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.rail.align.AlignmentProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Track going down in the world, reported onto the route it belongs to.
 *
 * <p>This is what makes a route's build state a fact rather than a label somebody remembered to set. A
 * surveyor who lays the first kilometre of their own main line should find the map already saying so,
 * and a bridge blown up should take that stretch off it without anyone filing a report.</p>
 *
 * <p>Only a player's own faction's routes are considered, and only within a route's own clearance width,
 * so laying a siding next to somebody else's main line does not credit theirs.</p>
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class TrackReporter {

    /**
     * How far off a route a rail may be and still count as on it, at least.
     *
     * <p>A floor rather than the whole answer: each route widens this to its own clearance, so a main
     * line forgives more than a yard throat does.</p>
     */
    static final double MIN_TOLERANCE = 3.0;

    private TrackReporter() {
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            report(player, event.getPlacedBlock(), event.getPos(), true);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        report(event.getPlayer(), event.getState(), event.getPos(), false);
    }

    private static void report(net.minecraft.world.entity.player.Player player, BlockState state,
                               BlockPos pos, boolean laid) {
        // Deliberately not gated on Immersive Railroading: routes are the planning layer and exist
        // without it, and vanilla rails on a planned route are still track on the ground.
        if (!(player instanceof ServerPlayer server)
                || !(server.level() instanceof ServerLevel level) || !isTrack(state)) {
            return;
        }
        AlignmentProgress.report(level, WarforgeCompat.factionOfPlayer(server.getUUID()),
                pos.getX() + 0.5, pos.getZ() + 0.5, MIN_TOLERANCE, laid);
    }

    /**
     * Whether this block is track.
     *
     * <p>Vanilla rails by tag, and Immersive Railroading's by namespace rather than by class: IR is a
     * soft dependency and naming one of its blocks here would make this class fail to load without it.
     * A namespace check costs a string comparison on a block that is already a candidate.</p>
     */
    private static boolean isTrack(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(BlockTags.RAILS)) {
            return true;
        }
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && RailCompat.MODID.equals(id.getNamespace());
    }
}
