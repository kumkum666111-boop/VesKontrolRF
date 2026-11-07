package ru.veskontrol.rf

import kotlin.math.min

enum class RoadNorm(val tonnes: Double, val label: String) {
    SIX(6.0, "6 т/ось"),
    TEN(10.0, "10 т/ось"),
    ELEVEN_FIVE(11.5, "11,5 т/ось")
}

enum class VehicleType(val title: String) {
    PASSENGER_CAR("Легковой автомобиль"),
    SOLO_TRUCK("Одиночный грузовик"),
    ROAD_TRAIN("Автопоезд: тягач + прицеп/полуприцеп"),
    TRAILER_ONLY("Прицеп отдельно"),
    BUS("Одиночный автобус"),
    ARTICULATED_BUS("Сочленённый автобус"),
    OTHER("Другое ТС")
}

enum class RoadCategory(val title: String) {
    UNKNOWN("Неизвестна"),
    IA("I-а"),
    IB("I-б"),
    II("II"),
    III("III"),
    IV("IV"),
    V("V")
}

data class AxleInput(
    val actualLoad: String = "",
    /** Number of wheels on the axle: 2 or 4+. */
    val wheelCount: Int = 2,
    /** true = dual-tire wheels (двускатные), false = single-tire (односкатные). */
    val dualTyres: Boolean = false,
    /** Distance from the preceding axle in metres. Required for all except axle 1. */
    val gapFromPrevious: String = "",
    /** Optional lower manufacturer/vehicle-document axle limit, tonnes. */
    val ownLimit: String = ""
)

enum class LoadState { NOT_ENTERED, OK, NEAR_LIMIT, OVER }

data class AxleEvaluation(
    val axleNumber: Int,
    val actual: Double?,
    val statutoryLimit: Double,
    val effectiveLimit: Double,
    val state: LoadState,
    val groupNumber: Int
)

data class GroupEvaluation(
    val groupNumber: Int,
    val firstAxle: Int,
    val lastAxle: Int,
    val spacingAverage: Double?,
    val actualTotal: Double?,
    val statutoryGroupLimit: Double?,
    val effectiveGroupLimit: Double?,
    val state: LoadState,
    val note: String
)

data class AxleCalculation(
    val axes: List<AxleEvaluation>,
    val groups: List<GroupEvaluation>,
    val errors: List<String>
)

data class MassLimit(
    val tonnes: Double?,
    val sourceLabel: String
)

object AxleRules {
    private data class SingleValues(val single: Double, val dual: Double)

    // Appendix 2 to Government Resolution No. 2060 of 01.12.2023.
    private val singleAxle = mapOf(
        RoadNorm.SIX to SingleValues(5.5, 6.0),
        RoadNorm.TEN to SingleValues(9.0, 10.0),
        RoadNorm.ELEVEN_FIVE to SingleValues(10.5, 11.5)
    )

    // Values are total loads for a group of 2 or 3 closely spaced axles (tonnes).
    // Each row: [gap <=1.0, <=1.3, <=1.8, <=2.5], then single-/dual-tyre.
    private val tandem = mapOf(
        RoadNorm.SIX to arrayOf(
            SingleValues(8.0, 9.0), SingleValues(9.0, 10.0),
            SingleValues(10.0, 11.0), SingleValues(11.0, 12.0)
        ),
        RoadNorm.TEN to arrayOf(
            SingleValues(10.0, 11.0), SingleValues(13.0, 14.0),
            SingleValues(15.0, 16.0), SingleValues(17.0, 18.0)
        ),
        RoadNorm.ELEVEN_FIVE to arrayOf(
            SingleValues(11.5, 12.5), SingleValues(14.0, 16.0),
            SingleValues(17.0, 18.0), SingleValues(18.0, 20.0)
        )
    )

    private val tridem = mapOf(
        RoadNorm.SIX to arrayOf(
            SingleValues(11.0, 12.0), SingleValues(12.0, 13.0),
            SingleValues(13.5, 15.0), SingleValues(15.0, 16.0)
        ),
        RoadNorm.TEN to arrayOf(
            SingleValues(15.0, 16.5), SingleValues(18.0, 19.5),
            SingleValues(21.0, 22.5), SingleValues(22.0, 23.0)
        ),
        RoadNorm.ELEVEN_FIVE to arrayOf(
            SingleValues(17.0, 18.0), SingleValues(20.0, 21.0),
            SingleValues(23.5, 24.0), SingleValues(25.0, 26.0)
        )
    )

