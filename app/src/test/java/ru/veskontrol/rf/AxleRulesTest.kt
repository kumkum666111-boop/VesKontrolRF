package ru.veskontrol.rf

import org.junit.Assert.*
import org.junit.Test

class AxleRulesTest {
    private fun axis(
        actual: String = "",
        gap: String = "",
        wheels: Int = 2,
        dual: Boolean = false,
        own: String = ""
    ) = AxleInput(actual, wheels, dual, gap, own)

    @Test fun singleAxleOn115RoadOneTyrePerWheel() {
        val result = AxleRules.calculate(listOf(axis()), RoadNorm.ELEVEN_FIVE)
        assertEquals(10.5, result.axes.single().statutoryLimit, 0.001)
    }

    @Test fun singleAxleOn115RoadDualTyres() {
        val result = AxleRules.calculate(listOf(axis(dual = true)), RoadNorm.ELEVEN_FIVE)
        assertEquals(11.5, result.axes.single().statutoryLimit, 0.001)
    }

    @Test fun singleAxleWithFourWheelsStillUsesSingleOrDualTyreSelection() {
        val result = AxleRules.calculate(listOf(axis(wheels = 4, dual = true)), RoadNorm.ELEVEN_FIVE)
        assertEquals(11.5, result.axes.single().statutoryLimit, 0.001)
    }

    @Test fun tandemChecksGroupAndSingleAxleCap() {
        val result = AxleRules.calculate(
            listOf(axis("5.0"), axis("5.0", gap = "0.9")),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(11.5, result.groups.single().statutoryGroupLimit!!, 0.001)
        assertEquals(10.5, result.axes.first().statutoryLimit, 0.001)
        assertEquals(LoadState.OK, result.groups.single().state)
    }

    @Test fun tridemGroupUsesAverageGapBand() {
        val result = AxleRules.calculate(
            listOf(axis("4"), axis("4", "2.0"), axis("4", "2.4")),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(25.0, result.groups.single().statutoryGroupLimit!!, 0.001)
    }

    @Test fun groupOfFourLowWheelAxesUsesPerAxleRow() {
        val result = AxleRules.calculate(
            listOf(axis("5"), axis("5", "0.9"), axis("5", "0.9"), axis("5", "0.9")),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(5.5, result.axes.first().statutoryLimit, 0.001)
        assertNull(result.groups.single().statutoryGroupLimit)
    }

    @Test fun tandemMarksGroupOverWhenOneAxleExceedsIndividualCapEvenIfGroupTotalFits() {
        val result = AxleRules.calculate(
            listOf(axis("10.6"), axis("0.5", "0.9")),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(11.1, result.groups.single().actualTotal!!, 0.001)
        assertEquals(11.5, result.groups.single().effectiveGroupLimit!!, 0.001)
        assertEquals(LoadState.OVER, result.axes.first().state)
        assertEquals(LoadState.OVER, result.groups.single().state)
    }

    @Test fun highWheelCountTandemUsesPerAxleRowNotGroupTotal() {
        val result = AxleRules.calculate(
            listOf(axis("10.5", wheels = 4), axis("10.5", "1.0", wheels = 4)),
            RoadNorm.ELEVEN_FIVE
        )
        assertNull(result.groups.single().statutoryGroupLimit)
        assertEquals(11.0, result.axes.first().statutoryLimit, 0.001)
        assertEquals(LoadState.NEAR_LIMIT, result.axes.first().state)
    }

    @Test fun highWheelCountGroupOverThreeUsesPerAxleRow() {
        val result = AxleRules.calculate(
            listOf(axis("5", wheels = 4), axis("5", "0.9", wheels = 4), axis("5", "0.9", wheels = 4), axis("5", "0.9", wheels = 4)),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(11.0, result.axes.first().statutoryLimit, 0.001)
        assertNull(result.groups.single().statutoryGroupLimit)
    }

    @Test fun lowerManufacturerAxleLimitTakesPrecedence() {
        val result = AxleRules.calculate(
            listOf(axis("8.0", own = "7.5")),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(10.5, result.axes.single().statutoryLimit, 0.001)
        assertEquals(7.5, result.axes.single().effectiveLimit, 0.001)
        assertEquals(LoadState.OVER, result.axes.single().state)
    }

    @Test fun mixedTyreGroupUsesSingleTyreGroupRow() {
        val result = AxleRules.calculate(
            listOf(axis("5", dual = true), axis("5", "1.0", dual = false)),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(11.5, result.groups.single().statutoryGroupLimit!!, 0.001)
    }

    @Test fun invalidOwnLimitIsRejected() {
        val result = AxleRules.calculate(listOf(axis("5", own = "0")), RoadNorm.ELEVEN_FIVE)
        assertTrue(result.errors.any { it.contains("Проверь предельную нагрузку") })
    }

    @Test fun signOverrideTakesPriorityAndDoesNotRequireAxleGaps() {
        val result = AxleRules.calculate(
            listOf(axis("8"), axis("8")),
            RoadNorm.ELEVEN_FIVE,
            signOverride = 7.5
        )
        assertEquals(7.5, result.axes[0].statutoryLimit, 0.001)
        assertNull(result.groups[0].statutoryGroupLimit)
        assertEquals(2, result.groups.size)
    }

    @Test fun mixedWheelCountCloseGroupIsNotSilentlyCalculated() {
        val result = AxleRules.calculate(
            listOf(axis("5", wheels = 2), axis("5", "1.0", wheels = 4)),
            RoadNorm.ELEVEN_FIVE
        )
        assertTrue(result.errors.any { it.contains("смешаны оси") })
        assertTrue(result.axes.isEmpty())
    }

    @Test fun incompleteGroupIsNotMarkedGreen() {
        val result = AxleRules.calculate(
            listOf(axis("5"), axis("", "1.0")),
            RoadNorm.ELEVEN_FIVE
        )
        assertEquals(LoadState.NOT_ENTERED, result.groups.single().state)
    }

    @Test fun vehicleGrossMassTableForTruckAndRoadTrain() {
        assertEquals(32.0, AxleRules.statutoryGrossMassLimit(VehicleType.SOLO_TRUCK, 4).tonnes!!, 0.001)
        assertEquals(44.0, AxleRules.statutoryGrossMassLimit(VehicleType.ROAD_TRAIN, 6).tonnes!!, 0.001)
    }

    @Test fun passengerCarRequiresRegistrationMassLimit() {
        assertNull(AxleRules.statutoryGrossMassLimit(VehicleType.PASSENGER_CAR, 2).tonnes)
    }

    @Test fun grossMassCanBeCalculatedFromAllAxles() {
        assertEquals(18.5, AxleRules.sumAxleLoads(listOf(axis("6.5"), axis("12.0", "3.0")))!!, 0.001)
        assertNull(AxleRules.sumAxleLoads(listOf(axis("6.5"), axis("", "3.0"))))
    }

    @Test fun fineStartsOnlyAboveTenPercent() {
        assertNull(AxleRules.overloadFineNote(10.0))
        assertTrue(AxleRules.overloadFineNote(10.01)!!.contains("300 000"))
    }

    @Test fun fineThresholdChangesAtTwentyAndFiftyPercent() {
        assertTrue(AxleRules.overloadFineNote(20.0)!!.contains("300 000"))
        assertTrue(AxleRules.overloadFineNote(20.01)!!.contains("450 000"))
        assertTrue(AxleRules.overloadFineNote(50.0)!!.contains("450 000"))
        assertTrue(AxleRules.overloadFineNote(50.01)!!.contains("600 000"))
    }
}
