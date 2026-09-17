package com.wf.wfballistics.mine;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorShapedCharge;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.damage.DamageClass;
import com.wf.wfballistics.damage.WFDamageSources;
import com.wf.wfballistics.fx.ExplosionCreator;
import com.wf.wfballistics.fx.ExplosionSmallCreator;
import com.wf.wfballistics.item.MinePreset;
import com.wf.wfballistics.item.MinePresetRegistry;
import com.wf.wfballistics.warhead.WarheadCarrier;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The warheads a mine actually carries. */
public final class MineWarheads {

    /** Anti-personnel blast: a shoe-remover, not a crater. */
    public static final ResourceLocation BLAST = rl("mine_blast");
    /** Fragmentation sleeve: the burst a bounding mine hops up to deliver. */
    public static final ResourceLocation FRAG = rl("mine_frag");
    /** Directional fragmentation: a claymore's fan of balls, along the mine's facing. */
    public static final ResourceLocation DIRECTIONAL = rl("mine_directional");
    /** Anti-armour shaped charge, fired up into the belly of whatever set it off. */
    public static final ResourceLocation HEAT = rl("mine_heat");
    /** Underwater charge: the shock, not the fireball. */
    public static final ResourceLocation NAVAL = rl("mine_naval");
    /** A rack of anti-personnel canisters going out all at once: a minefield, not an explosion. */
    public static final ResourceLocation DISPENSE_AP = rl("mine_dispense_ap");
    /** The same for anti-armour. */
    public static final ResourceLocation DISPENSE_AT = rl("mine_dispense_at");

    //: blast -------------------------------------------------------------------------------------
    /** Lethal radius of an AP charge. Small: it is a few hundred grams under one foot. */
    public static final double BLAST_RADIUS = 3.5;
    public static final float BLAST_PEAK = 30.0f;
    /** How hard the charge throws its victim upward at the centre, blocks/tick. */
    private static final double BLAST_LIFT = 0.55;

    //: fragmentation -----------------------------------------------------------------------------
    /** Half-height of the band a bounding sleeve throws into, degrees off horizontal. */
    private static final double FRAG_BAND_DEG = 22.0;
    public static final double FRAG_RANGE = 14.0;
    private static final float FRAG_PER_FRAGMENT = 3.0f;
    /** Floor on how many fragments a sleeve holds, for a mine that never said. */
    private static final int FRAG_MINIMUM = 240;

    //: directional -------------------------------------------------------------------------------
    public static final double DIRECTIONAL_RANGE = 18.0;
    private static final float DIRECTIONAL_PER_FRAGMENT = 2.2f;
    private static final int DIRECTIONAL_MINIMUM = 360;
    /** What the back of a claymore does to whoever is behind it. Unpleasant, survivable, deserved. */
    private static final double BACKBLAST_RADIUS = 2.5;
    private static final float BACKBLAST_PEAK = 9.0f;

    //: shaped charge -----------------------------------------------------------------------------
    /** Blast size of the anti-armour jet. */
    public static final float HEAT_SIZE = 4.0f;
    /** Range along the jet axis that the slug itself is lethal over. */
    private static final double HEAT_JET_RANGE = 5.0;
    public static final float HEAT_PEAK = 120.0f;

    //: naval -------------------------------------------------------------------------------------
    /** Reach of the shock through water. Big: water carries it, which is the whole point of the thing. */
    public static final double NAVAL_RADIUS = 11.0;
    public static final float NAVAL_PEAK = 90.0f;
    /** What is left of the shock for someone standing clear of the water. */
    private static final float NAVAL_IN_AIR = 0.3f;
    /** Hull breach. Enough to open a boat or a dock; not enough to rearrange the seabed. */
    private static final float NAVAL_BREACH_SIZE = 5.0f;

    //: dispensers --------------------------------------------------------------------------------
    /** Canisters on a rack, and therefore mines in a burst. */
    public static final int DISPENSER_CANISTERS = 6;
    /** Outward throw, blocks/tick. With the rise below this lands the belt about eight blocks out. */
    private static final double DISPENSE_SPEED = 0.55;
    private static final double DISPENSE_RISE = 0.38;
    /** How far off its even share of the arc each canister may go, as a fraction of the share. */
    private static final double DISPENSE_JITTER = 0.35;
    /**
     * Fraction of the throw each canister's range may vary by, so the six land as a belt with depth rather than as
     * a fence along one radius.
     */
    private static final double DISPENSE_RANGE_JITTER = 0.18;
    /**
     * The front a rack covers, in degrees, centred on the way it is facing: both the sector it watches and the
     * sector it throws into.
     */
    public static final double DISPENSER_ARC = 120.0;

