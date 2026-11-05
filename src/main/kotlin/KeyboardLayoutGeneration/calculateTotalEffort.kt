package KeyboardLayoutGeneration

import keyboard_analysis.CombinedLayoutData
import keyboard_analysis.CorpusData
import keyboard_analysis.LayoutAnalysisResult
import keyboard_analysis.completeLayoutAnalysis
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Kiszámítja a billentyűzetkiosztás összesített jósági/terhelési mutatóját (Célfüggvény).
 * Minél KISEBB a visszaadott érték, annál jobb a kiosztás.
 */
fun calculateTotalEffort(result: LayoutAnalysisResult): Double {

    // --- 1. BÜNTETŐ SÚLYOK (Minimalizálandó értékek -> növelik a Total Effortot) ---
    val weightCharReachEffort = 40.0
    val weightHandImbalance = 4.0

    // Cél: < 4-6%
    val weightPinkyOff = 5.0

    // Cél: < 1-1.5%
    val weightSfb = 15.0  //original 10

    // Cél: < 0.5% (ÚJ)
    val weightFullScissor = 15.0  //original 15
    val weightHalfScissor = weightFullScissor / 2 //original :2

    // Cél: < 1.5-2% (ÚJ)
    val weightLateralStretch = 8.0
    val weightPinkyStretch = 8.0

    // Cél: < 2-3%
    val weightRedirect = 4.0

    val weightSpaceSfs1 = 2.0

    val weightGravity = 0.5

    // Soronkénti terhelés súlyai (0: legfelső sor ... 5: thumb sor)
    //TODO megvalósítani, hogy magától tudja a program, hogy hány soros ez a billentyűzet és annak függvényében kiosztani ezeket a büntetőpontokat
    //az ujjak hossza bekerült a fizikai layout effort modellbe, így egyelőre ezek az értékek 0 súlyt kapnak
    val weightRow0 = 0.0
    val weightRow1 = 0.0
    val weightRow2 = 0.0
    val weightRow3 = 0.0 // Home row (ezt külön jutalmazzuk lent)
    val weightRow4 = 0.0
    val weightRow5 = 0.0 // Thumb row

    // --- 2. JUTALMAZÓ SÚLYOK (Maximalizálandó értékek -> csökkentik a Total Effortot) ---
    val weightHomeRow = 0.86

    // Cél: minél magasabb, együttesen > 40-50%
    val weightAlternation = 1.0
    val weightRoll = 1.3
    val weightThreeRoll = 1.6

    // Kéz-aszimmetria kiszámítása: |bal% - jobb%|
    //val handImbalance = kotlin.math.abs(result.handBalanceLeft - result.handBalanceRight)

    val lessUsedHandPercentage = minOf(result.handBalanceLeft, result.handBalanceRight)
    val k = 50.0 - lessUsedHandPercentage

    val handImbalance = when {
        k <= 5.0 -> {
            0.04 * (5.0 - k) * (5.0 - k)
        }
        k <= 7.0 -> {
            0.5 * (k - 5.0) * (k - 5.0)
        }
        else -> {
            val diff = k - 7.0
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

    // Jutalmak összege
    val rewards =
        (result.homeRowPercentage * weightHomeRow) + //ezt mindig jól adjuk hozzá
                (result.alternationPercentage * weightAlternation) +
                (result.rollPercentage * weightRoll) +
                (result.threerollPercentage * weightThreeRoll)

    return penalties - rewards
}

/**
 * Simulated Annealing alapú optimalizáló függvény.
 *
 * Dinamikusan skálázza a hűtést az iterációszámhoz (ha a coolingRate nincs fixen megadva),
 * és a hőmérséklet függvényében súlyozza a mutációs műveleteket.
 * Bemenetként kap egy kezdeti layoutot, és visszaadja a megtalált legjobb (legkisebb effortú) változatot.
 */
fun improveLayout(
    initialLayout: CombinedLayoutData,
    corpusData: CorpusData,
    iterations: Int = 10000,
    initialTemp: Double = 10.0,
    coolingRate: Double? = null, // Ha null, automatikusan kiszámolja az iterations alapján
    finalTemp: Double = 0.005
): CombinedLayoutData {
    // Dolgozzunk egy másolaton, hogy az eredeti objektum érintetlen maradjon, ha kell
    var currentLayout = initialLayout.deepCopy("${initialLayout.name}_current")
    var currentAnalysis = completeLayoutAnalysis(currentLayout, corpusData, verbose = false)
    var currentEnergy = currentAnalysis.totalEffort

    var bestLayout = currentLayout.deepCopy("${initialLayout.name}_optimized")
    var bestEnergy = currentEnergy

    var temp = initialTemp
    val effectiveCoolingRate = coolingRate ?: (finalTemp / initialTemp).pow(1.0 / iterations)
    val mainRows = currentLayout.numRows - 1 // A fő mátrix sorai (a thumb sort figyelmen kívül hagyva)

    println("=== Optimalizálás indítása: ${initialLayout.name} ===")
    println("Kezdeti Total Effort: ${String.format("%.4f", currentEnergy)} | CoolingRate: ${String.format("%.6f", effectiveCoolingRate)}")

    for (step in 1..iterations) {
        // 1. Készítünk egy munkamásolatot és végrehajtunk rajta egy hőmérséklet-függő mutációt
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

            // Ha ez az eddigi globálisan legjobb állapot, elmentjük
            if (currentEnergy < bestEnergy) {
                bestLayout = currentLayout.deepCopy("${initialLayout.name}_optimized")
                bestEnergy = currentEnergy
                println("[Lépés: $step | T: ${String.format("%.4f", temp)}] Új legjobb Effort: ${String.format("%.4f", bestEnergy)}")
            }
        }

        // 5. Hűtés
        temp *= effectiveCoolingRate
    }

    println("=== Optimalizálás befejezve ===")
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