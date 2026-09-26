package com.wf.wflib.armor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorResolverTest {

    private static final double EPS = 1.0E-9D;

    /** The worked example from WF-ARMOR-DESIGN.md: DT 20, DR 0.5, hardness 15, 300,000 points. */
    private static ArmorSpec steelPlate() {
        return ArmorSpec.builder("steel_plate", 300_000)
                .row(ProtectionType.KINETIC, 20.0D, 0.5D, 15.0D, 0.0D)
                .build();
    }

    /** Soft armour: good against blades and impact, almost nothing against a rifle. */
    private static ArmorSpec carrier() {
        return ArmorSpec.builder("carrier", 60_000)
                .row(ProtectionType.KINETIC, 2.0D, 0.1D, 1.0D, 0.0D)
                .row(ProtectionType.SHARP, 6.0D, 0.4D, 2.0D, 0.0D)
                .row(ProtectionType.IMPACT, 0.0D, 0.5D, 1.0D, 0.0D)
                .build();
    }

    /** Rated for 200 points an interval of chemical, which is a full-strength cloud and then some. */
    private static ArmorSpec hazmat() {
        return ArmorSpec.builder("hazmat", 100_000)
                .row(ProtectionType.CHEMICAL, 0.0D, 0.8D, 0.0D, 200.0D)
                .build();
    }

    private static int totalWear(ArmorResult r) {
        return r.wear().stream().mapToInt(ArmorResult.LayerWear::points).sum();
    }

    private static int wearOf(ArmorResult r, int index) {
        return r.wear().stream()
                .filter(w -> w.index() == index)
                .mapToInt(ArmorResult.LayerWear::points)
                .findFirst().orElse(0);
    }

    @Nested
    @DisplayName("reduction")
    class Reduction {

        @Test
        void nothingWornMeansEverythingLands() {
            ArmorResult r = ArmorResolver.resolve(List.of(), ProtectionType.KINETIC, 30.0D);
            assertEquals(30.0D, r.through(), EPS);
            assertEquals(0.0D, r.absorbed(), EPS);
            assertEquals(ArmorOutcome.THROUGH, r.outcome());
        }

        @Test
        void aHitThatCannotClearTheThresholdIsStoppedEntirely() {
            ArmorResult r = ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 4.0D);
            assertEquals(0.0D, r.through(), EPS);
            assertEquals(4.0D, r.absorbed(), EPS, "a stopped hit puts all of its energy into the plate");
            assertEquals(ArmorOutcome.STOPPED, r.outcome());
        }

        @Test
        void resistanceTakesAShareOfWhatTheThresholdLeft() {
            ArmorResult r = ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 30.0D);
            assertEquals(5.0D, r.through(), EPS, "30 - DT 20 = 10, halved by DR 0.5");
            assertEquals(25.0D, r.absorbed(), EPS);
            assertEquals(ArmorOutcome.REDUCED, r.outcome());
        }

        @Test
        void armourIsNeverTotalImmunityHoweverMuchIsStacked() {
            ArmorSpec absurd = ArmorSpec.builder("absurd", 1000)
                    .row(ProtectionType.KINETIC, 0.0D, 1.0D, 0.0D, 0.0D).build();
            ArmorResult r = ArmorResolver.resolve(
                    List.of(ArmorLayer.pristine(absurd), ArmorLayer.pristine(absurd)),
                    ProtectionType.KINETIC, 100.0D);
            assertEquals(5.0D, r.through(), EPS, "DR is capped at 0.95: a type nothing can hurt reads as a bug");
        }

        @Test
        void armourPiercingIgnoresTheThreshold() {
            ArmorResult plain = ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 30.0D);
            ArmorResult ap = ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 30.0D, 20.0D, 0.0D);
            assertEquals(5.0D, plain.through(), EPS);
            assertEquals(15.0D, ap.through(), EPS, "the whole DT is pierced, so only DR is left");
        }
    }

    @Nested
    @DisplayName("hardness brakes the volume-fire result")
    class Hardness {

        @Test
        void oneRifleRoundBitesThePlate() {
            ArmorResult r = ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 30.0D);
            assertEquals(1075, totalWear(r), "hardness 15 scuffed at 0.05, plus the 10 absorbed above it");
        }

        @Test
        void aWholeBuckshotBlastOnlyScuffsIt() {
            int wear = 0;
            for (int pellet = 0; pellet < 8; pellet++) {
                wear += totalWear(ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 4.0D));
            }
            assertEquals(160, wear, "8 pellets fully stopped, each scuffing 5% of what it put in");
        }

        @Test
        void theRifleBreaksThePlateSixTimesFasterDespiteCarryingLessEnergyIntoIt() {
            int perRound = totalWear(ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 30.0D));
            int perBlast = 8 * totalWear(ArmorResolver.resolve(plate(), ProtectionType.KINETIC, 4.0D));
            int max = steelPlate().maxCondition();
            assertEquals(280, (int) Math.ceil((double) max / perRound));
            assertEquals(1875, (int) Math.ceil((double) max / perBlast));
            assertTrue(perBlast < perRound,
                    "cheap grapeshot must mark thick armour, not meaningfully degrade it");
        }

        @Test
        void softArmourIsStillShreddedByVolumeFire() {
            // The brake is per plate, not global: the emergent result has to survive where it is fun.
            List<ArmorLayer> soft = List.of(ArmorLayer.pristine(carrier()));
            assertEquals(125, totalWear(ArmorResolver.resolve(soft, ProtectionType.KINETIC, 4.0D)),
                    "a vest authored at hardness 1 does not get to ignore a pellet");

            int blastsToShredTheVest = (int) Math.ceil(carrier().maxCondition() / (8.0D * 125));
            int blastsToBreakThePlate = (int) Math.ceil(steelPlate().maxCondition() / (8.0D * 20));
            assertEquals(60, blastsToShredTheVest);
            assertEquals(1875, blastsToBreakThePlate);
            assertTrue(blastsToBreakThePlate > blastsToShredTheVest * 30,
                    "hardness is what separates armour buckshot can grind down from armour it cannot");
        }
    }

    @Nested
    @DisplayName("wear is attributed, not totalled")
    class Attribution {

        @Test
        void thePlateThatAteTheRoundIsTheOneThatWearsOut() {
            List<ArmorLayer> kit = List.of(ArmorLayer.pristine(carrier()), ArmorLayer.pristine(steelPlate()));
            ArmorResult r = ArmorResolver.resolve(kit, ProtectionType.KINETIC, 30.0D);
            int vest = wearOf(r, 0);
            int plate = wearOf(r, 1);
            assertTrue(plate > vest * 4, "the insert took the hit, so the insert is the consumable");
            assertEquals(975, plate);
            assertEquals(185, vest);
        }

        @Test
        void aLayerThatDoesNothingAgainstThisTypeTakesNoWearAtAll() {
            List<ArmorLayer> kit = List.of(ArmorLayer.pristine(hazmat()), ArmorLayer.pristine(steelPlate()));
            ArmorResult r = ArmorResolver.resolve(kit, ProtectionType.KINETIC, 30.0D);
            assertEquals(0, wearOf(r, 0), "a gas suit is not consumed by being shot through");
            assertEquals(1075, wearOf(r, 1));
        }
    }

    @Nested
    @DisplayName("condition")
    class Condition {

        @Test
        void aWornPlateDefendsLess() {
            ArmorSpec spec = steelPlate();
            ArmorResult half = ArmorResolver.resolve(
                    List.of(new ArmorLayer(spec, spec.maxCondition() / 2)), ProtectionType.KINETIC, 30.0D);
            assertEquals(15.0D, half.through(), EPS, "half condition halves both DT and DR");
        }

        @Test
        void aSpentPlateWithAWornFloorIsStillSomeSteel() {
            ArmorSpec ceramic = ArmorSpec.builder("ceramic", 1000)
                    .row(ProtectionType.KINETIC, 20.0D, 0.5D, 15.0D, 0.0D).build();
            ArmorSpec integral = ArmorSpec.builder("integral", 1000)
                    .row(ProtectionType.KINETIC, 20.0D, 0.5D, 15.0D, 0.0D).wornFloor(0.6D).build();
            assertEquals(30.0D,
                    ArmorResolver.resolve(List.of(new ArmorLayer(ceramic, 0)), ProtectionType.KINETIC, 30.0D)
                            .through(), EPS, "shattered ceramic is nothing");
            assertEquals(12.6D,
                    ArmorResolver.resolve(List.of(new ArmorLayer(integral, 0)), ProtectionType.KINETIC, 30.0D)
                            .through(), EPS, "bad steel is still steel");
        }

        @Test
        void theResultReportsTheWorstContributingLayerForTheReadout() {
            List<ArmorLayer> kit = List.of(ArmorLayer.pristine(carrier()), ArmorLayer.pristine(steelPlate()));
            ArmorResult r = ArmorResolver.resolve(kit, ProtectionType.KINETIC, 30.0D);
            assertTrue(r.worstCondition() < 1.0D && r.worstCondition() > 0.99D,
                    "one round off a fresh kit, but the readout must move at all");
        }
    }

    @Nested
    @DisplayName("exposure")
    class Exposure {

        @Test
        void aHazardTheSuitIsRatedForCostsNothingHoweverLongYouStandThere() {
            ArmorLayer suit = ArmorLayer.pristine(hazmat());
            assertEquals(0, ArmorResolver.sustainedWear(suit, ProtectionType.CHEMICAL, 150.0D),
                    "a field it is rated for is survivable forever, which is the whole reason to own it");
            assertEquals(0, ArmorResolver.sustainedWear(suit, ProtectionType.CHEMICAL, 200.0D),
                    "at the rating exactly, still nothing: the threshold is inclusive like DT is");
        }

        @Test
        void aboveTheRatingTheSuitPaysTheDifferenceFlat() {
            ArmorLayer suit = ArmorLayer.pristine(hazmat());
            assertEquals(50, ArmorResolver.sustainedWear(suit, ProtectionType.CHEMICAL, 250.0D));
            assertEquals(100, ArmorResolver.sustainedWear(suit, ProtectionType.CHEMICAL, 300.0D));
        }

        @Test
        void aHazardIsPointsPerIntervalAndNothingIsIntegratedOverTime() {
            ArmorLayer bare = ArmorLayer.pristine(ArmorSpec.builder("bare", 100_000)
                    .row(ProtectionType.CHEMICAL, 0.0D, 0.0D, 0.0D, 0.0D).build());
            // The number the cloud is authored with is the number the suit is charged. No conversion
            // anywhere between the cloud and the durability bar, which is the point of doing it this way.
            assertEquals(150, ArmorResolver.sustainedWear(bare, ProtectionType.CHEMICAL, 150.0D));
        }

        @Test
        void aWornSuitShrugsOffLessThanItUsedTo() {
            ArmorSpec spec = hazmat();
            ArmorLayer half = new ArmorLayer(spec, spec.maxCondition() / 2);
            // Rated 200 at full condition, so 100 at half of it: a cloud it used to stand in now bites.
            assertEquals(0, ArmorResolver.sustainedWear(ArmorLayer.pristine(spec),
                    ProtectionType.CHEMICAL, 150.0D));
            assertEquals(50, ArmorResolver.sustainedWear(half, ProtectionType.CHEMICAL, 150.0D));
        }

        @Test
        void anAcuteSplashCostsHeavyAndLightArmourTheSameFraction() {
            ArmorSpec light = ArmorSpec.builder("light", 10_000).build();
            ArmorSpec heavy = ArmorSpec.builder("heavy", 500_000).build();
            int onLight = ArmorResolver.acuteWear(ArmorLayer.pristine(light), ProtectionType.CHEMICAL, 0.05D);
            int onHeavy = ArmorResolver.acuteWear(ArmorLayer.pristine(heavy), ProtectionType.CHEMICAL, 0.05D);
            assertEquals(500, onLight);
            assertEquals(25_000, onHeavy);
            assertEquals(onLight / (double) light.maxCondition(), onHeavy / (double) heavy.maxCondition(), EPS,
                    "heavy armour is not the universal answer: it laughs at bullets and still dissolves");
        }

        @Test
        void aSuitRatedForTheTypeShrugsOffTheSplash() {
            int unrated = ArmorResolver.acuteWear(
                    ArmorLayer.pristine(ArmorSpec.builder("bare", 100_000).build()),
                    ProtectionType.CHEMICAL, 0.05D);
            int rated = ArmorResolver.acuteWear(ArmorLayer.pristine(hazmat()), ProtectionType.CHEMICAL, 0.05D);
            assertEquals(5000, unrated);
            assertEquals(1000, rated, "DR 0.8 is the dial, and it needs no number of its own");
        }

        @Test
        void impactTypesNeverTakeTheExposurePath() {
            ArmorLayer plate = ArmorLayer.pristine(steelPlate());
            assertEquals(0, ArmorResolver.sustainedWear(plate, ProtectionType.KINETIC, 999.0D));
            assertEquals(0, ArmorResolver.acuteWear(plate, ProtectionType.KINETIC, 1.0D));
        }

        @Test
        void anExposureTypeStillReducesDamageEvenThoughItTakesNoImpactWear() {
            ArmorResult r = ArmorResolver.resolve(
                    List.of(ArmorLayer.pristine(hazmat())), ProtectionType.CHEMICAL, 10.0D);
            assertEquals(2.0D, r.through(), EPS, "the suit still stops the gas hurting you");
            assertEquals(0, totalWear(r), "but it wears by contact time, not by the tick that landed");
        }
    }

    @Nested
    @DisplayName("coverage")
    class Coverage {

        @Test
        void aPieceThatOnlyCoversPartOfYouIsWorthPartOfItsRating() {
            ArmorLayer whole = ArmorLayer.pristine(steelPlate());
            ArmorLayer head = whole.withCoverage(0.15D);
            ArmorResult full = ArmorResolver.resolve(List.of(whole), ProtectionType.KINETIC, 30.0D);
            ArmorResult partial = ArmorResolver.resolve(List.of(head), ProtectionType.KINETIC, 30.0D);
            // DT 3 and DR 0.075, rather than DT 20 and DR 0.5.
            assertEquals(5.0D, full.through(), EPS);
            assertEquals(24.975D, partial.through(), EPS);
        }

        @Test
        void fullCoverageIsTheDefaultSoEveryOtherTestStillMeansWhatItSaid() {
            assertEquals(1.0D, ArmorLayer.pristine(steelPlate()).coverage(), EPS);
            assertEquals(1.0D, new ArmorLayer(steelPlate(), 1000).coverage(), EPS);
        }

        @Test
        void aPartlyCoveringPieceIsScuffedWhereAFullyCoveringOneWouldBeBitten() {
            ArmorResult full = ArmorResolver.resolve(
                    List.of(ArmorLayer.pristine(steelPlate())), ProtectionType.KINETIC, 30.0D);
            ArmorResult partial = ArmorResolver.resolve(
                    List.of(ArmorLayer.pristine(steelPlate()).withCoverage(0.15D)),
                    ProtectionType.KINETIC, 30.0D);
            assertTrue(totalWear(partial) * 20 < totalWear(full),
                    "hardness is a property of the steel, not of how much of you it is in front of: "
                            + totalWear(partial) + " against " + totalWear(full));
        }

        @Test
        void coverageNarrowsTheSealAsWellAsThePlate() {
            ArmorLayer sealed = ArmorLayer.pristine(hazmat());
            assertEquals(0, ArmorResolver.sustainedWear(sealed, ProtectionType.CHEMICAL, 150.0D),
                    "a cloud of 150 against a rating of 200 is free");
            assertTrue(ArmorResolver.sustainedWear(sealed.withCoverage(0.5D),
                    ProtectionType.CHEMICAL, 150.0D) > 0,
                    "half a suit is half a seal, and a seal either holds or it does not");
        }
    }

    @Nested
    @DisplayName("inserts")
    class Inserts {

        private static ArmorSpec steelInsert() {
            return ArmorSpec.builder("steel", 300_000)
                    .row(ProtectionType.KINETIC, 20.0D, 0.5D, 15.0D, 0.0D)
                    .build();
        }

        private static ArmorSpec vest() {
            return ArmorSpec.builder("vest", 60_000)
                    .row(ProtectionType.SHARP, 6.0D, 0.4D, 2.0D, 0.0D)
                    .inserts(2, ProtectionType.KINETIC, ProtectionType.BLAST)
                    .build();
        }

        @Test
        void anInsertAdvertisesItselfByWhatItDefendsAgainst() {
            assertEquals(java.util.Set.of(ProtectionType.KINETIC), steelInsert().covers());
            assertTrue(vest().acceptsInsert(steelInsert()),
                    "a vest good at sharp and impact is exactly what wants a kinetic plate");
        }

        @Test
        void aGarmentRefusesAnInsertThatCoversNothingItAskedFor() {
            ArmorSpec filter = ArmorSpec.builder("filter", 10_000)
                    .row(ProtectionType.CHEMICAL, 0.0D, 0.9D, 0.0D, 4.0D)
                    .build();
            assertTrue(!vest().acceptsInsert(filter));
        }

        @Test
        void somethingWithItsOwnPocketsIsNotItselfAnInsert() {
            assertTrue(!vest().acceptsInsert(vest()));
        }

        @Test
        void aGarmentWithNoPocketsTakesNothing() {
            ArmorSpec plain = ArmorSpec.builder("plain", 10_000)
                    .row(ProtectionType.IMPACT, 0.0D, 0.2D, 0.0D, 0.0D)
                    .build();
            assertEquals(0, plain.insertSlots());
            assertTrue(!plain.acceptsInsert(steelInsert()));
        }

        @Test
        void rebasingOntoAnItemsDurabilityChangesNothingElse() {
            ArmorSpec rebased = steelInsert().withMaxCondition(120_000);
            assertEquals(120_000, rebased.maxCondition());
            assertEquals(steelInsert().row(ProtectionType.KINETIC), rebased.row(ProtectionType.KINETIC));
            assertEquals(steelInsert().covers(), rebased.covers());
        }
    }

    private static List<ArmorLayer> plate() {
        return List.of(ArmorLayer.pristine(steelPlate()));
    }
}
