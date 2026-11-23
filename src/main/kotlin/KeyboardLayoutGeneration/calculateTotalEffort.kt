package KeyboardLayoutGeneration

import keyboard_analysis.CombinedLayoutData
import keyboard_analysis.CorpusData
import keyboard_analysis.LayoutAnalysisResult
import keyboard_analysis.completeLayoutAnalysis
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

// --- 1. BÜNTETŐ SÚLYOK (Minimalizálandó értékek -> növelik a Total Effortot) ---
var weightCharReachEffort = 40.0
var weightHandImbalance = 4.0

// Cél: < 4-6%
var weightPinkyOff = 2.0

// Cél: < 1-1.5%
var weightSfb = 15.0  //original 10

// Cél: < 0.5% (ÚJ)
var weightFullScissor = 15.0  //original 15
var weightHalfScissor = weightFullScissor / 2 //original :2

// Cél: < 1.5-2% (ÚJ)
var weightLateralStretch = 8.0
var weightPinkyStretch = 8.0

// Cél: < 2-3%
var weightRedirect = 4.0

var weightSpaceSfs1 = 2.0

var weightGravity = 0.5

// Soronkénti terhelés súlyai (0: legfelső sor ... 5: thumb sor)
//TODO megvalósítani, hogy magától tudja a program, hogy hány soros ez a billentyűzet és annak függvényében kiosztani ezeket a büntetőpontokat
//az ujjak hossza bekerült a fizikai layout effort modellbe, így egyelőre ezek az értékek 0 súlyt kapnak
var weightRow0 = 0.0
var weightRow1 = 0.0
var weightRow2 = 0.0
var weightRow3 = 0.0 // Home row (ezt külön jutalmazzuk lent)
var weightRow4 = 0.0
var weightRow5 = 0.0 // Thumb row

// --- 2. JUTALMAZÓ SÚLYOK (Maximalizálandó értékek -> csökkentik a Total Effortot) ---
var weightHomeRow = 0.85

// Cél: minél magasabb, együttesen > 40-50%
var weightAlternation = 1.0
var weightRoll = 1.3
var weightThreeRoll = 1.6

fun resetDefaultWeights() {
    weightCharReachEffort = 40.0
    weightHandImbalance = 4.0
    weightPinkyOff = 2.0
    weightSfb = 15.0
    weightFullScissor = 15.0
    weightHalfScissor = 7.5
    weightLateralStretch = 8.0
    weightPinkyStretch = 8.0
    weightRedirect = 4.0
    weightSpaceSfs1 = 2.0
    weightGravity = 0.5
    weightRow0 = 0.0
    weightRow1 = 0.0
    weightRow2 = 0.0
    weightRow3 = 0.0
    weightRow4 = 0.0
    weightRow5 = 0.0
    weightHomeRow = 0.85
    weightAlternation = 1.0
    weightRoll = 1.3
    weightThreeRoll = 1.6
}

/**
 * Kiszámítja a billentyűzetkiosztás összesített jósági/terhelési mutatóját (Célfüggvény).
 * Minél KISEBB a visszaadott érték, annál jobb a kiosztás.
 */