    private MineWarheads() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, path);
    }

    /** Adds the mine warheads to {@link WarheadRegistry}. */
    public static void bootstrap() {
        WarheadRegistry.register(BLAST, MineWarheads::blastCharge);
        WarheadRegistry.register(FRAG, MineWarheads::fragmentationSleeve);
        WarheadRegistry.register(DIRECTIONAL, MineWarheads::directionalSleeve);
        WarheadRegistry.register(HEAT, MineWarheads::shapedCharge);
        WarheadRegistry.register(NAVAL, MineWarheads::navalCharge);
        WarheadRegistry.register(DISPENSE_AP,
                (source, pos) -> dispense(source, pos, MinePresetRegistry.rl("scatter_ap")));
        WarheadRegistry.register(DISPENSE_AT,
                (source, pos) -> dispense(source, pos, MinePresetRegistry.rl("scatter_at")));

        WarheadRegistry.declarePeakDamage(BLAST, Math.round(BLAST_PEAK));
        WarheadRegistry.declarePeakDamage(DISPENSE_AP, 0);
        WarheadRegistry.declarePeakDamage(DISPENSE_AT, 0);
        WarheadRegistry.declarePeakDamage(HEAT, Math.round(HEAT_PEAK));
        WarheadRegistry.declarePeakDamage(NAVAL, Math.round(NAVAL_PEAK));
        WarheadRegistry.declarePeakDamage(FRAG, atOneBlock(FRAG_MINIMUM,
                4.0 * Math.PI * Math.sin(Math.toRadians(FRAG_BAND_DEG)), FRAG_PER_FRAGMENT));
        WarheadRegistry.declarePeakDamage(DIRECTIONAL, atOneBlock(DIRECTIONAL_MINIMUM,
                2.0 * Math.PI * (1.0 - Math.cos(Math.toRadians(MineEntity.DEFAULT_BLAST_HALF_ANGLE))),
                DIRECTIONAL_PER_FRAGMENT));
    }

    /** A person, for the reference figure above: 0.6 wide by 1.8 tall. */
    private static final double PLAYER_SILHOUETTE = 0.6 * 1.8;

    /** @return what a sleeve of this shape does to a person standing one block from it. */
    private static int atOneBlock(int fragments, double solidAngle, float perFragment) {
        return (int) Math.round(expectedHits(fragments, solidAngle, 1.0, PLAYER_SILHOUETTE) * perFragment);
    }

    /** Anti-personnel blast. */
    private static void blastCharge(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        for (Entity victim : exposed(level, pos, BLAST_RADIUS)) {
            double falloff = falloff(victim, pos, BLAST_RADIUS);
            hurt(level, source, victim, (float) (BLAST_PEAK * falloff));
            victim.addDeltaMovement(new Vec3(0.0, BLAST_LIFT * falloff, 0.0));
        }
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 2, 0.8f, 0.5f);
    }

    /** The fragmentation sleeve. */
    private static void fragmentationSleeve(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        int fragments = Math.max(FRAG_MINIMUM, source.getFragmentCount());
        // Solid angle of a band +/-e either side of the horizontal, out of the whole sphere: 4*pi*sin(e).
        double band = Math.toRadians(FRAG_BAND_DEG);
        double solidAngle = 4.0 * Math.PI * Math.sin(band);
        for (Entity victim : exposed(level, pos, FRAG_RANGE)) {
            // Outside the band entirely: standing on the mine, or standing on the roof above it.
            if (!inBand(pos, victim, band)) {
                continue;
            }
            double distanceSq = victim.getBoundingBox()
                    .getCenter()
                    .distanceToSqr(pos);
            float damage = (float) (expectedHits(fragments, solidAngle, distanceSq, silhouette(victim))
                    * FRAG_PER_FRAGMENT);
            hurt(level, source, victim, damage);
        }
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 3, 1.2f, 0.4f);
    }

    /**
     * The directional sleeve: a claymore's fan, thrown into the cone the mine is pointing along (see {@code
     * MineEntity#angle} and {@code blastHalfAngle}).
     */
    private static void directionalSleeve(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        int fragments = Math.max(DIRECTIONAL_MINIMUM, source.getFragmentCount());
        Vec3 axis = source.angle();
        double half = Math.toRadians(Math.max(1.0f, source.blastHalfAngleDeg()));
        double solidAngle = 2.0 * Math.PI * (1.0 - Math.cos(half));
        for (Entity victim : exposed(level, pos, DIRECTIONAL_RANGE)) {
            Vec3 to = victim.getBoundingBox().getCenter().subtract(pos);
            double distanceSq = to.lengthSqr();
            if (distanceSq < 1.0E-6) {
                continue;
            }
            if (inCone(pos, victim, axis, half)) {
                hurt(level, source, victim, (float) (expectedHits(fragments, solidAngle, distanceSq,
                        silhouette(victim)) * DIRECTIONAL_PER_FRAGMENT));
            } else if (distanceSq <= BACKBLAST_RADIUS * BACKBLAST_RADIUS) {
                hurt(level, source, victim, (float) (BACKBLAST_PEAK * falloff(victim, pos, BACKBLAST_RADIUS)));
            }
        }
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 2, 1.0f, 0.5f);
    }

    /** The anti-armour jet. */
    private static void shapedCharge(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        Vec3 axis = source.angle();
        new ExplosionAEF(level, pos.x, pos.y, pos.z, HEAT_SIZE)
                .makeShapedCharge(axis, source.blastHalfAngleDeg(),
                        BlockAllocatorShapedCharge.DEFAULT_JET_POWER)
                .igniterFaction(source.igniterFactionId())
                .explode();

        double half = Math.toRadians(Math.max(1.0f, source.blastHalfAngleDeg()));
        for (Entity victim : exposed(level, pos, HEAT_JET_RANGE)) {
            // The jet does not fall off across a target's own depth: it either reaches you or it does not.
            if (inCone(pos, victim, axis, half)) {
                hurt(level, source, victim, HEAT_PEAK);
            }
        }
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    }

    /** The underwater charge. */
    private static void navalCharge(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        boolean underwater = !level.getFluidState(BlockPos.containing(pos)).isEmpty();
        List<Entity> candidates = level.getEntities((Entity) null,
                new AABB(pos, pos).inflate(NAVAL_RADIUS), Entity::isAlive);
        for (Entity victim : candidates) {
            if (victim.ignoreExplosion(null)) {
                continue;
            }
            double distance = Math.sqrt(victim.distanceToSqr(pos));
            if (distance > NAVAL_RADIUS) {
                continue;
            }
            // Sharing the water is what makes the shock reach you; anything else has to be in the open.
            boolean wet = underwater && victim.isInWater();
            if (!wet && !clearPath(level, pos, victim.getEyePosition())) {
                continue;
            }
            float share = wet ? 1.0f : NAVAL_IN_AIR;
            hurt(level, source, victim, (float) (NAVAL_PEAK * share * (1.0 - distance / NAVAL_RADIUS)));
        }

        // Blocks: a hull breach, and deliberately no fire; it is going off under the water.
        new ExplosionAEF(level, pos.x, pos.y, pos.z, NAVAL_BREACH_SIZE)
                .setBlockAllocator(new BlockAllocatorStandard(16))
                .setBlockProcessor(new BlockProcessorStandard().setNoDrop())
                .igniterFaction(source.igniterFactionId())
                .explode();
        ExplosionCreator.composeEffectStandard(level, pos.x, pos.y, pos.z);
    }

    /** Fires a rack: every canister at once, thrown out and up across the front it is facing. */
    /**
     * Where canister {@code index} is thrown, as a bearing offset in radians from the rack's facing.
     *
     * @param spread the full arc in radians
     */
    public static double canisterOffset(int index, double spread, int slots) {
        double share = spread / Math.max(1, slots);
        return -spread * 0.5 + (index + 0.5) * share;
    }

    /** The most {@link #DISPENSE_JITTER} can move a canister off {@link #canisterOffset}, in radians. */
    public static double canisterJitter(double spread, int slots) {
        return spread / Math.max(1, slots) * DISPENSE_JITTER;
    }

    private static void dispense(WarheadCarrier source, Vec3 pos, ResourceLocation mineId) {
        Level level = source.level();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        ResourceLocation loaded = source instanceof MineEntity mine && mine.canisterMineId() != null
                ? mine.canisterMineId() : mineId;
        MinePreset preset = MinePresetRegistry.get(loaded);
        if (preset == null) {
            return;
        }
        MineEntity rack = source instanceof MineEntity mine ? mine : null;
        int rounds = rack != null && rack.isRack() ? rack.canisters() : DISPENSER_CANISTERS;
        if (rounds <= 0) {
            return;
        }
        // Full circle for a carrier with no facing, and for a rack whose arc was left at the default.
        double arc = rack == null ? 360.0 : Math.min(360.0, Math.max(1.0, rack.getArc()));
        double spread = Math.toRadians(arc);
        Vec3 front = rack == null ? null : rack.facing();
        double centre = front == null ? 0.0 : Math.atan2(front.z, front.x);

        RandomSource random = server.getRandom();
        int slots = Math.max(rounds, rack != null && rack.isRack() ? rack.canisterCapacity() : rounds);
        for (int canister = 0; canister < slots && canister < rounds; canister++) {
            double bearing = centre + canisterOffset(canister, spread, slots)
                    + (random.nextDouble() * 2.0 - 1.0) * canisterJitter(spread, slots);
            double throwSpeed = DISPENSE_SPEED
                    * (1.0 + (random.nextDouble() * 2.0 - 1.0) * DISPENSE_RANGE_JITTER);
            MineEntity mine = preset.build(server, random.nextFloat() * 360.0f);
            mine.moveTo(pos.x, pos.y, pos.z, mine.getYRot(), 0.0f);
            if (rack != null) {
                mine.setTeamId(rack.igniterFactionId());
                mine.setOwnerId(rack.getOwnerId());
            }
            mine.scatter(new Vec3(Math.cos(bearing) * throwSpeed, DISPENSE_RISE,
                    Math.sin(bearing) * throwSpeed), random);
            server.addFreshEntity(mine);
        }
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 2, 0.7f, 0.3f);
    }

    /** Expected number of a sleeve's fragments that actually meet a target of this size at this distance. */
    static double expectedHits(int fragments, double solidAngle, double distanceSq, double silhouette) {
        if (solidAngle <= 1.0E-6 || fragments <= 0) {
            return 0.0;
        }
        double subtended = silhouette / Math.max(distanceSq, 0.25);
        return fragments * Math.min(1.0, subtended / solidAngle);
    }

    /**
     * @return true if a spray in a band {@code half} either side of the horizontal meets this body at all.
     */
    private static boolean inBand(Vec3 pos, Entity victim, double half) {
        AABB box = victim.getBoundingBox();
        Vec3 centre = box.getCenter();
        double horizontal = Math.sqrt((centre.x - pos.x) * (centre.x - pos.x)
                + (centre.z - pos.z) * (centre.z - pos.z));
        if (horizontal < 1.0E-4) {
            return false;
        }
        double reach = horizontal * Math.tan(half);
        return box.maxY >= pos.y - reach && box.minY <= pos.y + reach;
    }

    /**
     * @return true if a cone of half-angle {@code half} about {@code axis} meets this body.
     */
    private static boolean inCone(Vec3 pos, Entity victim, Vec3 axis, double half) {
        AABB box = victim.getBoundingBox();
        Vec3 centre = box.getCenter();
        double cosHalf = Math.cos(half);
        for (double y : new double[]{box.minY + 0.1, centre.y, box.maxY - 0.1}) {
            Vec3 to = new Vec3(centre.x - pos.x, y - pos.y, centre.z - pos.z);
            double length = to.length();
            if (length < 1.0E-4) {
                return true;
            }
            if (to.scale(1.0 / length).dot(axis) >= cosHalf) {
                return true;
            }
        }
        return false;
    }

    /** The area a body presents to a fragment coming at it side-on. */
    private static double silhouette(Entity victim) {
        return Math.max(0.05, victim.getBbWidth() * victim.getBbHeight());
    }

    /** Everything within {@code radius} that the charge can actually see. */
    private static List<Entity> exposed(Level level, Vec3 pos, double radius) {
        List<Entity> candidates = level.getEntities((Entity) null,
                new AABB(pos, pos).inflate(radius), Entity::isAlive);
        candidates.removeIf(victim -> victim.ignoreExplosion(null)
                || victim.distanceToSqr(pos) > radius * radius
                || !clearPath(level, pos, victim.getEyePosition()));
        return candidates;
    }

    /** Linear falloff from the charge out to {@code radius}, 1 at the centre and 0 at the edge. */
    private static double falloff(Entity victim, Vec3 pos, double radius) {
        return Math.max(0.0, 1.0 - Math.sqrt(victim.distanceToSqr(pos)) / radius);
    }

    /**
     * @return true if nothing solid stands between the charge and {@code to}.
     */
    private static boolean clearPath(Level level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    /**
     * One hit, attributed to whoever laid the mine so a kill reads as theirs, and typed {@link
     * DamageClass#EXPLOSIVE} so armour that resists blast actually does.
     */
    private static void hurt(Level level, WarheadCarrier source, Entity victim, float amount) {
        if (amount < 0.5f) {
            return;
        }
        Entity direct = source instanceof Entity entity ? entity : null;
        DamageSource damage = WFDamageSources.create(level, DamageClass.EXPLOSIVE, layer(level, source), direct);
        victim.hurt(damage, amount);
    }

    /** @return the player who laid this mine, if they are still about, so the kill is credited to them. */
    @Nullable
    private static Entity layer(Level level, WarheadCarrier source) {
        if (source instanceof MineEntity mine && mine.getOwnerId() != null) {
            Entity owner = level.getPlayerByUUID(mine.getOwnerId());
            if (owner != null) {
                return owner;
            }
        }
        return source instanceof Entity entity ? entity : null;
    }
}
