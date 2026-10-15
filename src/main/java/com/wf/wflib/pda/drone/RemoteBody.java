package com.wf.wflib.pda.drone;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** What the Drones app shows and does for one linked body; each mod registers a provider for its own. */
public interface RemoteBody {

    String name();

    /** Short, upper case. */
    String status();

    /** 0..1. */
    float fuel();

    boolean electric();

    List<Cell> cells();

    /** One line per weapon. */
    List<String> ammo();

    /** @return null = may take manual control, else why not (shown). */
    @Nullable
    String connectRefusal(ServerPlayer player);

    /** Manual control: only after {@link #connectRefusal} answered null. */
    void connect(ServerPlayer player);

    /** @param ratio 0..1 health */
    record Cell(String label, float ratio) {
    }

    List<Function<Entity, RemoteBody>> PROVIDERS = new ArrayList<>();

    /** Common setup. First non-null answer wins. */
    static void register(Function<Entity, RemoteBody> provider) {
        PROVIDERS.add(provider);
    }

    @Nullable
    static RemoteBody of(Entity entity) {
        for (int i = 0; i < PROVIDERS.size(); i++) {
            RemoteBody body = PROVIDERS.get(i).apply(entity);
            if (body != null) {
                return body;
            }
        }
        return null;
    }
}