fun calculateTotalEffort(result: LayoutAnalysisResult): Double {

    // Kéz-aszimmetria kiszámítása: |bal% - jobb%|
    //val handImbalance = kotlin.math.abs(result.handBalanceLeft - result.handBalanceRight)

    val lessUsedHandPercentage = minOf(result.handBalanceLeft, result.handBalanceRight)
    val handDifference = 50.0 - lessUsedHandPercentage

    val handImbalance = when {
        handDifference <= 5.0 -> {
            0.04 * (5.0 - handDifference) * (5.0 - handDifference)
        }
        handDifference <= 7.0 -> {
            0.5 * (handDifference - 5.0) * (handDifference - 5.0)
        }
        else -> {
            val diff = handDifference - 7.0
            // C1 folytonos harmadfokú folytatás: a k=7 pontban az érték (2.0)
            // és a meredekség (2.0) is tökéletesen illeszkedik a fenti másodfokú ághoz.
            diff * diff * diff + 2.0 * diff + 2.0
        }
    }

    // Sorok súlyozott összege (index-biztos lekéréssel, ha változna a sorok száma)
    val rowPenalty =
        (result.rowPercentages.getOrElse(0) { 0.0 } * weightRow0) +
                (result.rowPercentages.getOrElse(1) { 0.0 } * weightRow1) +
                (result.rowPercentages.getOrElse(2) { 0.0 } * weightRow2) +
                (result.rowPercentages.getOrElse(3) { 0.0 } * weightRow3) +
                (result.rowPercentages.getOrElse(4) { 0.0 } * weightRow4) +
                (result.rowPercentages.getOrElse(5) { 0.0 } * weightRow5)

    // Büntetések összege (beleértve a két új metrikát)
    val penalties =
        (result.charReachEffort * weightCharReachEffort) +
                (result.baseGravityEffort * weightGravity) +
                (handImbalance * weightHandImbalance) +
                (result.pinkyOffPercentage * weightPinkyOff) +
                (result.sfbPercentage * weightSfb) +
                (result.fullScissorPercentage * weightFullScissor) +
                (result.halfScissorPercentage * weightHalfScissor) +
                (result.lateralStretchPercentage * weightLateralStretch) +
                (result.pinkystretchPercentage * weightPinkyStretch) +
                (result.redirectPercentage * weightRedirect) +
                (result.spaceSfs1Penalty * weightSpaceSfs1) +
                rowPenalty

    // Jutalmak összege (a thumb sor leütései is megkapják a home row pozitív súlyát)
    val rewards =
        ((result.homeRowPercentage + result.thumbRowPercentage) * weightHomeRow) +
                (result.alternationPercentage * weightAlternation) +
                (result.rollPercentage * weightRoll) +
                (result.threerollPercentage * weightThreeRoll)

    return penalties - rewards
}

data class RawRunLogEntry(
    val runIndex: Int,
    val effort: Double,
    val mode: String
)

data class PolishedRunLogEntry(
    val runIndex: Int,
    val beforeEffort: Double,
    val afterEffort: Double,
    val mode: String
) {
    val delta: Double get() = afterEffort - beforeEffort
}

internal data class RunCandidate(
    val runIndex: Int,
    val effort: Double,
    val layout: CombinedLayoutData,
    val mode: String
)

internal data class PolishedCandidate(
    val runIndex: Int,
    val beforeEffort: Double,
    val afterEffort: Double,
    val layout: CombinedLayoutData,
    val mode: String
) {
    val delta: Double get() = afterEffort - beforeEffort
}

private fun calculatePercentile(sortedValues: List<Double>, percentile: Double): Double {
    if (sortedValues.isEmpty()) return Double.NaN
    if (sortedValues.size == 1) return sortedValues[0]
    val rank = (percentile / 100.0) * (sortedValues.size - 1)
    val lower = rank.toInt()
    val upper = minOf(lower + 1, sortedValues.size - 1)
    val fraction = rank - lower
    return sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower])
}

private fun formatEffortStats(label: String, values: List<Double>): List<String> {
    if (values.isEmpty()) return emptyList()
    val sorted = values.sorted()
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    val stdDev = sqrt(variance)
    val p10 = calculatePercentile(sorted, 10.0)
    val p25 = calculatePercentile(sorted, 25.0)
    val median = calculatePercentile(sorted, 50.0)
    val p75 = calculatePercentile(sorted, 75.0)
    val p90 = calculatePercentile(sorted, 90.0)

    fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)

    return listOf(
        "$label (n=${values.size}):",
        " - Min: ${fmt(sorted.first())} | Max: ${fmt(sorted.last())} | Átlag: ${fmt(mean)} | Szórás: ${fmt(stdDev)}",
        " - P10: ${fmt(p10)} | P25: ${fmt(p25)} | Medián (P50): ${fmt(median)} | P75: ${fmt(p75)} | P90: ${fmt(p90)}"
    )
}

/**
 * Egységes részletes log író függvény minden optimalizálóhoz (Saját SA, Chips-n-Salsa, MOEA).
 * Automatikusan kiírja a futási paramétereket, a korpusz nevét, az összes célfüggvény-súlyt (weights),
 * a Top 3 helyezett adatait, a statisztikai összefoglalót, a 2-opt csiszolt jelöltek előtte->utána értékeit,
 * az összes nyers futás effort értékét, valamint opcionálisan egy tiszta CSV fájlt az utólagos elemzéshez.
 */
