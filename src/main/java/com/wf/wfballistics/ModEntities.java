package com.wf.wfballistics;

import com.wf.wfballistics.drone.CrateEntity;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.entity.*;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.EntityGlyphidBomb;
import com.wf.wfballistics.entity.glyphid.GlyphidWaypoint;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    // 1. Create the DeferredRegister for Entity Types
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, WFBallistics.MODID);

    // 2. Register your specific entity
    // Replace 'YourEntityClass::new' with your actual custom entity class constructor later
    // Fallback size only: MissileEntity#makeBoundingBox fits the real AABB to the oriented model each tick.
    public static final DeferredHolder<EntityType<?>, EntityType<MissileEntity>> STEALTH_MISSILE =
            ENTITY_TYPES.register("missile", () -> EntityType.Builder.of(MissileEntity::new, MobCategory.MISC)
                    .sized(2.0F, 2.0F)
                    // 32 chunks = 512 blocks, the vanilla max view distance, so the missile is tracked (and, with
                    // MissileEntity#shouldRenderAtSqrDistance returning true, rendered) as far as the client can see;
                    // the effective range is min(this, serverViewDistance*16), horizontal only. Flight AUDIO no longer
                    // depends on tracking - it is pushed by the server independently; see MissileFlightAudioPacket.
                    .clientTrackingRange(32)
                    .updateInterval(1)
                    .build("missile")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<BombletEntity>> BOMBLET =
            ENTITY_TYPES.register("bomblet", () -> EntityType.Builder.<BombletEntity>of(BombletEntity::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .fireImmune()
                    .build("bomblet")
            );

    // Mist effect cloud: a stationary, fluid-imbued area entity.
    public static final DeferredHolder<EntityType<?>, EntityType<MistEntity>> MIST =
            ENTITY_TYPES.register("mist", () -> EntityType.Builder.of(MistEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .noSummon()
                    .build("mist")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<FireLingeringEntity>> FIRE_LINGERING =
            ENTITY_TYPES.register("fire_lingering", () -> EntityType.Builder.of(FireLingeringEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("fire_lingering")
            );

    // Autonomous rotor drone. Fallback size only: DroneEntity#makeBoundingBox fits the AABB to the
    // oriented model each tick. Tracked as far as a missile so a delivery run is visible on approach.
    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE =
            ENTITY_TYPES.register("drone", () -> EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
                    .sized(3.0F, 1.2F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build("drone")
            );

    // Cargo crate. Only ever exists loose in the world: a drone in flight holds its cargo as items and
    // draws the crate itself, so there is no such thing as a crate riding along attached to something.
    public static final DeferredHolder<EntityType<?>, EntityType<CrateEntity>> CRATE =
            ENTITY_TYPES.register("crate", () -> EntityType.Builder.<CrateEntity>of(CrateEntity::new, MobCategory.MISC)
                    .sized(CrateEntity.SIZE, CrateEntity.SIZE)
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build("crate")
            );

    // Nuclear detonation driver: a server-side, multi-tick ray explosion. Renders nothing.
    public static final DeferredHolder<EntityType<?>, EntityType<EntityNukeExplosionMK5>> NUKE_EXPLOSION =
            ENTITY_TYPES.register("nuke_explosion", () -> EntityType.Builder.<EntityNukeExplosionMK5>of(EntityNukeExplosionMK5::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .build("nuke_explosion")
            );

    // Torex: the toroidal-convection mushroom cloud effect. Large box, far tracking, never moves.
    public static final DeferredHolder<EntityType<?>, EntityType<EntityNukeTorex>> NUKE_TOREX =
            ENTITY_TYPES.register("torex", () -> EntityType.Builder.<EntityNukeTorex>of(EntityNukeTorex::new, MobCategory.MISC)
                    .noSave()
                    .fireImmune()
                    .sized(20F, 40F)
                    .clientTrackingRange(64)
                    .updateInterval(Integer.MAX_VALUE)
                    .build("torex")
            );

    // Swarm mob. Climbs walls and chews terrain, so it is tracked at normal mob range but updated every
    // tick: a glyphid that stutters on a wall reads as broken.
    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphid>> GLYPHID =
            ENTITY_TYPES.register("glyphid", () -> EntityType.Builder.<EntityGlyphid>of(EntityGlyphid::new, MobCategory.MONSTER)
                    .sized(1.4F, 1.0F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("glyphid")
            );

    // Ordnance dropped by a glyphid on the wing: acid, or a blast for the heavier castes.
    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidBomb>> GLYPHID_BOMB =
            ENTITY_TYPES.register("glyphid_bomb", () -> EntityType.Builder.<EntityGlyphidBomb>of(EntityGlyphidBomb::new, MobCategory.MISC)
                    .sized(0.4F, 0.4F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("glyphid_bomb")
            );

    // Colony order marker. Never moves, never renders, and only ticks every 40th tick, so it costs almost
    // nothing to leave lying around.
    public static final DeferredHolder<EntityType<?>, EntityType<GlyphidWaypoint>> GLYPHID_WAYPOINT =
            ENTITY_TYPES.register("glyphid_waypoint", () -> EntityType.Builder.<GlyphidWaypoint>of(GlyphidWaypoint::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(48)
                    .updateInterval(Integer.MAX_VALUE)
                    .build("glyphid_waypoint")
            );

    // 3. Register the DeferredRegister with the Mod Event Bus
    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
        eventBus.addListener(ModEntities::registerAttributes);
    }

    /**
     * Living entities need an attribute supplier registered before anything can spawn one.
     */
    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(GLYPHID.get(), EntityGlyphid.createAttributes().build());
    }
}