    // More than 3 close axles with no more than 2 wheel assemblies per axle:
    // per-axle values, not a total group value.
    private val longGroupLowWheel = mapOf(
        RoadNorm.SIX to arrayOf(
            SingleValues(3.5, 4.0), SingleValues(4.0, 4.5),
            SingleValues(4.5, 5.0), SingleValues(5.0, 5.5)
        ),
        RoadNorm.TEN to arrayOf(
            SingleValues(5.0, 5.5), SingleValues(6.0, 6.5),
            SingleValues(6.5, 7.0), SingleValues(7.0, 7.5)
        ),
        RoadNorm.ELEVEN_FIVE to arrayOf(
            SingleValues(5.5, 6.0), SingleValues(6.5, 7.0),
            SingleValues(7.5, 8.0), SingleValues(8.5, 9.0)
        )
    )

    // Group of 2+ close axles with 4+ wheels on each axle:
    // values apply per axle, not as a group total.
    private val groupHighWheelPerAxle = mapOf(
        RoadNorm.SIX to doubleArrayOf(6.0, 6.5, 7.5, 8.5),
        RoadNorm.TEN to doubleArrayOf(9.5, 10.5, 12.0, 13.5),
        RoadNorm.ELEVEN_FIVE to doubleArrayOf(11.0, 12.0, 14.0, 16.0)
    )

