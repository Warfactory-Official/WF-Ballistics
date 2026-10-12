package com.wf.wflib.client.wiaj;

import com.wf.gemrender.particle.LevelContactProbe;
import com.wf.gemrender.particle.ParticleCollision;
import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.client.flywheel.InstancedDebrisEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Blast debris from the blocks the blast removed, never from surviving terrain. Effect packet precedes the block
 * updates (sent at tick end) => resolved {@link #WAIT_TICKS} later from {@link Removals} claimed within
 * {@link #REACH}; nothing removed => no chunks. Chunk = removed blocks of one 2x2x2 cell, thrown from where it stood.
 */
public final class BlastDebris {
    static final int WAIT_TICKS = 3;
    /** Removals up to this far from the blast centre belong to it, blocks. */
    static final double REACH = 24;
    /** Distinct chunk models (one draw each) per blast; chunks share them round robin. */
    static final int MODELS = 6;
    static final int CELL = 2;

    private record Pending(ClientLevel level, double x, double y, double z, int count, float velocity, Quaternionf vent,
                           long tick) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    /** Harness: last resolved blast's claimed removals, chunks thrown. */
    public static int lastRemoved, lastChunks;

    private BlastDebris() {
    }

    /** {@code count}: chunk cap; {@code velocity}: blocks/tick; {@code vent}: +y -> open side (before the removals). */
    public static void schedule(ClientLevel level, double x, double y, double z, int count, float velocity,
                                Quaternionf vent) {
        PENDING.add(new Pending(level, x, y, z, count, velocity, vent, level.getGameTime()));
    }

    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.level != level) {
                it.remove();
            } else if (level.getGameTime() - p.tick >= WAIT_TICKS) {
                it.remove();
                resolve(p);
            }
        }
    }

    private static void resolve(Pending p) {
        List<Removals.Removed> removed = Removals.claim(p.x, p.y, p.z, REACH, p.tick - Removals.KEEP_TICKS);
        lastRemoved = removed.size();
        lastChunks = 0;
        if (removed.isEmpty()) {
            return;
        }
        Map<Long, List<Removals.Removed>> cells = new HashMap<>();
        for (Removals.Removed r : removed) {
            cells.computeIfAbsent(cellKey(Math.floorDiv(r.x(), CELL), Math.floorDiv(r.y(), CELL),
                    Math.floorDiv(r.z(), CELL)), k -> new ArrayList<>()).add(r);
        }
        List<List<Removals.Removed>> chunks = new ArrayList<>(cells.values());
        RandomSource rand = p.level.random;
        Collections.shuffle(chunks, new java.util.Random(rand.nextLong()));
        if (chunks.size() > p.count) {
            chunks = chunks.subList(0, p.count);
        }

        lastChunks = chunks.size();
        int models = Math.min(MODELS, chunks.size());
        List<List<Vec3>> origins = new ArrayList<>();
        List<WorldInAJar> jars = new ArrayList<>();
        for (int m = 0; m < models; m++) {
            WorldInAJar jar = jar(chunks.get(m));
            Removals.Removed first = chunks.get(m).getFirst();
            jar.setTint(p.level.getBlockTint(new BlockPos(first.x(), first.y(), first.z()),
                    BiomeColors.GRASS_COLOR_RESOLVER));
            jars.add(jar);
            origins.add(new ArrayList<>());
        }
        for (int i = 0; i < chunks.size(); i++) {
            origins.get(i % models).add(centre(chunks.get(i)));
        }

        if (FlywheelEffectManager.isAvailable(p.level)) {
            ParticleCollision.Probe probe = LevelContactProbe.of(p.level);
            for (int m = 0; m < models; m++) {
                JarModels.Rubble rubble = JarModels.bake(jars.get(m));
                if (rubble != null) {
                    FlywheelEffectManager.spawn(new InstancedDebrisEffect(p.level, p.x, p.y, p.z, origins.get(m),
                            rubble, p.velocity, p.vent, rand, probe));
                }
            }
            return;
        }
        for (int m = 0; m < models; m++) {
            for (Vec3 o : origins.get(m)) {
                Vec3 v = InstancedDebrisEffect.launch(p.x, p.y, p.z, o, p.velocity, p.vent, rand);
                DebrisManager.add(new Debris(rand, o.x, o.y, o.z, v.x, v.y, v.z, jars.get(m)));
            }
        }
    }

    private static WorldInAJar jar(List<Removals.Removed> cell) {
        WorldInAJar jar = new WorldInAJar(CELL, CELL, CELL);
        for (Removals.Removed r : cell) {
            jar.setBlock(Math.floorMod(r.x(), CELL), Math.floorMod(r.y(), CELL), Math.floorMod(r.z(), CELL), r.state());
        }
        return jar;
    }

    /** Cell centre: jar models are centred on the cell. */
    private static Vec3 centre(List<Removals.Removed> cell) {
        Removals.Removed r = cell.getFirst();
        return new Vec3(Math.floorDiv(r.x(), CELL) * CELL + CELL * 0.5, Math.floorDiv(r.y(), CELL) * CELL + CELL * 0.5,
                Math.floorDiv(r.z(), CELL) * CELL + CELL * 0.5);
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0xFFFFF) << 22 | (long) z & 0x3FFFFF;
    }

    public static void clear() {
        PENDING.clear();
    }
}
