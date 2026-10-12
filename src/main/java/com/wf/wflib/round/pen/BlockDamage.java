package com.wf.wflib.round.pen;

import com.wf.wflib.WFLib;
import com.wf.wflib.compat.WarforgeCompat;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Round impulse per block: {@code D += m (|v| - |v'|)}, kg m/s; {@code D >= }{@link PenTable#toughness} => destroyed,
 * no drops. Fast rounds pass with little loss, heavy slow ones stop and dump {@code m |v|}. Idle {@link #HOLD_TICKS}
 * => fades to 0 over {@link #FADE_TICKS}. Server only, not saved. Cracks: vanilla destroy progress under a negative
 * breaker id (entity ids >= 0).
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class BlockDamage {

    public static boolean enabled = true;
    /** x every toughness. */
    public static float toughnessScale = 1.0f;
    static final int HOLD_TICKS = 600;
    static final int FADE_TICKS = 200;
    static final int SWEEP_TICKS = 20;
    /** Client drops an overlay not updated for 400 ticks. */
    static final int REFRESH_TICKS = 300;
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<Entry>> LEVELS = new IdentityHashMap<>();
    private static int nextId = -2;

    private BlockDamage() {
    }

    private static final class Entry {
        final int id = nextId--;
        final BlockState state;
        final float toughness;
        float damage;
        long hit;
        long sent;
        int stage = -1;

        Entry(BlockState state, float toughness) {
            this.state = state;
            this.toughness = toughness;
        }

        float at(long now) {
            long idle = now - hit - HOLD_TICKS;
            return idle <= 0 ? damage : damage - toughness * idle / FADE_TICKS;
        }
    }

    /**
     * @param state   {@code level} state at {@code pos}
     * @param impulse kg m/s
     * @return destroyed
     */
    public static boolean hit(ServerLevel level, BlockPos pos, BlockState state, double impulse,
                              @Nullable Entity shooter) {
        if (!enabled || impulse <= 0.0 || state.isAir()) {
            return false;
        }
        float toughness = PenTable.toughness(state) * toughnessScale;
        if (toughness == Float.POSITIVE_INFINITY || isProtected(level, pos, shooter)) {
            return false;
        }
        Long2ObjectOpenHashMap<Entry> map = LEVELS.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        long key = pos.asLong();
        long now = level.getGameTime();
        Entry e = map.get(key);
        if (e != null && e.state != state) {
            clear(level, pos, map.remove(key));
            e = null;
        }
        double damage = (e == null ? 0.0 : Math.max(0.0f, e.at(now))) + impulse;
        if (damage >= toughness) {
            if (e != null) {
                clear(level, pos, map.remove(key));
            }
            return level.destroyBlock(pos, false, shooter);
        }
        if (e == null) {
            e = new Entry(state, toughness);
            map.put(key, e);
        }
        e.damage = (float) damage;
        e.hit = now;
        show(level, pos, e, now);
        return false;
    }

    /** WarForge claim rules for explosions, as the shooter (or its vehicle's driver). */
    public static boolean isProtected(ServerLevel level, BlockPos pos, @Nullable Entity shooter) {
        return WarforgeCompat.claimProtected(level, actor(shooter), pos);
    }

    static @Nullable UUID actor(@Nullable Entity shooter) {
        if (shooter instanceof Player p) {
            return p.getUUID();
        }
        return shooter != null && shooter.getRootVehicle().getControllingPassenger() instanceof Player p
                ? p.getUUID() : null;
    }

    /** Ceil(10 D / toughness) - 1: any damage cracks. */
    static int stage(float damage, float toughness) {
        return Math.min(9, Math.max(0, (int) Math.ceil(10.0f * damage / toughness) - 1));
    }

    private static void show(ServerLevel level, BlockPos pos, Entry e, long now) {
        int stage = stage(e.at(now), e.toughness);
        if (stage != e.stage || now - e.sent >= REFRESH_TICKS) {
            e.stage = stage;
            e.sent = now;
            level.destroyBlockProgress(e.id, pos, stage);
        }
    }

    private static void clear(ServerLevel level, BlockPos pos, Entry e) {
        level.destroyBlockProgress(e.id, pos, -1);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % SWEEP_TICKS != 0) {
            return;
        }
        Long2ObjectOpenHashMap<Entry> map = LEVELS.get(level);
        if (map == null || map.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ObjectIterator<Long2ObjectMap.Entry<Entry>> it = map.long2ObjectEntrySet().fastIterator();
             it.hasNext(); ) {
            Long2ObjectMap.Entry<Entry> slot = it.next();
            Entry e = slot.getValue();
            pos.set(slot.getLongKey());
            if (!level.isLoaded(pos)) {
                it.remove();
            } else if (e.at(now) <= 0.0f || level.getBlockState(pos) != e.state) {
                clear(level, pos, e);
                it.remove();
            } else {
                show(level, pos, e, now);
            }
        }
    }

    /** Placed over a damaged block within a sweep: fresh. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Long2ObjectOpenHashMap<Entry> map = LEVELS.get(level);
            Entry e = map == null ? null : map.remove(event.getPos().asLong());
            if (e != null) {
                clear(level, event.getPos(), e);
            }
        }
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.remove(level);
        }
    }

    /** Accumulated impulse at {@code pos}, kg m/s; 0 untracked. */
    public static float damage(ServerLevel level, BlockPos pos) {
        Long2ObjectOpenHashMap<Entry> map = LEVELS.get(level);
        Entry e = map == null ? null : map.get(pos.asLong());
        return e == null ? 0.0f : Math.max(0.0f, e.at(level.getGameTime()));
    }
}
