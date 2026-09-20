package com.wf.wflib.demolition;

import com.wf.wflib.compat.WarforgeCompat;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import com.wf.wflib.network.AuxParticlePacket;
import com.wf.wflib.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The blast a mining charge produces: an instant cube around the centre that breaks only natural blocks (see {@link
 * DemolitionTags#NATURAL_BLAST_BREAKABLE}), keeps their drops (fortune-mined for ores), then spawns those drops as
 * a handful of consolidated item entities so a big blast does not litter the world with hundreds of them.
 */
public final class MiningExplosion {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final float MAX_DAMAGE = 6.0F;
    /** Cap on distinct block types sampled for the debris particle burst. */
    private static final int DEBRIS_TYPES = 8;
    /** How far the boom carries, before the distant variant's volume multiplier. See {@link DemolitionAudio}. */
    private static final float AUDIO_RANGE = 16.0F;

    private MiningExplosion() {
    }

    public static void explode(ServerLevel level, BlockPos center, int radius, int fortune, int tier,
                               @Nullable Player player) {
        List<BlockState> debris = new ArrayList<>();
        Set<Block> debrisSeen = new HashSet<>();
        List<BlockState> nearby = new ArrayList<>();
        Set<Block> nearbySeen = new HashSet<>();
        List<BlockPos> chain = new ArrayList<>();
        Set<BlockPos> doomed = new LinkedHashSet<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        Long2BooleanMap occluders = new Long2BooleanOpenHashMap();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (IDetonatable.isExplosive(state)) {
                        chain.add(pos.immutable());
                        continue;
                    }
                    if (nearby.size() < DEBRIS_TYPES && nearbySeen.add(state.getBlock())) {
                        nearby.add(state);
                    }
                    if (!isBreakable(state, tier)) {
                        continue;
                    }
                    if (state.getDestroySpeed(level, pos) < 0) {
                        continue; // indestructible (bedrock, deposits)
                    }
                    if (hasCover(level, center, pos, tier, occluders, probe)) {
                        continue; // shadowed by a block the blast can't break
                    }
                    doomed.add(pos.immutable());
                }
            }
        }

        WarforgeCompat.filterClaimProtected(level, factionOf(player), doomed);

        // Pass two: break what survived, keeping the drops.
        List<ItemStack> drops = new ArrayList<>();
        int broken = 0;
        for (BlockPos target : doomed) {
            BlockState state = level.getBlockState(target);
            if (state.isAir()) {
                continue;
            }
            boolean ore = state.is(Tags.Blocks.ORES);
            BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(target) : null;
            List<ItemStack> blockDrops = Block.getDrops(state, level, target, be);
            if (ore && fortune > 0) {
                applyFortune(blockDrops, fortune, level.random);
            }
            drops.addAll(blockDrops);
            if (debris.size() < DEBRIS_TYPES && debrisSeen.add(state.getBlock())) {
                debris.add(state);
            }
            level.setBlock(target, AIR, Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
            broken++;
        }

        spawnConsolidatedDrops(level, center, consolidate(drops));
        // Smoke and the block-break burst always play; only the flying debris needs blocks to have broken.
        spawnBlastEffect(level, center, radius, broken > 0 ? debris : List.of());
        spawnCrackParticles(level, center, radius, nearby);
        DemolitionAudio.playBlast(level, Vec3.atCenterOf(center), 4.0F, AUDIO_RANGE);
        damageEntities(level, center, radius, player);

        for (BlockPos p : chain) {
            IDetonatable.tryDetonate(level, p, player);
        }
    }

    @Nullable
    private static UUID factionOf(@Nullable Player player) {
        return player == null ? null : WarforgeCompat.factionOfPlayer(player.getUUID());
    }

    private static boolean isBreakable(BlockState state, int tier) {
        // Deep matrix and deepslate ores need tier 2; checked first so it beats the broad ore tag below.
        if (state.is(DemolitionTags.DEEP_BLAST_BREAKABLE) || state.is(DemolitionTags.DEEP_ORES)) {
            return tier >= 2;
        }
        return state.is(DemolitionTags.NATURAL_BLAST_BREAKABLE) || state.is(Tags.Blocks.ORES);
    }

    /**
     * True when a block the blast cannot break stands between the centre and {@code target}, so the target sits in
     * the blast's shadow and should be left intact.
     */
    private static boolean hasCover(ServerLevel level, BlockPos center, BlockPos target, int tier,
                                    Long2BooleanMap occluders, BlockPos.MutableBlockPos scratch) {
        int x = center.getX();
        int y = center.getY();
        int z = center.getZ();
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        int dx = tx - x;
        int dy = ty - y;
        int dz = tz - z;
        if (dx == 0 && dy == 0 && dz == 0) {
            return false; // the centre itself
        }
        int stepX = Integer.signum(dx);
        int stepY = Integer.signum(dy);
        int stepZ = Integer.signum(dz);
        // Both endpoints are block centres, so the first boundary is half a voxel away on each moving axis.
        double tMaxX = dx != 0 ? 0.5 / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0 ? 0.5 / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tMaxZ = dz != 0 ? 0.5 / Math.abs(dz) : Double.POSITIVE_INFINITY;
        double tDeltaX = dx != 0 ? 1.0 / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? 1.0 / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = dz != 0 ? 1.0 / Math.abs(dz) : Double.POSITIVE_INFINITY;
        int cx = x;
        int cy = y;
        int cz = z;
        int maxSteps = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) + 2;
        for (int i = 0; i < maxSteps; i++) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                cx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                cy += stepY;
                tMaxY += tDeltaY;
            } else {
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (cx == tx && cy == ty && cz == tz) {
                return false; // reached the target with a clear line to the centre
            }
            if (isOccluder(level, cx, cy, cz, tier, occluders, scratch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A blast occluder is a solid block the blast can't break: air and anything breakable let the blast through,
     * everything else (bedrock, deposits, player-built walls) stops it and casts a shadow.
     */
    private static boolean isOccluder(ServerLevel level, int x, int y, int z, int tier,
                                      Long2BooleanMap occluders, BlockPos.MutableBlockPos scratch) {
        long key = BlockPos.asLong(x, y, z);
        if (occluders.containsKey(key)) {
            return occluders.get(key);
        }
        scratch.set(x, y, z);
        BlockState state = level.getBlockState(scratch);
        boolean occludes;
        if (state.isAir()) {
            occludes = false;
        } else if (isBreakable(state, tier) && state.getDestroySpeed(level, scratch) >= 0) {
            occludes = false; // the blast eats straight through anything it can break
        } else {
            occludes = state.blocksMotion(); // decorations (torches, flowers, fluids) don't shield anything
        }
        occluders.put(key, occludes);
        return occludes;
    }

    private static void applyFortune(List<ItemStack> drops, int fortune, RandomSource rng) {
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            int bonus = Math.max(0, rng.nextInt(fortune + 2) - 1);
            if (bonus > 0) {
                drop.grow(drop.getCount() * bonus);
            }
        }
    }

    /** Merge same-item drops into full stacks so as few item entities as possible are spawned. */
    private static List<ItemStack> consolidate(List<ItemStack> drops) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            int remaining = drop.getCount();
            for (ItemStack existing : out) {
                if (remaining <= 0) {
                    break;
                }
                if (ItemStack.isSameItemSameComponents(existing, drop)) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    if (space > 0) {
                        int moved = Math.min(space, remaining);
                        existing.grow(moved);
                        remaining -= moved;
                    }
                }
            }
            while (remaining > 0) {
                ItemStack copy = drop.copy();
                int take = Math.min(drop.getMaxStackSize(), remaining);
                copy.setCount(take);
                out.add(copy);
                remaining -= take;
            }
        }
        return out;
    }

    /** Spawn the merged stacks in a tight, deterministic spiral above the centre, never one per block. */
    private static void spawnConsolidatedDrops(ServerLevel level, BlockPos center, List<ItemStack> stacks) {
        double baseX = center.getX() + 0.5;
        double baseY = center.getY() + 0.5;
        double baseZ = center.getZ() + 0.5;
        for (int i = 0; i < stacks.size(); i++) {
            double angle = i * 2.3999632; // golden angle keeps the cluster even
            double r = Math.min(0.6, 0.15 + 0.05 * i);
            double ox = Math.cos(angle) * r;
            double oz = Math.sin(angle) * r;
            ItemEntity entity = new ItemEntity(level, baseX + ox, baseY + 0.4, baseZ + oz, stacks.get(i));
            entity.setDeltaMovement(ox * 0.05, 0.12, oz * 0.05);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
    }

    /** The blast's smoke and flying chunks, as one packet. */
    /** How many distinct broken block types a blast bothers to throw chunks of. */
    private static final int MAX_DEBRIS_TYPES = 3;

    private static void spawnBlastEffect(ServerLevel level, BlockPos center, int radius,
                                         List<BlockState> debris) {
        CompoundTag data = new CompoundTag();
        data.putInt("radius", radius);

        int[] states = new int[Math.min(debris.size(), MAX_DEBRIS_TYPES)];
        for (int i = 0; i < states.length; i++) {
            states[i] = Block.getId(debris.get(i));
        }
        data.putIntArray("debris", states);

        double cx = center.getX() + 0.5;
        double cy = center.getY() + 0.5;
        double cz = center.getZ() + 0.5;
        WFNetwork.sendToAllAround(level, cx, cy, cz, BlastParticles.RANGE,
                new AuxParticlePacket("mining_blast", cx, cy, cz, data));
    }

    /** A heavy burst of vanilla block-break crack particles, fanning out from the centre. Always plays. */
    private static void spawnCrackParticles(ServerLevel level, BlockPos center, int radius,
                                            List<BlockState> nearby) {
        double cx = center.getX() + 0.5;
        double cy = center.getY() + 0.5;
        double cz = center.getZ() + 0.5;
        List<BlockState> states = nearby.isEmpty() ? List.of(Blocks.STONE.defaultBlockState()) : nearby;
        int per = Math.max(8, (90 + radius * 40) / states.size());
        float spread = radius * 0.5F;
        for (BlockState state : states) {
            BlastParticles.burst(level, new BlockParticleOption(ParticleTypes.BLOCK, state),
                    cx, cy, cz, per, spread, spread, spread, 0.35D);
        }
    }

    private static void damageEntities(ServerLevel level, BlockPos center, int radius, @Nullable Player player) {
        double cx = center.getX() + 0.5;
        double cy = center.getY() + 0.5;
        double cz = center.getZ() + 0.5;
        double range = radius + 1.0;
        DamageSource source = level.damageSources()
                .explosion(null, player);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(center).inflate(range))) {
            double dist = Math.sqrt(entity.distanceToSqr(cx, cy, cz));
            if (dist > range) {
                continue;
            }
            float damage = (float) (MAX_DAMAGE * (1.0 - dist / range));
            if (damage > 0) {
                entity.hurt(source, damage);
            }
        }
    }
}
