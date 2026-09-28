package com.wf.wflib.armor;

import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.api.StrikeContext;
import com.wf.wflib.damage.DamageResistanceHandler;
import com.wf.wflib.round.RoundDamageSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The front door. One reduction step, one owner: everything that wants to know what armour did to a
 * hit asks here, and nothing else reduces damage.
 *
 * <p>There are two doors because there are two kinds of caller. WFMedical already knows which limb
 * was hit, so it asks about one slot; a mob has no limb model, so the whole-entity door weights every
 * worn piece by how much of a body it covers. That weighting is what stops a helmet from protecting a
 * shin, which is what the old whole-armour sum did.
 */
public final class ArmorSystem {

    /** The slots armour is worn on, and how much of a body each one is in front of. Sums to 1. */
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final double[] COVERAGE = {0.15D, 0.40D, 0.30D, 0.15D};

    /** Innate DT/DR is a handful of distinct values per mob, so the specs behind them are cached. */
    private static final Map<InnateKey, ArmorSpec> INNATE = new ConcurrentHashMap<>();

    private static volatile boolean externalPlayerResolution;
    private static final List<Predicate<? super LivingEntity>> EXTERNAL_BODIES = new CopyOnWriteArrayList<>();

    private ArmorSystem() {
    }

    // --- ownership ----------------------------------------------------------------------------

    /**
     * Declares that something else resolves armour for players, per limb, and that this mod's own
     * damage handler must therefore leave them alone.
     *
     * <p>WFMedical calls this. Without it both would reduce the same hit, which is the exact failure
     * the whole design exists to remove; with it, WFMedical picks the limb, maps it to a slot, calls
     * {@link #resolveSlot}, and this mod's handler still covers every mob in the world.
     */
    public static void claimPlayerResolution() {
        externalPlayerResolution = true;
    }

    public static boolean playerResolutionClaimed() {
        return externalPlayerResolution;
    }

    /** {@link #claimPlayerResolution} for non-player bodies matching {@code bodies} (another mod owns their HP). */
    public static void claimResolution(Predicate<? super LivingEntity> bodies) {
        EXTERNAL_BODIES.add(bodies);
    }

