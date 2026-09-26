package com.wf.wflib.rail.supply;

import com.wf.wflib.rail.excavate.CarvePlan;
import com.wf.wflib.rail.excavate.TunnelProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a railway is made of, and therefore what a work train has to be carrying to build one.
 *
 * <p>Three materials, because three things are placed: the tunnel's <b>lining</b>, the <b>track</b> in
 * it, and the <b>torches</b> that keep it lit. The lining is whatever block the section is configured
 * to be built from, asked of the section rather than named again here, so a route dug in a sandstone
 * profile costs sandstone and nobody has to remember to change two settings.</p>
 *
 * <p>The quantities are not a balance decision made here. A tunnel costs exactly the blocks it puts in
 * the ground: a slice is counted as it is about to be cut and the train is charged for that count. What
 * this class decides is only which item each of those blocks is paid for with, and what a whole route
 * comes to - which is the number a station needs before it dispatches anything, so that a train is not
 * sent out with a load that cannot finish the first hundred blocks.</p>
 */
public record Bill(Item lining, Item track, Item torch) {

    /** What one block of route costs, before the ground has been looked at. */
    public record Estimate(int lining, int track, int torch) {

        public int total() {
            return this.lining + this.track + this.torch;
        }

        /** The same, as a map in the order a hold is filled. */
        public Map<Item, Integer> against(Bill bill) {
            Map<Item, Integer> out = new LinkedHashMap<>();
            add(out, bill.lining(), this.lining);
            add(out, bill.track(), this.track);
            add(out, bill.torch(), this.torch);
            return out;
        }

        private static void add(Map<Item, Integer> out, Item item, int count) {
            if (count > 0) {
                out.merge(item, count, Integer::sum);
            }
        }
    }

    /**
     * The bill for a section and a track material.
     *
     * @param trackItem what a block of track is paid for with, as a registry id
     */
    public static Bill of(TunnelProfile profile, String liningBlock, String trackItem) {
        return new Bill(itemOf(CarvePlan.stateOf(profile.liningOr(liningBlock)).getBlock().asItem(),
                Items.STONE_BRICKS), item(trackItem, Items.RAIL), Items.TORCH);
    }

    /**
     * An item by registry id.
     *
     * <p>Compared against air rather than null, because a registry lookup for an id this install has
     * never heard of hands back the default entry rather than nothing. A null check there passes and
     * the train is then charged in air, which it has an infinite amount of.</p>
     */
    private static Item item(String id, Item fallback) {
        ResourceLocation key = ResourceLocation.tryParse(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return key == null ? fallback : itemOf(BuiltInRegistries.ITEM.get(key), fallback);
    }

    private static Item itemOf(Item item, Item fallback) {
        return item == null || item == Items.AIR ? fallback : item;
    }

    /**
     * Roughly what a route of this length will take.
     *
     * <p>An estimate, and called one - a <b>low</b> one, by a lot, on anything but an axis. The lining
     * count is the section's own wall repeated along the route: twenty eight blocks a block for the
     * standard section, and what a machine actually laid was thirty seven a block down a tunnel
     * running along +z and forty three a block down one running at eighteen degrees to it. The wall
     * is the same wall; a wall that does not lie along the grid is built out of more blocks than one
     * that does, and each slice's rasterisation differs slightly from the last, so the overlap is not
     * free either.</p>
     *
     * <p>That is a wrong number to load against and the right number to charge, and only one of the
     * two comes from here. A slice is counted as it is about to be cut and the train is charged for
     * that count, so being half as much again light costs extra trips to the depot rather than a
     * railway built for nothing.</p>
     */
    public static Estimate forLength(TunnelProfile profile, double blocks) {
        double length = Math.max(0.0, blocks);
        int spacing = Math.max(1, profile.torchSpacing());
        return new Estimate((int) Math.ceil(liningPerBlock(profile) * length),
                (int) Math.ceil(length),
                (int) Math.ceil(profile.torchCells().size() * length / spacing));
    }

    /** How many blocks of lining one block of tunnel is walled with. */
    public static int liningPerBlock(TunnelProfile profile) {
        int lining = 0;
        for (int up = profile.lowestUp(); up <= profile.highestUp(); up++) {
            for (int across = 0; across < profile.width(); across++) {
                if (profile.at(across, up) == TunnelProfile.Kind.LINING) {
                    lining++;
                }
            }
        }
        return lining;
    }
}
