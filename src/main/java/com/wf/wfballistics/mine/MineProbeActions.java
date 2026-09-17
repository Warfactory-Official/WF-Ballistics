package com.wf.wfballistics.mine;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.demolition.DetonatorItem;
import com.wf.wfballistics.probe.ProbeActions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** What the probe's mine actions actually do. */
public final class MineProbeActions {

    /** Dig a mine in ({@code arg} 1) or back out ({@code arg} 0). */
    public static final ResourceLocation BURY =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "mine_bury");

    /** Make a safe mine live. The only way there is, now that a click does not. */
    public static final ResourceLocation ARM =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "mine_arm");

    /** Begin defusing. */
    public static final ResourceLocation DEFUSE =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "mine_defuse");

    /** Wire this mine to the detonator in hand ({@code arg} 1) or take it off ({@code arg} 0). */
    public static final ResourceLocation LINK =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "mine_link");

    /** Put one canister back on a rack from the hand. */
    public static final ResourceLocation RELOAD =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "mine_reload");

    private MineProbeActions() {
    }

    public static void register() {
        ProbeActions.registerEntity(BURY, (player, entity, arg) -> {
            if (!(entity instanceof MineEntity mine) || !(entity.level() instanceof ServerLevel level)) {
                return false;
            }
            boolean digIn = arg != 0;
            if (mine.isBuried() == digIn || mine.buryRefusal(player, digIn) != null) {
                return false;
            }
            mine.applyBury(level, player, InteractionHand.MAIN_HAND, digIn);
            return true;
        });

        ProbeActions.registerEntity(ARM, (player, entity, arg) ->
                entity instanceof MineEntity mine && mine.armRefusal(player) == null && mine.activate());

        ProbeActions.registerEntity(DEFUSE, (player, entity, arg) ->
                entity instanceof MineEntity mine
                        && mine.tryDefuse(player, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME);

        ProbeActions.registerEntity(RELOAD, (player, entity, arg) ->
                entity instanceof MineEntity mine
                        && mine.tryReload(player, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME);

        ProbeActions.registerEntity(LINK, (player, entity, arg) -> {
            if (!(entity.level() instanceof ServerLevel level)) {
                return false;
            }
            ItemStack clacker = DetonatorItem.inHand(player);
            return !clacker.isEmpty()
                    && DetonatorItem.wire(level, player, clacker, entity, arg != 0);
        });
    }
}