    /** Someone else resolves armour and HP for {@code entity}: this mod's handler and headshot factor skip it. */
    public static boolean resolutionClaimed(LivingEntity entity) {
        if (entity instanceof Player) {
            return externalPlayerResolution;
        }
        for (Predicate<? super LivingEntity> p : EXTERNAL_BODIES) {
            if (p.test(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Slot in front of an AHF part. */
    public static EquipmentSlot slotFor(HitboxPart part) {
        return switch (part) {
            case HEAD -> EquipmentSlot.HEAD;
            case TORSO, LEFT_ARM, RIGHT_ARM -> EquipmentSlot.CHEST;
            case LEFT_LEG, RIGHT_LEG -> EquipmentSlot.LEGS;
        };
    }

    /** How much of a body this slot is in front of, for the whole-entity door. */
    public static double coverage(EquipmentSlot slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                return COVERAGE[i];
            }
        }
        return 0.0D;
    }

    // --- the doors ----------------------------------------------------------------------------

    /**
     * The per-limb door: the caller already knows where the hit landed, so the piece on that slot is
     * in front of all of it.
     */
    public static ArmorResult resolveSlot(LivingEntity entity, EquipmentSlot slot, DamageSource source,
                                          float amount, boolean applyWear) {
        ProtectionType type = ArmorProjection.typeFor(source);
        if (type == null) {
            return ArmorResult.untouched(ProtectionType.IMPACT, amount);
        }
        List<WornArmor> worn = new ArrayList<>(1);
        WornArmor piece = ArmorStacks.worn(entity, slot, 1.0D);
        if (piece != null) {
            worn.add(piece);
        }
        return resolve(entity, type, amount, worn, source, applyWear);
    }

    /**
     * The whole-entity door: nobody knows where it landed, so every piece is worth its share of a body.
     */
    public static ArmorResult resolveWhole(LivingEntity entity, DamageSource source, float amount,
                                           boolean applyWear) {
        ProtectionType type = ArmorProjection.typeFor(source);
        if (type == null) {
            return ArmorResult.untouched(ProtectionType.IMPACT, amount);
        }
        return resolveWhole(entity, source, type, amount, applyWear);
    }

    /** As above, for a caller that has already projected the source and would rather not do it twice. */
    public static ArmorResult resolveWhole(LivingEntity entity, DamageSource source, ProtectionType type,
                                           float amount, boolean applyWear) {
        List<WornArmor> worn = new ArrayList<>(SLOTS.length);
        for (int i = 0; i < SLOTS.length; i++) {
            WornArmor piece = ArmorStacks.worn(entity, SLOTS[i], COVERAGE[i]);
            if (piece != null) {
                worn.add(piece);
            }
        }
        return resolve(entity, type, amount, worn, source, applyWear);
    }

    /** Pierce for {@code source}: a round's own, else the thread's {@link DamageResistanceHandler#setup} context. */
    public static float pierceDT(@Nullable DamageSource source) {
        return source instanceof RoundDamageSource round ? round.pierceDT() : DamageResistanceHandler.currentPierceDT();
    }

    public static float pierceDR(@Nullable DamageSource source) {
        return source instanceof RoundDamageSource round ? round.pierceDR() : DamageResistanceHandler.currentPierceDR();
    }

    /**
     * Resolves a hit against a ready-made set of layers, applies the wear, and hands back the whole
     * answer rather than a single float: a number alone cannot tell "the plate stopped it" from
     * "nothing was there", and those are different injuries.
     */
    public static ArmorResult resolve(LivingEntity entity, ProtectionType type, float amount,
                                      List<WornArmor> worn, @Nullable DamageSource source,
                                      boolean applyWear) {
        List<ArmorLayer> layers = new ArrayList<>();
        List<WornArmor> owners = new ArrayList<>();
        List<Integer> within = new ArrayList<>();

        ArmorLayer innate = innateLayer(entity, type, source);
        if (innate != null) {
            layers.add(innate);
            owners.add(null);
            within.add(-1);
        }
        for (WornArmor piece : worn) {
            for (int i = 0; i < piece.layers().size(); i++) {
                layers.add(piece.layers().get(i));
                owners.add(piece);
                within.add(i);
            }
        }
        if (layers.isEmpty()) {
            return ArmorResult.untouched(type, amount);
        }

        ArmorResult result = ArmorResolver.resolve(layers, type, amount, pierceDT(source), pierceDR(source));
        if (applyWear && !result.wear().isEmpty()) {
            StrikeContext strike = source == null ? null : StrikeContext.of(source);
            applyWear(result, owners, within, strike == null ? 1.0f : strike.threat().wearFactor());
        }
        return result;
    }

    /** Spends the wear the resolver attributed, x {@code factor} (threat), on the stacks that actually earned it. */
    private static void applyWear(ArmorResult result, List<WornArmor> owners, List<Integer> within, float factor) {
        List<WornArmor> dirty = null;
        for (ArmorResult.LayerWear entry : result.wear()) {
            WornArmor owner = owners.get(entry.index());
            if (owner == null) {
                // The entity's own hide. Nothing to write it to, and nothing that wears out.
                continue;
            }
            int slotIndex = within.get(entry.index());
            ItemStack stack = owner.stackFor(slotIndex);
            int points = factor == 1.0f ? entry.points() : (int) Math.ceil(entry.points() * (double) factor);
            if (ArmorStacks.wear(stack, points) > 0 && owner.layerToInsert()[slotIndex] >= 0) {
                if (dirty == null) {
                    dirty = new ArrayList<>(2);
                }
                if (!dirty.contains(owner)) {
                    dirty.add(owner);
                }
            }
        }
        if (dirty != null) {
            // Inserts are copies, so a worn insert is only real once the whole component is rebuilt.
            for (WornArmor owner : dirty) {
                ArmorStacks.setInserts(owner.garment(), owner.inserts());
            }
        }
    }

    // --- the damage event ---------------------------------------------------------------------

    /**
     * Resolves the hit and writes the result back onto the event. Called at {@code HIGH} priority, so
     * anything reading {@code getAmount()} at {@code NORMAL} reads what actually reached the body.
     */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!ArmorConfig.enabled) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        if (!(entity instanceof Player) && !ArmorConfig.mobArmor || resolutionClaimed(entity)) {
            return;
        }
        applyToEvent(event);
    }

