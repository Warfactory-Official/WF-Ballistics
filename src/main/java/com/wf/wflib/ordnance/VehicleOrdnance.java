package com.wf.wflib.ordnance;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileModels;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.flight.FlightStageRegistry;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.missile.SeekerMode;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;

/**
 * Air/vehicle-launched rockets, bombs and missiles. Inputs SI (kg, N, motor ticks, Cd*A m^2, blast radius blocks);
 * accel = T/m/400, quadratic drag k = CdA/m, cruise = sqrt(accel/k), turn = maxG*g authority from {@code refSpeed};
 * homing seekers glide after burn-out.
 * Rocket => {@code rocket_<id>}, dumb bomb => {@code bomb_<id>} (kinetic); missile => {@code missile_<id>}, guided
 * bomb => {@code bomb_<id>} (missile). Size tags: {@code wflib:missile/<size>}, {@code wflib:bomb/<size>},
 * {@code wflib:rocket/<calibre>}.
 */
public final class VehicleOrdnance {

    static final double G = 9.8 / 400.0;
    private static final double ACTIVE_RADAR_RANGE = 1024.0;
    private static final ResourceLocation DIRECT = FlightStageRegistry.rl("direct");
    private static final ResourceLocation AIR_LAUNCH = FlightStageRegistry.rl("air_launch");

