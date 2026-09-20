package com.wf.wflib.mine;

import com.wf.wflib.WFLib;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** The stock entity matchers, and the tag generation every {@link MineTarget} memo is keyed against. */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MineTargets {

    /** Anything at all. A mine that goes off for a thrown snowball, if that is what you want. */
    public static final MineTarget ANY = MineTarget.builder()
            .build();

    /** Anything alive: the default, and what a mine is normally for. */
    public static final MineTarget LIVING = MineTarget.ofClass(LivingEntity.class);

    /** Players only, spectators excluded: they have no business setting anything off. */
    public static final MineTarget PLAYERS = MineTarget.builder()
            .where(Player.class, player -> !player.isSpectator())
            .build();

    /** Mobs only, so a minefield laid against a swarm ignores the people who laid it. */
    public static final MineTarget MOBS = MineTarget.ofClass(Mob.class);

    /** The swarm specifically (see {@link EntityGlyphid}). */
    public static final MineTarget GLYPHIDS = MineTarget.ofClass(EntityGlyphid.class);

    /** Volume, in blocks, at or above which a body counts as heavy. */
    private static final double HEAVY_VOLUME = 1.0;

    /** Anything heavy enough to set off an anti-armour fuze. */
    public static final MineTarget HEAVY = MineTarget.builder()
            .type(net.minecraft.world.entity.Entity.class)
            .where(entity -> entity.isVehicle()
                    || entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight() >= HEAVY_VOLUME)
            .build();

    /**
     * Bumped whenever tags reload, so every {@link MineTarget}'s per-{@link net.minecraft.world.entity.EntityType}
     * memo is discarded rather than answering from a tag set the datapack has since replaced.
     */
    private static volatile int tagGeneration = 0;

    private MineTargets() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    /** @return the current tag generation; a memo labelled with an older one is stale. */
    public static int tagGeneration() {
        return tagGeneration;
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        tagGeneration++;
    }
}
