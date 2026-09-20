package com.wf.wflib.warhead;

import com.wf.wflib.orbital.OrbitalImpact;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class OrbitalWarheads {

    public static final ResourceLocation KINETIC_ROD = WarheadRegistry.rl("kinetic_rod");
    public static final ResourceLocation CARGO_LANDING = WarheadRegistry.rl("cargo_landing");
    public static final ResourceLocation CARGO_LANDING_SOFT = WarheadRegistry.rl("cargo_landing_soft");

    private OrbitalWarheads() {
    }

    public static void bootstrap() {
        WarheadRegistry.register(KINETIC_ROD, (source, pos) -> impact(source, pos, KINETIC_ROD));
        WarheadRegistry.register(CARGO_LANDING, (source, pos) -> impact(source, pos, CARGO_LANDING));
        WarheadRegistry.register(CARGO_LANDING_SOFT, (source, pos) -> impact(source, pos, CARGO_LANDING_SOFT));
    }

    private static void impact(WarheadCarrier source, Vec3 pos, ResourceLocation effect) {
        Level level = source.level();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        UUID id = source instanceof Entity entity ? entity.getUUID() : null;
        OrbitalImpact.apply(server, pos.x, pos.z, effect, source.igniterFactionId(), id);
    }
}