    /**
     * The same resolution, with no question of who owns the entity.
     *
     * <p>For whoever claimed players: a claim makes the claimant responsible for <em>every</em> hit on
     * one, including the ones its own model declines to look at. A player its medical system is
     * configured to ignore is still wearing armour, and would otherwise be the only unarmoured thing
     * in the world.
     */
    public static void applyToEvent(LivingIncomingDamageEvent event) {
        if (!ArmorConfig.enabled || event.getEntity().level().isClientSide) {
            return;
        }
        LivingEntity entity = event.getEntity();
        float amount = event.getAmount();
        if (amount <= 0.0F) {
            return;
        }
        ProtectionType type = ArmorProjection.typeFor(event.getSource());
        if (type == null) {
            return;
        }
        if (type.takesExposure()) {
            // Taking fire or chemical damage is the cheapest reliable hint that an entity is standing
            // in something. Nothing pushes contact for a burning mob, and polling every entity in the
            // world every interval to find the few that are alight would be the wrong way round.
            ArmorExposure.track(entity);
        }

        StrikeContext strike = StrikeContext.of(event.getSource());
        List<HitboxPart> parts = strike == null ? null : strike.parts();
        ArmorResult result = parts == null ? resolveWhole(entity, event.getSource(), type, amount, true)
                : resolveSlot(entity, slotFor(parts.get(0)), event.getSource(), amount, true);
        if (result.absorbed() <= 0.0D) {
            return;
        }
        if (result.through() <= 0.0D) {
            // Cancelled rather than zeroed on purpose. A zero-damage hit still opens the invulnerability
            // window, so armour that stopped a shot would also be protecting the target from the next
            // one, and a stopped burst would arrive as a single hit. The buckshot-wears-the-plate
            // arithmetic depends on every pellet being counted.
            event.setCanceled(true);
            return;
        }
        event.setAmount((float) result.through());
    }

    // --- exposure -----------------------------------------------------------------------------

    /**
     * Charges a splash: a flat share of maximum condition, taken at once, across everything worn.
     *
     * @return condition points spent
     */
    public static int acute(LivingEntity entity, ProtectionType type, double fraction) {
        if (fraction <= 0.0D || !type.takesExposure()) {
            return 0;
        }
        int spent = 0;
        for (EquipmentSlot slot : SLOTS) {
            WornArmor piece = ArmorStacks.worn(entity, slot, 1.0D);
            if (piece == null) {
                continue;
            }
            boolean insertsChanged = false;
            for (int i = 0; i < piece.layers().size(); i++) {
                int points = ArmorResolver.acuteWear(piece.layers().get(i), type, fraction);
                if (points <= 0) {
                    continue;
                }
                int done = ArmorStacks.wear(piece.stackFor(i), points);
                spent += done;
                insertsChanged |= done > 0 && piece.layerToInsert()[i] >= 0;
            }
            if (insertsChanged) {
                ArmorStacks.setInserts(piece.garment(), piece.inserts());
            }
        }
        return spent;
    }

    // --- innate resistance --------------------------------------------------------------------

    /**
     * The entity's own hide as a layer, so a glyphid's chitin and a player's plate go through exactly
     * the same arithmetic. It never wears: there is no stack to write it to, and armour you are made
     * of does not come off.
     */
    @Nullable
    private static ArmorLayer innateLayer(LivingEntity entity, ProtectionType type,
                                          @Nullable DamageSource source) {
        float[] dtdr = DamageResistanceHandler.getInnateDTDR(entity, legacyCategory(type), source);
        if (dtdr[0] <= 0.0F && dtdr[1] <= 0.0F) {
            return null;
        }
        // Quantised so a mob whose resistance varies continuously cannot grow the cache without bound.
        InnateKey key = new InnateKey(type, Math.round(dtdr[0] * 16.0F), Math.round(dtdr[1] * 256.0F));
        ArmorSpec spec = INNATE.computeIfAbsent(key, k -> ArmorSpec.builder("innate/" + k.type(), 1)
                .wornFloor(1.0D)
                .row(k.type(), k.dtQ() / 16.0D, k.drQ() / 256.0D, 0.0D, 0.0D)
                .build());
        return ArmorLayer.pristine(spec);
    }

    /**
     * The protection axis projected back onto {@link DamageResistanceHandler}'s five older categories,
     * which is what innate profiles and {@code DynamicResistance} are still authored against. Lossy by
     * construction and that is fine: the older set was always a protection-axis set.
     */
    private static String legacyCategory(ProtectionType type) {
        return switch (type) {
            case KINETIC, SHARP, IMPACT -> DamageResistanceHandler.CATEGORY_PHYSICAL;
            case BLAST -> DamageResistanceHandler.CATEGORY_EXPLOSION;
            case THERMAL -> DamageResistanceHandler.CATEGORY_FIRE;
            case ELECTRIC -> DamageResistanceHandler.CATEGORY_ENERGY;
            case CHEMICAL, RADIATION -> DamageResistanceHandler.CATEGORY_OTHER;
        };
    }

    private record InnateKey(ProtectionType type, int dtQ, int drQ) {
    }
}
