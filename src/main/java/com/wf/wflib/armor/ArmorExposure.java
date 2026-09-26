package com.wf.wflib.armor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Standing in the fire, the gas or the field.
 *
 * <p>STALKER is the reference and it is less harsh than it first looks: it expects the player to
 * spend minutes or hours in dangerous places, so there is no lingering charge and nothing to drain.
 * Two paths, and the split is the whole mechanic:
 *
 * <ul>
 *   <li><b>Acute.</b> Walking into it costs a flat share of maximum condition, at once. A percentage
 *       rather than points, so it costs a cheap vest and steel power armour alike: heavy armour is
 *       not the universal answer, it laughs at bullets and still dissolves.</li>
 *   <li><b>Sustained.</b> Staying in it costs a flat number of condition points per interval, for as
 *       long as contact lasts. That number is the hazard's tier, and the suit's tier for the type is
 *       subtracted from it, so a hazard at or below what the suit is rated for costs nothing at all,
 *       indefinitely. That last clause is the entire reason to own a good suit.</li>
 * </ul>
 *
 * <p>The interval is five ticks, a quarter of a second, which is roughly how fast a person reacts:
 * finer would charge damage nobody could have avoided, coarser would charge a player who did get out in
 * time for the part of the interval they were already clear of.
 *
 * <p>Accrual and persistence run at different rates on purpose. Accrual is integer, into a transient
 * field, every {@link ArmorConfig#exposureIntervalTicks}; the write to the stacks is far coarser,
 * because a component written on a worn stack is an equipment-sync packet and that packet, not the
 * allocation, is what a per-tick write actually costs. Since accrual is exact, coarsening the write
 * loses nothing.
 */
public final class ArmorExposure {

    /** Something that can say how hard a hazard is working on an entity right now. */
    public interface Source {
        /**
         * @return what {@code type} costs this entity, in condition points per exposure interval, before
         *         the armour's own rating for that type is subtracted. 0 for none
         */
        double tier(LivingEntity entity, ProtectionType type);
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    private static final Map<LivingEntity, Contact> CONTACTS = new ConcurrentHashMap<>();

    /** Intervals with no contact and nothing owed before an entry is dropped. */
    private static final int IDLE_INTERVALS_TO_FORGET = 4;

    private static boolean bootstrapped;

    private ArmorExposure() {
    }

    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** Registers the built-in hazards: being on fire, and standing in lava. Idempotent. */
    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        register((entity, type) -> {
            if (type != ProtectionType.THERMAL) {
                return 0.0D;
            }
            if (entity.isInLava()) {
                return ArmorConfig.lavaTier;
            }
            return entity.isOnFire() ? ArmorConfig.fireTier : 0.0D;
        });
    }

    /**
     * Declares that {@code entity} is in this hazard right now, at this rate. Pushed rather than polled
     * because the thing that knows is the cloud, and asking every cloud about every entity every
     * interval would be the wrong way round.
     *
     * @param tier what the hazard deals, in condition points per exposure interval. Not per tick and not
     *             per second: the interval is the unit, which is what keeps this a flat integer with no
     *             conversion anywhere between the cloud and the durability bar
     */
    public static void contact(LivingEntity entity, ProtectionType type, double tier) {
        if (entity == null || type == null || tier <= 0.0D || !type.takesExposure()) {
            return;
        }
        if (entity.level().isClientSide || !ArmorConfig.enabled) {
            return;
        }
        Contact contact = CONTACTS.computeIfAbsent(entity, e -> new Contact());
        contact.pushed.merge(type, tier, Math::max);
    }

    /** One server tick. Does nothing on most of them. */
    public static void tick(MinecraftServer server) {
        if (!ArmorConfig.enabled) {
            return;
        }
        int now = server.getTickCount();
        boolean accrue = now % Math.max(1, ArmorConfig.exposureIntervalTicks) == 0;
        boolean persist = now % Math.max(1, ArmorConfig.persistIntervalTicks) == 0;
        if (!accrue && !persist) {
            return;
        }

        if (accrue) {
            // Players are polled whether or not anything has pushed for them, because standing in a
            // fire that is doing no damage yet still ruins a suit. Everything else is covered by a
            // push: a mob only appears here once a cloud or a burn has said so.
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CONTACTS.computeIfAbsent(player, e -> new Contact());
            }
        }

        Iterator<Map.Entry<LivingEntity, Contact>> it = CONTACTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LivingEntity, Contact> entry = it.next();
            LivingEntity entity = entry.getKey();
            Contact contact = entry.getValue();
            if (entity.isRemoved() || !entity.isAlive()) {
                it.remove();
                continue;
            }
            if (accrue) {
                accrue(entity, contact);
            }
            if (persist) {
                contact.flush(entity);
            }
            if (contact.idle >= IDLE_INTERVALS_TO_FORGET && contact.pending.isEmpty()) {
                it.remove();
            }
        }
    }

    /**
     * Starts watching an entity without claiming a hazard of its own, so the polled sources get a
     * chance at it. This is how a burning mob is covered: nothing pushes for it, but it is taking fire
     * damage, and that is enough of a hint to start looking. An entity that turns out not to be in
     * anything is dropped again a few intervals later.
     */
    public static void track(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide || !ArmorConfig.enabled) {
            return;
        }
        CONTACTS.computeIfAbsent(entity, e -> new Contact());
    }

    /**
     * Writes anything owed, and <b>keeps watching</b>.
     *
     * <p>Keeping the entry is load-bearing, not tidiness. Writing wear changes a worn stack, which
     * fires {@code LivingEquipmentChangeEvent}, which flushes: if a flush also forgot what the entity
     * was standing in, the next interval would read as first contact and charge the splash again. That
     * compounds, and a hazmat suit standing in a mild cloud loses a percent of itself every interval
     * forever instead of nothing at all.
     */
    public static void flush(LivingEntity entity) {
        Contact contact = CONTACTS.get(entity);
        if (contact != null) {
            contact.flush(entity);
        }
    }

    /** Writes anything owed and stops watching. For death and logout, where there is no next interval. */
    public static void forget(LivingEntity entity) {
        Contact contact = CONTACTS.remove(entity);
        if (contact != null) {
            contact.flush(entity);
        }
    }

    /**
     * Throws away what one slot owed, without writing it.
     *
     * <p>For a piece that has actually left: the points were accrued against the thing that was being
     * worn, and {@code LivingEquipmentChangeEvent} fires after the swap, so the old stack is already
     * gone and what the event hands back is a copy of it. Writing them to its replacement would be
     * worse than losing them, and what is lost is at most one persistence interval of contact.
     */
    public static void discard(LivingEntity entity, EquipmentSlot slot) {
        Contact contact = CONTACTS.get(entity);
        if (contact != null) {
            contact.pending.remove(slot);
        }
    }

    /** Test seam. */
    public static void clear() {
        CONTACTS.clear();
    }

    private static void accrue(LivingEntity entity, Contact contact) {
        EnumSet<ProtectionType> active = EnumSet.noneOf(ProtectionType.class);
        for (ProtectionType type : ProtectionType.VALUES) {
            if (!type.takesExposure()) {
                continue;
            }
            double tier = contact.pushed.getOrDefault(type, 0.0D);
            for (Source source : SOURCES) {
                tier = Math.max(tier, source.tier(entity, type));
            }
            if (tier <= 0.0D) {
                continue;
            }
            active.add(type);
            if (!contact.lastActive.contains(type)) {
                // First contact is the splash, and it is charged straight away rather than accrued:
                // it is rare, and a percentage of maximum is not the sort of thing to leave owed.
                ArmorSystem.acute(entity, type, ArmorConfig.acuteFraction);
                continue;
            }
            sustain(entity, contact, type, tier);
        }
        contact.pushed.clear();
        contact.lastActive = active;
        contact.idle = active.isEmpty() ? contact.idle + 1 : 0;
    }

    private static void sustain(LivingEntity entity, Contact contact, ProtectionType type, double tier) {
        for (EquipmentSlot slot : ArmorSystem.SLOTS) {
            WornArmor piece = ArmorStacks.worn(entity, slot, 1.0D);
            if (piece == null) {
                continue;
            }
            int[] owed = null;
            for (int i = 0; i < piece.layers().size(); i++) {
                int points = ArmorResolver.sustainedWear(piece.layers().get(i), type, tier);
                if (points <= 0) {
                    continue;
                }
                if (owed == null) {
                    owed = contact.pending.computeIfAbsent(slot, s -> new int[piece.layers().size()]);
                    if (owed.length < piece.layers().size()) {
                        owed = java.util.Arrays.copyOf(owed, piece.layers().size());
                        contact.pending.put(slot, owed);
                    }
                }
                owed[i] += points;
            }
        }
    }

    /** What one entity owes, and what it was in contact with last interval. */
    private static final class Contact {
        private final EnumMap<ProtectionType, Double> pushed = new EnumMap<>(ProtectionType.class);
        private final EnumMap<EquipmentSlot, int[]> pending = new EnumMap<>(EquipmentSlot.class);
        private EnumSet<ProtectionType> lastActive = EnumSet.noneOf(ProtectionType.class);
        private int idle;

        private void flush(LivingEntity entity) {
            if (pending.isEmpty()) {
                return;
            }
            List<Map.Entry<EquipmentSlot, int[]>> owed = new ArrayList<>(pending.entrySet());
            pending.clear();
            for (Map.Entry<EquipmentSlot, int[]> entry : owed) {
                WornArmor piece = ArmorStacks.worn(entity, entry.getKey(), 1.0D);
                if (piece == null) {
                    continue;
                }
                int[] points = entry.getValue();
                boolean insertsChanged = false;
                int layers = Math.min(points.length, piece.layers().size());
                for (int i = 0; i < layers; i++) {
                    if (points[i] <= 0) {
                        continue;
                    }
                    ItemStack stack = piece.stackFor(i);
                    insertsChanged |= ArmorStacks.wear(stack, points[i]) > 0
                            && piece.layerToInsert()[i] >= 0;
                }
                if (insertsChanged) {
                    ArmorStacks.setInserts(piece.garment(), piece.inserts());
                }
            }
        }
    }
}
