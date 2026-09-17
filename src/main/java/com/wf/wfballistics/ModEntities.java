package com.wf.wfballistics;

import com.wf.wfballistics.drone.CrateEntity;
import com.wf.wfballistics.debug.EntityDebugDummy;
import com.wf.wfballistics.drone.DroneDebrisEntity;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.entity.*;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.kinetic.KineticShellEntity;
import com.wf.wfballistics.mine.MineEntity;
import com.wf.wfballistics.entity.glyphid.EntityGlyphidBomb;
import com.wf.wfballistics.entity.glyphid.GlyphidWaypoint;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidBehemoth;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidBlaster;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidBombardier;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidBrawler;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidBrenda;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidDigger;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidNuclear;
import com.wf.wfballistics.entity.glyphid.caste.EntityGlyphidScout;
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

    public static final DeferredHolder<EntityType<?>, EntityType<MissileEntity>> STEALTH_MISSILE =
            ENTITY_TYPES.register("missile", () -> EntityType.Builder.of(MissileEntity::new, MobCategory.MISC)
                    .sized(2.0F, 2.0F)
                    .clientTrackingRange(32)
                    .updateInterval(1)
                    .build("missile")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<KineticShellEntity>> KINETIC_SHELL =
            ENTITY_TYPES.register("kinetic_shell", () -> EntityType.Builder.<KineticShellEntity>of(KineticShellEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(32)
                    .updateInterval(1)
                    .fireImmune()
                    .build("kinetic_shell")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<BombletEntity>> BOMBLET =
            ENTITY_TYPES.register("bomblet", () -> EntityType.Builder.<BombletEntity>of(BombletEntity::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .fireImmune()
                    .build("bomblet")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<MineEntity>> MINE =
            ENTITY_TYPES.register("mine", () -> EntityType.Builder.<MineEntity>of(MineEntity::new, MobCategory.MISC)
                    .sized(0.75F, 0.25F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build("mine")
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

    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE =
            ENTITY_TYPES.register("drone", () -> EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
                    .sized(3.0F, 1.2F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build("drone")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<DroneDebrisEntity>> DRONE_DEBRIS =
            ENTITY_TYPES.register("drone_debris", () -> EntityType.Builder.<DroneDebrisEntity>of(DroneDebrisEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.3F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("drone_debris")
            );

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

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphid>> GLYPHID =
            ENTITY_TYPES.register("glyphid", () -> EntityType.Builder.<EntityGlyphid>of(EntityGlyphid::new, MobCategory.MONSTER)
                    .sized(1.4F, 1.0F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("glyphid")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidScout>> GLYPHID_SCOUT =
            ENTITY_TYPES.register("glyphid_scout", () -> caste(EntityGlyphidScout::new, 1.25F, 0.75F)
                    .build("glyphid_scout")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidBombardier>> GLYPHID_BOMBARDIER =
            ENTITY_TYPES.register("glyphid_bombardier", () -> caste(EntityGlyphidBombardier::new, 1.75F, 1.0F)
                    .build("glyphid_bombardier")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidBlaster>> GLYPHID_BLASTER =
            ENTITY_TYPES.register("glyphid_blaster", () -> caste(EntityGlyphidBlaster::new, 2.0F, 1.125F)
                    .build("glyphid_blaster")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidBrawler>> GLYPHID_BRAWLER =
            ENTITY_TYPES.register("glyphid_brawler", () -> caste(EntityGlyphidBrawler::new, 2.0F, 1.125F)
                    .build("glyphid_brawler")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidDigger>> GLYPHID_DIGGER =
            ENTITY_TYPES.register("glyphid_digger", () -> caste(EntityGlyphidDigger::new, 1.75F, 1.0F)
                    .build("glyphid_digger")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidBehemoth>> GLYPHID_BEHEMOTH =
            ENTITY_TYPES.register("glyphid_behemoth", () -> caste(EntityGlyphidBehemoth::new, 2.5F, 1.5F)
                    .build("glyphid_behemoth")
            );

    // Fire-immune: it is a walking bomb, and burning one to death should still leave the fuse running.
    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidNuclear>> GLYPHID_NUCLEAR =
            ENTITY_TYPES.register("glyphid_nuclear", () -> caste(EntityGlyphidNuclear::new, 2.5F, 1.75F)
                    .fireImmune()
                    .build("glyphid_nuclear")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidBrenda>> GLYPHID_BRENDA =
            ENTITY_TYPES.register("glyphid_brenda", () -> caste(EntityGlyphidBrenda::new, 2.5F, 1.75F)
                    .fireImmune()
                    .build("glyphid_brenda")
            );

    private static <T extends EntityGlyphid> EntityType.Builder<T> caste(EntityType.EntityFactory<T> factory,
                                                                        float width, float height) {
        return EntityType.Builder.of(factory, MobCategory.MONSTER)
                .sized(width, height)
                .clientTrackingRange(8)
                .updateInterval(1);
    }

    // Ordnance dropped by a glyphid on the wing: acid, or a blast for the heavier castes.
    public static final DeferredHolder<EntityType<?>, EntityType<EntityGlyphidBomb>> GLYPHID_BOMB =
            ENTITY_TYPES.register("glyphid_bomb", () -> EntityType.Builder.<EntityGlyphidBomb>of(EntityGlyphidBomb::new, MobCategory.MISC)
                    .sized(0.4F, 0.4F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("glyphid_bomb")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<GlyphidWaypoint>> GLYPHID_WAYPOINT =
            ENTITY_TYPES.register("glyphid_waypoint", () -> EntityType.Builder.<GlyphidWaypoint>of(GlyphidWaypoint::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(48)
                    .updateInterval(Integer.MAX_VALUE)
                    .build("glyphid_waypoint")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EntityDebugDummy>> DEBUG_DUMMY =
            ENTITY_TYPES.register("debug_dummy", () -> EntityType.Builder.<EntityDebugDummy>of(EntityDebugDummy::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .updateInterval(Integer.MAX_VALUE)
                    .build("debug_dummy")
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
        event.put(GLYPHID_SCOUT.get(), EntityGlyphidScout.createAttributes().build());
        event.put(GLYPHID_BOMBARDIER.get(), EntityGlyphidBombardier.createAttributes().build());
        event.put(GLYPHID_BLASTER.get(), EntityGlyphidBlaster.createAttributes().build());
        event.put(GLYPHID_BRAWLER.get(), EntityGlyphidBrawler.createAttributes().build());
        event.put(GLYPHID_DIGGER.get(), EntityGlyphidDigger.createAttributes().build());
        event.put(GLYPHID_BEHEMOTH.get(), EntityGlyphidBehemoth.createAttributes().build());
        event.put(GLYPHID_NUCLEAR.get(), EntityGlyphidNuclear.createAttributes().build());
        event.put(GLYPHID_BRENDA.get(), EntityGlyphidBrenda.createAttributes().build());
        event.put(DEBUG_DUMMY.get(), EntityDebugDummy.createAttributes().build());
    }
}
