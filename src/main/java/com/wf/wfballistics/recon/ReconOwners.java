package com.wf.wfballistics.recon;

import com.wf.wfballistics.compat.WarforgeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** The ownership chain, and the answer for anything nobody claimed. */
public final class ReconOwners {

    private static final List<OwnerSource> SOURCES = new ArrayList<>();

    static {
        register(new WarforgeOwners());
    }

    private ReconOwners() {
    }

    public static void register(OwnerSource source) {
        SOURCES.add(source);
        SOURCES.sort(Comparator.comparingInt(OwnerSource::priority).reversed());
    }

    public static int sourceCount() {
        return SOURCES.size();
    }

    /**
     * @return who holds this ground, or null if nobody does.
     */
    @Nullable
    public static UUID owningAt(ServerLevel level, BlockPos pos) {
        for (int i = 0; i < SOURCES.size(); i++) {
            UUID owner = SOURCES.get(i).owningAt(level, pos);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    /**
     * @return whose side this entity is on, or null if it has none.
     */
    @Nullable
    public static UUID owningEntity(Entity entity) {
        for (int i = 0; i < SOURCES.size(); i++) {
            UUID owner = SOURCES.get(i).owningEntity(entity);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    /**
     * @return the network id of whoever holds this ground, or {@link ReconNet#UNAFFILIATED}.
     */
    public static long netAt(ServerLevel level, BlockPos pos) {
        return ReconNet.netId(owningAt(level, pos));
    }

    /**
     * @return the network id of whoever this entity answers to, or {@link ReconNet#UNAFFILIATED}.
     */
    public static long netOf(Entity entity) {
        return ReconNet.netId(owningEntity(entity));
    }

    /** WarForge, if it is installed. */
    private static final class WarforgeOwners implements OwnerSource {

        @Override
        public int priority() {
            return 0;
        }

        @Override
        @Nullable
        public UUID owningAt(ServerLevel level, BlockPos pos) {
            return WarforgeCompat.factionClaiming(level, pos);
        }

        @Override
        @Nullable
        public UUID owningEntity(Entity entity) {
            return entity instanceof Player player ? WarforgeCompat.factionOfPlayer(player.getUUID()) : null;
        }
    }
}