    private VehicleOrdnance() {
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    private static ResourceLocation warhead(ThreatKind threat, float blast) {
        return blast <= 0 ? WarheadRegistry.rl("inert")
                : threat.isShapedCharge() ? WarheadRegistry.rl("shaped_charge") : WarheadRegistry.defaultId();
    }

    /** Called from {@link KineticPresetRegistry} bootstrap. */
    public static void registerKinetic() {
        rocket("hydra_70", 70, 11.3, 4520, 10, 0.0565, 100, 3);
        rocket("crv7", 70, 11.3, 4520, 10, 0.0565, 100, 3);
        rocket("s8", 80, 11.3, 4520, 10, 0.0565, 100, 3);
        bomb("mk_82", 227, 1.135, 2, 0);
        bomb("fab_250", 227, 1.135, 2, 0);
        bomb("cbu_87", 227, 1.135, 2, 0);
        bomb("b61", 320, 1.6, 64, 0);
    }

    /** Called from {@link MissilePresetRegistry} bootstrap. */
    public static void registerMissiles() {
        ThreatKind tandem = ThreatKind.TANDEM_HEAT;
        // SACLOS: rides the launcher's beam.
        for (String id : new String[]{"agm_114", "agm_65", "akd_10", "kh_31", "shturm", "vikhr"}) {
            missile(id, MissileModels.rl("atgm"), tandem, 178, 1000, 5, 0, 49, 11760, 600, 0.245, 18, 3,
                    SeekerMode.BEAM_RIDING, 30);
        }
        missile("tow", MissileModels.rl("atgm"), tandem, 152, 1000, 4, 0, 21.5, 3440, 600, 0.1075, 18, 2,
                SeekerMode.OPTICAL, 30);
        missile("fim_92", MissileModels.rl("guided_rocket"), ThreatKind.HE, 70, 99, 8, 2, 10.1, 1616, 600, 0.0505,
                18, 6, SeekerMode.INFRARED, 30);
        missile("fb_10", MissileModels.rl("guided_rocket"), ThreatKind.HE, 120, 99, 8, 2, 30, 4800, 600, 0.15, 18, 6,
                SeekerMode.SEMI_ACTIVE_RADAR, 30);
        for (String id : new String[]{"aim_9", "aim_132", "magic"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 127, 100, 8, 4, 85, 53400, 40, 0.445, 30, 1,
                    SeekerMode.INFRARED, 30);
        }
        for (String id : new String[]{"r_73", "pl_8"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 170, 100, 8, 4, 115, 46000, 60, 0.575, 30,
                    1, SeekerMode.INFRARED, 30);
        }
        for (String id : new String[]{"aim_120", "mica"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 178, 100, 8, 4, 112, 44800, 300, 0.56, 30,
                    0.5, SeekerMode.ACTIVE_RADAR, 30);
        }
        for (String id : new String[]{"r_77", "pl_12"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 200, 100, 8, 4, 180, 72000, 300, 0.9, 30, 1,
                    SeekerMode.ACTIVE_RADAR, 30);
        }
        MissilePresetRegistry.register(base("df_41", MissileModels.rl("atlas"), ThreatKind.HE, 2250, 1000, 64, 0,
                80000, 6400000, 12000, 400, 8, 5, false)
                .highAltitude(512.0)
                .build());
        guidedBomb("gbu_12", 230, 1.15, 16);
        guidedBomb("gbu_39", 230, 1.15, 16);
    }

    private static void rocket(String id, float calibre, double mass, double thrust, int burnTicks, double cdA,
                               float damage, float blast) {
        KineticPresetRegistry.register(KineticPreset.builder(rl(id), MissileModels.rl("micro"),
                        warhead(ThreatKind.HE, blast))
                .item("rocket_" + id)
                .threat(ThreatKind.HE)
                .blast(blast, true)
                .speed(10.0)
                .drag(0.0)
                .quadraticDrag(cdA / mass)
                .motor(thrust / mass / 400.0, burnTicks)
                .gravity(G)
                .life(1200)
                .impactDamage(damage)
                .headshot(1.5)
                .caliber(calibre)
                .stackSize(16)
                .build());
    }

    /** Rests, burrows by hardness, then fuse. */
    private static void bomb(String id, double mass, double cdA, float blast, float burrow) {
        KineticPresetRegistry.register(KineticPreset.builder(rl(id), MissileModels.rl("micro"),
                        warhead(ThreatKind.HE, blast))
                .item("bomb_" + id)
                .threat(ThreatKind.HE)
                .blast(blast, true)
                .speed(0.0)
                .drag(0.0)
                .quadraticDrag(cdA / mass)
                .gravity(G)
                .life(1200)
                .mass(mass)
                .caliber(125)
                .fuseDelay(60)
                .burrow(burrow)
                .noEntityContact()
                .stackSize(4)
                .build());
    }

    /** Glides under gravity to the lock or aim point. */
    private static void guidedBomb(String id, double mass, double cdA, float blast) {
        double k = cdA / mass;
        double ref = 2.0;
        MissilePresetRegistry.register(MissilePreset.builder(rl(id), MissileModels.rl("guided_rocket"),
                        warhead(ThreatKind.HE, blast))
                .item("bomb_" + id)
                .cruiseSpeed(ref)
                .accel(0.0, k * ref * ref)
                .fuel(MissileEntity.FuelType.SOLID, 1)
                .drag(k, true)
                .gLimit(2.0 * G, ref)
                .threat(ThreatKind.HE, 125, 0)
                .blast(blast, true)
                .ascentStage(AIR_LAUNCH)
                .cruiseStage(DIRECT)
                .attackStage(DIRECT)
                .terrainFollow(0.0)
                .build());
    }

    private static void missile(String id, ResourceLocation model, ThreatKind threat, float calibre, float damage,
                                float blast, float proximity, double mass, double thrust, int burnTicks, double cdA,
                                double maxG, double refSpeed, SeekerMode seeker, float fov) {
        MissilePresetRegistry.register(base(id, model, threat, calibre, damage, blast, proximity, mass, thrust,
                burnTicks, cdA, maxG, refSpeed, seeker != SeekerMode.BEAM_RIDING)
                .cruiseStage(DIRECT)
                .attackStage(DIRECT)
                .terrainFollow(0.0)
                .seekerMode(seeker, fov, seeker == SeekerMode.BEAM_RIDING ? 0.0 : ACTIVE_RADAR_RANGE)
                .build());
    }

    private static MissilePreset.Builder base(String id, ResourceLocation model, ThreatKind threat, float calibre,
                                              float damage, float blast, float proximity, double mass, double thrust,
                                              int burnTicks, double cdA, double maxG, double refSpeed,
                                              boolean glides) {
        double accel = thrust / mass / 400.0;
        double k = cdA / mass;
        double cruise = Math.sqrt(accel / k);
        return MissilePreset.builder(rl(id), model, warhead(threat, blast))
                .cruiseSpeed(cruise)
                .accel(accel, Math.max(accel, k * cruise * cruise))
                .fuel(MissileEntity.FuelType.SOLID, burnTicks)
                .drag(k, glides)
                .gLimit(maxG * G, Math.max(0.05, refSpeed))
                .impactDamage(damage)
                .threat(threat, calibre, 0)
                .blast(blast, true)
                .proximity(proximity)
                .ascentStage(AIR_LAUNCH);
    }
}