fun writeDetailedLog(
    logFile: File,
    logTitle: String,
    keyboardName: String,
    timestamp: String,
    corpusName: String,
    algorithmDetails: List<String>,
    totalTimeSeconds: Double,
    topResults: List<Pair<Double, String>>,
    rawRunResults: List<RawRunLogEntry> = emptyList(),
    polishedResults: List<PolishedRunLogEntry> = emptyList(),
    csvFile: File? = null,
    algorithmTag: String = ""
) {
    fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)
    fun fmtDelta(v: Double) = String.format(Locale.US, "%+.4f", v)

    val sortedRaw = rawRunResults.sortedBy { it.effort }
    val sortedPolished = polishedResults.sortedBy { it.afterEffort }

    logFile.bufferedWriter().use { writer ->
        writer.write("=== $logTitle ===")
        writer.newLine()
        writer.write("Billentyűzet: $keyboardName")
        writer.newLine()
        writer.write("Futtatás időpontja: $timestamp")
        writer.newLine()
        writer.write("Korpusz neve: $corpusName")
        writer.newLine()
        for (detail in algorithmDetails) {
            writer.write(detail)
            writer.newLine()
        }
        writer.write("Teljes futásidő: ${String.format(Locale.US, "%.1f", totalTimeSeconds)} mp")
        writer.newLine()
        writer.newLine()

        writer.write("--- CÉLFÜGGVÉNY SÚLYOK (WEIGHTS) ---")
        writer.newLine()
        writer.write("Büntető súlyok (Penalties):")
        writer.newLine()
        writer.write(" - weightCharReachEffort: $weightCharReachEffort")
        writer.newLine()
        writer.write(" - weightGravity:         $weightGravity")
        writer.newLine()
        writer.write(" - weightHandImbalance:   $weightHandImbalance")
        writer.newLine()
        writer.write(" - weightPinkyOff:        $weightPinkyOff")
        writer.newLine()
        writer.write(" - weightSfb:             $weightSfb")
        writer.newLine()
        writer.write(" - weightFullScissor:     $weightFullScissor")
        writer.newLine()
        writer.write(" - weightHalfScissor:     $weightHalfScissor")
        writer.newLine()
        writer.write(" - weightLateralStretch:  $weightLateralStretch")
        writer.newLine()
        writer.write(" - weightPinkyStretch:    $weightPinkyStretch")
        writer.newLine()
        writer.write(" - weightRedirect:        $weightRedirect")
        writer.newLine()
        writer.write(" - weightSpaceSfs1:       $weightSpaceSfs1")
        writer.newLine()
        writer.write(" - weightRows (0..5):     [$weightRow0, $weightRow1, $weightRow2, $weightRow3, $weightRow4, $weightRow5]")
        writer.newLine()
        writer.write("Jutalmazó súlyok (Rewards):")
        writer.newLine()
        writer.write(" - weightHomeRow:         $weightHomeRow")
        writer.newLine()
        writer.write(" - weightAlternation:     $weightAlternation")
        writer.newLine()
        writer.write(" - weightRoll:            $weightRoll")
        writer.newLine()
        writer.write(" - weightThreeRoll:       $weightThreeRoll")
        writer.newLine()
        writer.newLine()

        writer.write("--- TOP ${topResults.size} EREDMÉNY ---")
        writer.newLine()

        for ((index, pair) in topResults.withIndex()) {
            val rank = index + 1
            val (effort, originStr) = pair
            writer.write("$rank. Helyezett:")
            writer.newLine()
            writer.write(" - Effort pontszám: ${fmt(effort)}")
            writer.newLine()
            writer.write(" - Származás: $originStr")
            writer.newLine()
            writer.newLine()
        }

        if (sortedRaw.isNotEmpty() || sortedPolished.isNotEmpty()) {
            writer.write("--- STATISZTIKAI ÖSSZEFOGLALÓ ---")
            writer.newLine()

            if (sortedRaw.isNotEmpty()) {
                for (line in formatEffortStats("Összes nyers futás (csiszolás előtt)", sortedRaw.map { it.effort })) {
                    writer.write(line)
                    writer.newLine()
                }

                val modes = sortedRaw.map { it.mode }.distinct()
                if (modes.size > 1) {
                    for (mode in modes) {
                        val modeEfforts = sortedRaw.filter { it.mode == mode }.map { it.effort }
                        for (line in formatEffortStats("Nyers futások [$mode]", modeEfforts)) {
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }
                writer.newLine()
            }

            if (sortedPolished.isNotEmpty()) {
                for (line in formatEffortStats("2-opt csiszolt jelöltek (utána)", sortedPolished.map { it.afterEffort })) {
                    writer.write(line)
                    writer.newLine()
                }
                val deltas = sortedPolished.map { it.delta }
                val avgDelta = deltas.average()
                val bestDelta = deltas.min() // Legnagyobb javulás (legnegatívabb delta)
                val worstDelta = deltas.max()
                val improvedCount = deltas.count { it < -1e-9 }
                writer.write(
                    " - 2-opt javulás (delta): Átlag: ${fmtDelta(avgDelta)} | Legnagyobb javulás: ${fmtDelta(bestDelta)} | Legkisebb változás: ${fmtDelta(worstDelta)} | Javult: $improvedCount/${sortedPolished.size}"
                )
                writer.newLine()
                writer.newLine()
            }
        }

        if (sortedPolished.isNotEmpty()) {
            writer.write("--- 2-OPT CSISZOLT JELÖLTEK (${sortedPolished.size} db, csiszolt effort szerint rendezve) ---")
            writer.newLine()
            for ((idx, entry) in sortedPolished.withIndex()) {
                writer.write(
                    "#${idx + 1} (Szál #${entry.runIndex}) | Előtte: ${fmt(entry.beforeEffort)} -> Utána: ${fmt(entry.afterEffort)} (Delta: ${fmtDelta(entry.delta)}) | Mód: ${entry.mode}"
                )
                writer.newLine()
            }
            writer.newLine()
        }

        if (sortedRaw.isNotEmpty()) {
            writer.write("--- ÖSSZES NYERS FUTÁS CSISZOLÁS ELŐTT (${sortedRaw.size} db, effort szerint rendezve) ---")
            writer.newLine()
            for ((idx, entry) in sortedRaw.withIndex()) {
                writer.write(
                    "#${idx + 1} (Szál #${entry.runIndex}) | Effort: ${fmt(entry.effort)} | Mód: ${entry.mode}"
                )
                writer.newLine()
            }
            writer.newLine()
        }
    }
    println("Log fájl elmentve: ${logFile.absolutePath}")

    if (csvFile != null && (sortedRaw.isNotEmpty() || sortedPolished.isNotEmpty())) {
        val algoName = algorithmTag.ifBlank { logTitle }.replace(",", " ")
        csvFile.bufferedWriter().use { writer ->
            writer.write("algorithm,stage,rank,run_index,mode,effort_before,effort_after,delta")
            writer.newLine()
            for ((idx, entry) in sortedRaw.withIndex()) {
                writer.write(
                    "$algoName,raw,${idx + 1},${entry.runIndex},${entry.mode},${fmt(entry.effort)},,"
                )
                writer.newLine()
            }
            for ((idx, entry) in sortedPolished.withIndex()) {
                writer.write(
                    "$algoName,polished,${idx + 1},${entry.runIndex},${entry.mode},${fmt(entry.beforeEffort)},${fmt(entry.afterEffort)},${fmt(entry.delta)}"
                )
                writer.newLine()
            }
        }
        println("CSV fájl elmentve: ${csvFile.absolutePath}")
    }
}


/**
 * Simulated Annealing alapú optimalizáló függvény.
 * - Korlátozza a forró fázis hosszát nagy iterációszámok esetén is (fix keveredési idő).
 * - Az eredeti layoutot finomhangoló szálakat (applyHotShuffle = false) alacsonyabb T-ről indítja,
 *   hogy ne olvassza szét a kiindulási struktúrát.
 * - Újramelegítéskor (Reheating) újraszámolja a hűtési rátát a hátralévő lépésekre.
 */
fun improveLayout(
    initialLayout: CombinedLayoutData,
    corpusData: CorpusData,
    iterations: Int = 50000,
    initialTemp: Double = 10.0,
    finalTemp: Double = 0.005,
    applyHotShuffle: Boolean = true,
    verbose:Boolean = false
): CombinedLayoutData {
    // Dolgozzunk egy másolaton, hogy az eredeti objektum érintetlen maradjon, ha kell
    var currentLayout = initialLayout.deepCopy("${initialLayout.name}_current")
    val mainRows = currentLayout.numRows - 1

    // Különböző termikus profil a kevert (globális kereső) és az eredeti (finomhangoló) szálaknak
    val midTemp = 1.5
    val hotPhaseSteps: Int
    var temp: Double

    if (applyHotShuffle) {
        // 1. Globális keresés: összekeverjük a táblát, és adunk neki egy rövid,
        // maximum 2500 lépéses forró rendeződési fázist (T = 10.0 -> 1.5)
        for (i in 1..50) {
            mutateSwapRandomKeys(currentLayout, leftThumbEnabled = false, rightThumbEnabled = false, mainRows = mainRows)
        }
        hotPhaseSteps = minOf(2500, (iterations * 0.15).toInt()).coerceAtLeast(1)
        temp = initialTemp
    } else {
        // 2. Eredeti layout finomhangolása: nem keverjük össze, és közvetlenül
        // T = 1.5-ről indítjuk, hogy ne verje szét a meglévő jó struktúrát
        hotPhaseSteps = 0
        temp = midTemp
    }

    var currentAnalysis = completeLayoutAnalysis(currentLayout, corpusData, verbose = false)
    var currentEnergy = currentAnalysis.totalEffort

    var bestLayout = currentLayout.deepCopy("${initialLayout.name}_optimized")
    var bestEnergy = currentEnergy

    // Hűtési ráták: a forró fázis után mindig a hátralévő lépésekre skálázunk
    val hotCoolingRate = if (hotPhaseSteps > 0) (midTemp / initialTemp).pow(1.0 / hotPhaseSteps) else 1.0
    var mainCoolingRate = (finalTemp / midTemp).pow(1.0 / (iterations - hotPhaseSteps).coerceAtLeast(1))

    var stepsSinceLastBest = 0

    val modeStr = if (applyHotShuffle) "Hot Shuffle (Forró fázis: $hotPhaseSteps lépés)" else "Eredeti finomhangolás (T0 = $midTemp)"
    println("=== Optimalizálás indítása: ${initialLayout.name} [$modeStr] ===")
    println("Kezdeti Total Effort: ${String.format("%.4f", currentEnergy)}")

    for (step in 1..iterations) {
        val candidateLayout = currentLayout.deepCopy()
        performMutation(
            layout = candidateLayout,
            temp = temp,
            leftThumbEnabled = false,
            rightThumbEnabled = false,
            mainRows = mainRows
        )

        // 2. Kiértékeljük az új állapotot (verbose = false, hogy ne írjon a konzolra és fájlba!)
        val candidateAnalysis = completeLayoutAnalysis(candidateLayout, corpusData, verbose = false)
        val candidateEnergy = candidateAnalysis.totalEffort

        // 3. Energiakülönbség (Delta E)
        val deltaE = candidateEnergy - currentEnergy

        // 4. Metropolis-kritérium: elfogadjuk-e az új állapotot?
        val accept = if (deltaE < 0.0) {
            true // Jobb állapot -> mindig elfogadjuk
        } else {
            val acceptanceProbability = exp(-deltaE / temp)
            Random.nextDouble() < acceptanceProbability // Rosszabb állapot -> valószínűségi elfogadás
        }

        if (accept) {
            currentLayout = candidateLayout
            currentEnergy = candidateEnergy

            if (currentEnergy < bestEnergy) {
                bestLayout = currentLayout.deepCopy("${initialLayout.name}_optimized")
                bestEnergy = currentEnergy
                stepsSinceLastBest = 0
                if(verbose) println("[Lépés: $step | T: ${String.format("%.4f", temp)}] Új legjobb Effort: ${String.format("%.4f", bestEnergy)}")
            } else {
                stepsSinceLastBest++
            }
        } else {
            stepsSinceLastBest++
        }

        // --- ÚJRAMELEGÍTÉS (REHEATING) ÉS HŰTÉS ---
        // Ha 4000 lépése stagnálunk, már túl vagyunk a forró fázison, és még van hátra legalább 15% idő a lehűlésre
        if (stepsSinceLastBest > 4000 && step > hotPhaseSteps && step < iterations * 0.85) {
            currentLayout = bestLayout.deepCopy("${initialLayout.name}_current")
            currentEnergy = bestEnergy
            temp = 1.2 // Visszamelegítés az átmeneti zónába
            stepsSinceLastBest = 0

            // KRITIKUS: Újraszámoljuk a hűtési rátát a HÁTRALÉVŐ lépésekre, hogy a végére biztosan elérje a finalTemp-et!
            val remainingSteps = (iterations - step).coerceAtLeast(1)
            mainCoolingRate = (finalTemp / temp).pow(1.0 / remainingSteps)

            println("[Lépés: $step] ÚJRAMELEGÍTÉS! T = $temp, új coolingRate a hátralévő $remainingSteps lépésre: ${String.format("%.6f", mainCoolingRate)}")
        } else {
            if (step <= hotPhaseSteps) {
                temp *= hotCoolingRate
            } else {
                temp *= mainCoolingRate
            }
        }
    }

    println("=== Optimalizálás befejezve (Végső T: ${String.format("%.5f", temp)}) ===")
    println("Végső legjobb Total Effort: ${String.format("%.4f", bestEnergy)}")

    return bestLayout
}

/**
 * Segédfüggvény: Hőmérsékleti zónák szerint súlyozva választ a mikro- és makromutációk között.
 * A korábbi makromutációk (oszlopcsere, helyi/globális sorcsere, kéztükrözés) végig aktívak maradnak.
 *
 * Eloszlás zónánként:
 * - Forró (T > 2.0):   30% globál 2-es, 15% szomszédos 2-es, 15% 3-as ciklus | 15% oszlop, 10% helyi sor, 10% globál sor, 5% tükör (40% makro)
 * - Átmeneti (T > 0.2): 35% globál 2-es, 25% szomszédos 2-es, 20% 3-as ciklus | 8% oszlop, 5% helyi sor, 4% globál sor, 3% tükör (20% makro)
 * - Hideg (T <= 0.2):  37% globál 2-es, 35% szomszédos 2-es, 20% 3-as ciklus | 3% oszlop, 2% helyi sor, 2% globál sor, 1% tükör (8% makro)
 */
private fun performMutation(
    layout: CombinedLayoutData,
    temp: Double,
    leftThumbEnabled: Boolean = false,
    rightThumbEnabled: Boolean = false,
    mainRows: Int
) {
    val mutation = Random.nextDouble(0.0, 1.0)

    // Kumulatív valószínűségi határok a 7 művelethez:
    // [1: GlobalSwap2, 2: AdjacentSwap2, 3: CycleSwap3, 4: SwapCols, 5: SwapRowsLocal, 6: SwapRowsGlobal, 7: MirrorHand]
    val thresholds = when {
        temp > 2.0 -> doubleArrayOf(0.30, 0.45, 0.60, 0.75, 0.85, 0.95)
        temp > 0.2 -> doubleArrayOf(0.35, 0.60, 0.80, 0.88, 0.93, 0.97)
        else       -> doubleArrayOf(0.37, 0.72, 0.92, 0.95, 0.97, 0.99)
    }

    when {
        // 1. Globális 2-es csere (bárhol a táblán, '_' hellyel is cserélhet)
        mutation < thresholds[0] -> {
            mutateSwapRandomKeys(layout, leftThumbEnabled, rightThumbEnabled, mainRows)
        }

        // 2. Lokális (szomszédos) 2-es csere (azonos kézen +-1 sor/oszlop, '_' helyre is beléphet)
        mutation < thresholds[1] -> {
            mutateSwapAdjacentKeys(layout, mainRows)
        }

        // 3. Hárombetűs cikluscsere (A -> B -> C -> A, legfeljebb egy '_' hellyel)
        mutation < thresholds[2] -> {
            mutateSwapThreeKeysCycle(layout, leftThumbEnabled, rightThumbEnabled, mainRows)
        }

        // 4. Oszlopcsere (eredeti művelet)
        mutation < thresholds[3] -> {
            val left1 = Random.nextBoolean()
            val maxCols1 = if (left1) layout.leftChars[0].size else layout.rightChars[0].size
            val col1 = Random.nextInt(maxCols1)

            val left2 = Random.nextBoolean()
            val maxCols2 = if (left2) layout.leftChars[0].size else layout.rightChars[0].size
            val col2 = Random.nextInt(maxCols2)

            layout.swapColumns(left1, col1, left2, col2)
        }

        // 5. Helyi sorcsere (eredeti művelet)
        mutation < thresholds[4] -> {
            val isLeft = Random.nextBoolean()
            if (mainRows >= 2) {
                val r1 = Random.nextInt(mainRows)
                var r2 = Random.nextInt(mainRows)
                while (r2 == r1) {
                    r2 = Random.nextInt(mainRows)
                }
                layout.swapRowsLocal(isLeft, r1, r2)
            }
        }

        // 6. Globális sorcsere (eredeti művelet)
        mutation < thresholds[5] -> {
            if (mainRows >= 2) {
                val r1 = Random.nextInt(mainRows)
                var r2 = Random.nextInt(mainRows)
                while (r2 == r1) {
                    r2 = Random.nextInt(mainRows)
                }
                layout.swapRowsGlobal(r1, r2)
            }
        }

        // 7. Kézen belüli tükrözés (eredeti művelet)
        else -> {
            val isLeft = Random.nextBoolean()
            layout.mirrorHand(isLeft)
        }
    }
}

/**
 * Globális 2-es csere: két tetszőleges pozíciót felcserél, ha nem mindkettő '_'.
 */
private fun mutateSwapRandomKeys(
    layout: CombinedLayoutData,
    leftThumbEnabled: Boolean,
    rightThumbEnabled: Boolean,
    mainRows: Int
) {
    while (true) {
        val left1 = Random.nextBoolean()
        val maxRow1 = if (left1 && leftThumbEnabled || !left1 && rightThumbEnabled) layout.numRows else mainRows
        val r1 = Random.nextInt(maxRow1)
        val grid1 = if (left1) layout.leftChars else layout.rightChars
        val c1 = Random.nextInt(grid1[r1].size)

        val left2 = Random.nextBoolean()
        val maxRow2 = if (left2 && leftThumbEnabled || !left2 && rightThumbEnabled) layout.numRows else mainRows
        val r2 = Random.nextInt(maxRow2)
        val grid2 = if (left2) layout.leftChars else layout.rightChars
        val c2 = Random.nextInt(grid2[r2].size)

        if (left1 == left2 && r1 == r2 && c1 == c2) continue

        val ch1 = grid1[r1][c1]
        val ch2 = grid2[r2][c2]

        if (ch1 == '_' && ch2 == '_') continue
        layout.swapKeys(left1, r1, c1, left2, r2, c2)
        break
    }
}

/**
 * Szomszédos (lokális) 2-es csere azonos kézen belül (+-1 sor és/vagy +-1 oszlop).
 * Megengedi, hogy egy karakter egy mellette lévő '_' üres helyre vándoroljon át.
 */
private fun mutateSwapAdjacentKeys(layout: CombinedLayoutData, mainRows: Int) {
    for (attempt in 1..25) {
        val isLeft = Random.nextBoolean()
        val grid = if (isLeft) layout.leftChars else layout.rightChars
        val r1 = Random.nextInt(mainRows)
        val cols = grid[r1].size
        val c1 = Random.nextInt(cols)

        val dr = Random.nextInt(-1, 2)
        val dc = Random.nextInt(-1, 2)
        if (dr == 0 && dc == 0) continue

        val r2 = (r1 + dr).coerceIn(0, mainRows - 1)
        val c2 = (c1 + dc).coerceIn(0, cols - 1)

        if (r1 == r2 && c1 == c2) continue

        val ch1 = grid[r1][c1]
        val ch2 = grid[r2][c2]

        // Csak akkor ugorjuk át, ha mindkettő '_' (vagy azonos karakter)
        if (ch1 == ch2) continue

        layout.swapKeys(isLeft, r1, c1, isLeft, r2, c2)
        return
    }
    // Biztonsági tartalék, ha nem talált volna szomszédot
    mutateSwapRandomKeys(layout, false, false, mainRows)
}

/**
 * 3-as cikluscsere (poz1 <- poz2 <- poz3 <- poz1).
 * A ch1 != ch2 && ch2 != ch3 && ch1 != ch3 feltétel garantálja, hogy a 3 pozíció
 * különböző, és legfeljebb egy '_' üres hely szerepelhet benne.
 */
private fun mutateSwapThreeKeysCycle(
    layout: CombinedLayoutData,
    leftThumbEnabled: Boolean,
    rightThumbEnabled: Boolean,
    mainRows: Int
) {
    while (true) {
        val left1 = Random.nextBoolean()
        val maxRow1 = if (left1 && leftThumbEnabled || !left1 && rightThumbEnabled) layout.numRows else mainRows
        val r1 = Random.nextInt(maxRow1)
        val grid1 = if (left1) layout.leftChars else layout.rightChars
        val c1 = Random.nextInt(grid1[r1].size)
        val ch1 = grid1[r1][c1]

        val left2 = Random.nextBoolean()
        val maxRow2 = if (left2 && leftThumbEnabled || !left2 && rightThumbEnabled) layout.numRows else mainRows
        val r2 = Random.nextInt(maxRow2)
        val grid2 = if (left2) layout.leftChars else layout.rightChars
        val c2 = Random.nextInt(grid2[r2].size)
        val ch2 = grid2[r2][c2]

        if (ch1 == ch2) continue

        val left3 = Random.nextBoolean()
        val maxRow3 = if (left3 && leftThumbEnabled || !left3 && rightThumbEnabled) layout.numRows else mainRows
        val r3 = Random.nextInt(maxRow3)
        val grid3 = if (left3) layout.leftChars else layout.rightChars
        val c3 = Random.nextInt(grid3[r3].size)
        val ch3 = grid3[r3][c3]

        if (ch3 == ch1 || ch3 == ch2) continue

        layout.swapKeysCycle3(left1, r1, c1, left2, r2, c2, left3, r3, c3)
        break
    }
}

/**
 * Determinisztikus 2-opt végső csiszolás (Steepest Descent).
 * Garantálja, hogy a visszaadott layout szigorú 2-opt lokális minimumban van
 * (nincs két olyan pozíció a táblán, amelyek felcserélése javítana az efforton).
 */
fun polishLayout2Opt(
    initialLayout: CombinedLayoutData,
    corpusData: CorpusData,
    leftThumbEnabled: Boolean = false,
    rightThumbEnabled: Boolean = false
): CombinedLayoutData {
    val currentLayout = initialLayout.deepCopy("${initialLayout.name}_polished")
    var currentEnergy = completeLayoutAnalysis(currentLayout, corpusData, verbose = false).totalEffort
    val mainRows = currentLayout.numRows - 1

    // Összegyűjtjük az összes fő mátrixbeli koordinátát: (isLeft, row, col)
    val positions = mutableListOf<Triple<Boolean, Int, Int>>()
    for (r in 0 until mainRows) {
        for (c in currentLayout.leftChars[r].indices) positions.add(Triple(true, r, c))
        for (c in currentLayout.rightChars[r].indices) positions.add(Triple(false, r, c))
    }

    // Ha engedélyezve van a bal vagy jobb hüvelykujj, felvesszük az 1 elemű thumb sor (col = 0) koordinátáját is
    val thumbRow = currentLayout.numRows - 1
    if (leftThumbEnabled && thumbRow in currentLayout.leftChars.indices && currentLayout.leftChars[thumbRow].isNotEmpty()) {
        positions.add(Triple(true, thumbRow, 0))
    }
    if (rightThumbEnabled && thumbRow in currentLayout.rightChars.indices && currentLayout.rightChars[thumbRow].isNotEmpty()) {
        positions.add(Triple(false, thumbRow, 0))
    }

    var pass = 0
    while (true) {
        pass++
        var bestDelta = 0.0
        var bestSwap: Pair<Triple<Boolean, Int, Int>, Triple<Boolean, Int, Int>>? = null

        // Végigpróbáljuk az összes (i, j) párost
        for (i in 0 until positions.size) {
            val p1 = positions[i]
            val ch1 = if (p1.first) currentLayout.leftChars[p1.second][p1.third] else currentLayout.rightChars[p1.second][p1.third]

            for (j in i + 1 until positions.size) {
                val p2 = positions[j]
                val ch2 = if (p2.first) currentLayout.leftChars[p2.second][p2.third] else currentLayout.rightChars[p2.second][p2.third]

                if (ch1 == ch2) continue // Két '_' cseréjét átugorjuk

                // Csere kipróbálása helyben (deepCopy nélkül!)
                currentLayout.swapKeys(p1.first, p1.second, p1.third, p2.first, p2.second, p2.third)
                val newEnergy = completeLayoutAnalysis(currentLayout, corpusData, verbose = false).totalEffort
                val delta = newEnergy - currentEnergy

                if (delta < bestDelta - 1e-9) {
                    bestDelta = delta
                    bestSwap = Pair(p1, p2)
                }

                // Visszaállítjuk az eredeti állapotot
                currentLayout.swapKeys(p1.first, p1.second, p1.third, p2.first, p2.second, p2.third)
            }
        }

        // Ha találtunk javító cserét a teljes körben, véglegesítjük a legjobbat és új kört indítunk
        if (bestSwap != null) {
            val (p1, p2) = bestSwap
            currentLayout.swapKeys(p1.first, p1.second, p1.third, p2.first, p2.second, p2.third)
            currentEnergy += bestDelta
            println("[2-opt Polish | $pass. kör] Javulás: ${String.format("%.4f", bestDelta)} -> Új Effort: ${String.format("%.4f", currentEnergy)}")
        } else {
            break // Nincs több javító 2-es csere a teljes táblán
        }
    }

    return currentLayout
}