    fun parseNumber(text: String): Double? {
        val normalized = text.trim().replace(',', '.')
        if (normalized.isEmpty()) return null
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun spacingBand(spacing: Double): Int = when {
        spacing <= 1.0 -> 0
        spacing <= 1.3 -> 1
        spacing <= 1.8 -> 2
        else -> 3
    }

    private fun singleLimit(norm: RoadNorm, dual: Boolean): Double {
        val values = singleAxle.getValue(norm)
        return if (dual) values.dual else values.single
    }

    /**
     * Calculate axle and group limits from Resolution No. 2060 Annex 2.
     * Road category is deliberately not used as a proxy for 6/10/11.5 t norms:
     * use road-owner data / signs to select the actual design norm.
     */
    fun calculate(
        axles: List<AxleInput>,
        norm: RoadNorm?,
        signOverride: Double? = null
    ): AxleCalculation {
        val errors = mutableListOf<String>()
        if (axles.isEmpty()) return AxleCalculation(emptyList(), emptyList(), listOf("Добавьте хотя бы одну ось."))
        if (norm == null && signOverride == null) {
            return AxleCalculation(emptyList(), emptyList(), listOf("Выберите норматив дороги 6, 10 или 11,5 т/ось либо введите ограничение с дорожного знака."))
        }
        if (signOverride != null && signOverride <= 0.0) {
            return AxleCalculation(emptyList(), emptyList(), listOf("Ограничение с дорожного знака должно быть больше нуля."))
        }
        axles.forEachIndexed { index, axle ->
            if (axle.actualLoad.isNotBlank() && parseNumber(axle.actualLoad) == null) {
                errors += "Проверь фактическую нагрузку на оси ${index + 1}."
            }
            if (axle.ownLimit.isNotBlank() && (parseNumber(axle.ownLimit) == null || parseNumber(axle.ownLimit)!! <= 0.0)) {
                errors += "Проверь предельную нагрузку из документов для оси ${index + 1}."
            }
        }
        if (errors.isNotEmpty()) return AxleCalculation(emptyList(), emptyList(), errors)

        // A road-owner sign specifying a permissible axle load takes precedence
        // regardless of spacing between adjacent axles (Resolution 2060 Annex 2 note 7).
        // In this case axle gaps are not required for the sign-based check.
        if (signOverride != null) {
            val axisResults = axles.mapIndexed { i, input ->
                val actual = parseNumber(input.actualLoad)
                val own = parseNumber(input.ownLimit)
                val effective = if (own == null) signOverride else min(signOverride, own)
                AxleEvaluation(i + 1, actual, signOverride, effective, state(actual, effective), i + 1)
            }
            return AxleCalculation(
                axisResults,
                axisResults.map {
                    GroupEvaluation(it.groupNumber, it.axleNumber, it.axleNumber, null,
                        it.actual, null, null, it.state,
                        "Применено ограничение с дорожного знака; межосевые интервалы не меняют это значение.")
                },
                emptyList()
            )
        }

        val roadNorm = norm ?: return AxleCalculation(emptyList(), emptyList(), listOf("Не выбран норматив дороги."))

        val gaps = mutableListOf<Double>()
        for (i in 1 until axles.size) {
            val gap = parseNumber(axles[i].gapFromPrevious)
            if (gap == null || gap <= 0.0) {
                errors += "Укажи расстояние от оси ${i} до оси ${i + 1}."
            } else {
                gaps += gap
            }
        }
        if (errors.isNotEmpty()) return AxleCalculation(emptyList(), emptyList(), errors)

        // Build groups: adjacent axles separated by no more than 2.5 m belong to one group.
        val groups = mutableListOf<MutableList<Int>>()
        var current = mutableListOf(0)
        for (i in 1 until axles.size) {
            val gap = gaps[i - 1]
            if (gap <= 2.5) current.add(i)
            else {
                groups.add(current)
                current = mutableListOf(i)
            }
        }
        groups.add(current)

        val axisResults = mutableListOf<AxleEvaluation>()
        val groupResults = mutableListOf<GroupEvaluation>()

        groups.forEachIndexed { groupIndex, indices ->
            val groupNo = groupIndex + 1
            val inputs = indices.map { axles[it] }
            val n = indices.size
            val innerGaps = if (n <= 1) emptyList() else indices.drop(1).map { gaps[it - 1] }
            val average = innerGaps.takeIf { it.isNotEmpty() }?.average()

            // If a group mixes single- and dual-tyre or low-/high-wheel-count axles,
            // use the more conservative row for the complete group.
            val allHighWheel = inputs.all { it.wheelCount >= 4 }
            val allDual = inputs.all { it.dualTyres }
            if (n > 1 && inputs.map { it.wheelCount >= 4 }.distinct().size > 1) {
                errors += "В группе осей $groupNo смешаны оси с 2 и 4+ колёсами. Для такой комбинации нет однозначной строки в приложении № 2; требуется сверка с владельцем дороги/документами ТС."
                return@forEachIndexed
            }
            val mixedNote = if (!allHighWheel && inputs.map { it.dualTyres }.distinct().size > 1)
                " Смешанная ошиновка: применена строка для односкатных колёс."
            else ""

            var perAxleLimit: Double
            var statutoryGroupLimit: Double? = null
            var note: String

            if (n == 1) {
                perAxleLimit = singleLimit(roadNorm, inputs.first().dualTyres)
                note = "Одиночная ось."
            } else {
                val band = spacingBand(average ?: 0.0)
                when {
                    allHighWheel -> {
                        // This special table row is expressed per axle for groups of 2+ axles.
                        // It is not a group-total value; dual/single tyre distinction is not bracketed here.
                        perAxleLimit = groupHighWheelPerAxle.getValue(roadNorm)[band]
                        note = "Группа из 2+ осей с 4 и более колёсами на каждой оси; значение применяется к каждой оси, а не суммарно к группе."
                    }
                    n == 2 -> {
                        val row = tandem.getValue(roadNorm)[band]
                        statutoryGroupLimit = if (allDual) row.dual else row.single
                        perAxleLimit = singleLimit(roadNorm, allDual)
                        note = "Сдвоенная группа: проверяется и сумма группы, и каждая ось отдельно."
                    }
                    n == 3 -> {
                        val row = tridem.getValue(roadNorm)[band]
                        statutoryGroupLimit = if (allDual) row.dual else row.single
                        perAxleLimit = singleLimit(roadNorm, allDual)
                        note = "Строенная группа: проверяется и сумма группы, и каждая ось отдельно."
                    }
                    else -> {
                        val row = longGroupLowWheel.getValue(roadNorm)[band]
                        perAxleLimit = if (allDual) row.dual else row.single
                        note = "Группа более чем из 3 сближенных осей; значение применяется к каждой оси."
                    }
                }
            }

            val effectiveAxisLimits = indices.map { index ->
                val own = parseNumber(axles[index].ownLimit)
                if (own == null) perAxleLimit else min(perAxleLimit, own)
            }
            indices.forEachIndexed { local, inputIndex ->
                val actual = parseNumber(axles[inputIndex].actualLoad)
                val effective = effectiveAxisLimits[local]
                axisResults += AxleEvaluation(
                    inputIndex + 1, actual, perAxleLimit, effective,
                    state(actual, effective), groupNo
                )
            }

            val actualTotal = inputs.mapNotNull { parseNumber(it.actualLoad) }
                .takeIf { it.size == inputs.size }?.sum()
            val effectiveGroupLimit = statutoryGroupLimit?.let { stat ->
                min(stat, effectiveAxisLimits.sum())
            }
            val perAxleStates = indices.mapIndexed { local, index ->
                state(parseNumber(axles[index].actualLoad), effectiveAxisLimits[local])
            }
            val groupState = when {
                perAxleStates.any { it == LoadState.OVER } -> LoadState.OVER
                effectiveGroupLimit != null && actualTotal != null && actualTotal > effectiveGroupLimit -> LoadState.OVER
                perAxleStates.any { it == LoadState.NEAR_LIMIT } -> LoadState.NEAR_LIMIT
                effectiveGroupLimit != null && actualTotal != null && actualTotal >= effectiveGroupLimit * 0.90 -> LoadState.NEAR_LIMIT
                perAxleStates.any { it == LoadState.NOT_ENTERED } -> LoadState.NOT_ENTERED
                actualTotal == null -> LoadState.NOT_ENTERED
                else -> LoadState.OK
            }
            groupResults += GroupEvaluation(
                groupNo, indices.first() + 1, indices.last() + 1, average,
                actualTotal, statutoryGroupLimit, effectiveGroupLimit,
                groupState, note + mixedNote
            )
        }
        if (errors.isNotEmpty()) return AxleCalculation(emptyList(), emptyList(), errors)
        return AxleCalculation(axisResults, groupResults, emptyList())
    }

    private fun state(actual: Double?, limit: Double): LoadState = when {
        actual == null -> LoadState.NOT_ENTERED
        actual > limit -> LoadState.OVER
        actual >= limit * 0.90 -> LoadState.NEAR_LIMIT
        else -> LoadState.OK
    }

    private fun combineStates(states: List<LoadState>): LoadState = when {
        states.any { it == LoadState.OVER } -> LoadState.OVER
        states.any { it == LoadState.NEAR_LIMIT } -> LoadState.NEAR_LIMIT
        states.any { it == LoadState.NOT_ENTERED } -> LoadState.NOT_ENTERED
        else -> LoadState.OK
    }

    /** Annex 3 to Resolution No. 2060: statutory maximum gross mass in tonnes. */
    fun statutoryGrossMassLimit(vehicle: VehicleType, totalAxles: Int): MassLimit {
        val limit = when (vehicle) {
            VehicleType.SOLO_TRUCK -> when {
                totalAxles == 2 -> 18.0
                totalAxles == 3 -> 25.0
                totalAxles == 4 -> 32.0
                totalAxles >= 5 -> 38.0
                else -> null
            }
            VehicleType.ROAD_TRAIN -> when {
                totalAxles == 3 -> 28.0
                totalAxles == 4 -> 36.0
                totalAxles == 5 -> 40.0
                totalAxles >= 6 -> 44.0
                else -> null
            }
            VehicleType.BUS -> when {
                totalAxles == 2 -> 18.0
                totalAxles == 3 -> 25.0
                totalAxles == 4 -> 32.0
                else -> null
            }
            VehicleType.ARTICULATED_BUS -> when {
                totalAxles == 3 -> 28.0
                totalAxles == 4 -> 36.0
                totalAxles >= 5 -> 40.0
                else -> null
            }
            VehicleType.PASSENGER_CAR, VehicleType.TRAILER_ONLY, VehicleType.OTHER -> null
        }
        return MassLimit(limit, if (limit == null) "Нужен предел из ПТС/СТС, таблички изготовителя или специального разрешения" else "Постановление Правительства РФ № 2060, приложение № 3")
    }

    /** Returns the gross mass as the sum of measured axle loads only when every axle has a value. */
    fun sumAxleLoads(axles: List<AxleInput>): Double? {
        if (axles.isEmpty()) return null
        val values = axles.map { parseNumber(it.actualLoad) }
        return if (values.all { it != null }) values.filterNotNull().sum() else null
    }

    fun evaluateMass(actual: Double?, limit: Double?): LoadState {
        if (limit == null || limit <= 0.0) return LoadState.NOT_ENTERED
        return state(actual, limit)
    }

    fun overloadFineNote(overPercent: Double): String? = when {
        overPercent > 50.0 -> "При превышении более 50% статья 12.21.1 КоАП РФ предусматривает штраф 600 000 ₽ для собственника/владельца ТС."
        overPercent > 20.0 -> "При превышении более 20%, но не более 50% статья 12.21.1 КоАП РФ предусматривает штраф 450 000 ₽ для собственника/владельца ТС."
        overPercent > 10.0 -> "При превышении более 10%, но не более 20% статья 12.21.1 КоАП РФ предусматривает штраф 300 000 ₽ для собственника/владельца ТС."
        else -> null
    }
}
