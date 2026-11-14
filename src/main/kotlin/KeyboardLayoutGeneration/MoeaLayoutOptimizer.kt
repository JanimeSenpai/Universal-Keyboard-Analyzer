package KeyboardLayoutGeneration

import keyboard_analysis.CombinedLayoutData
import keyboard_analysis.CorpusData
import keyboard_analysis.LayoutParser
import keyboard_analysis.completeLayoutAnalysis
import keyboard_analysis.printLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import layoutcharefforts
import org.moeaframework.Executor
import org.moeaframework.core.Solution
import org.moeaframework.core.variable.EncodingUtils
import org.moeaframework.problem.AbstractProblem
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Egy fizikai billentyűpozíció egyedi koordinátája a kétkezes mátrixban.
 */
data class KeyPos(val isLeft: Boolean, val row: Int, val col: Int)

/**
 * Egyedi ujjlenyomat (string) generálása a karaktertömbökből,
 * hogy ki tudjuk szűrni a pontosan megegyező generált billentyűzeteket.
 */
private fun moeaLayoutSignature(layout: CombinedLayoutData): String {
    val leftStr = layout.leftChars.joinToString("|") { it.concatToString() }
    val rightStr = layout.rightChars.joinToString("|") { it.concatToString() }
    return "$leftStr||$rightStr"
}

/**
 * Összegyűjti az optimalizálható pozíciókat.
 * - Alapértelmezetten (leftThumbEnabled = false, rightThumbEnabled = false) kizárólag
 *   a fő mátrix (0 until numRows - 1) koordinátáit tartalmazza, a thumb_left és thumb_right érintetlen marad.
 * - Ha valamelyik thumb engedélyezve van, a ReadLayout.kt-ban létrehozott 1 elemű
 *   leftChars[thumbRow][0] / rightChars[thumbRow][0] cellát is beveszi a permutációba.
 */
fun collectOptimizablePositions(
    layout: CombinedLayoutData,
    leftThumbEnabled: Boolean = false,
    rightThumbEnabled: Boolean = false
): List<KeyPos> {
    val mainRows = layout.numRows - 1
    val positions = mutableListOf<KeyPos>()

    // 1. Fő mátrix pozíciói (0 .. numRows - 2)
    for (r in 0 until mainRows) {
        for (c in layout.leftChars[r].indices) positions.add(KeyPos(true, r, c))
        for (c in layout.rightChars[r].indices) positions.add(KeyPos(false, r, c))
    }

    // 2. Opcionális hüvelykujj-pozíciók (a thumbRow sorban oldalanként 1 elemű CharArray van, col = 0)
    val thumbRow = layout.numRows - 1
    if (leftThumbEnabled && thumbRow in layout.leftChars.indices && layout.leftChars[thumbRow].isNotEmpty()) {
        positions.add(KeyPos(true, thumbRow, 0))
    }
    if (rightThumbEnabled && thumbRow in layout.rightChars.indices && layout.rightChars[thumbRow].isNotEmpty()) {
        positions.add(KeyPos(false, thumbRow, 0))
    }

    return positions
}

/**
 * MOEA Framework Problem definíció a billentyűzet-optimalizáláshoz.
 * 1 darab Permutation változót optimalizál a kijelölt pozíciókon, 1 célfüggvénnyel (Total Effort).
 * Magában foglalja a 2D-s billentyűzet-topológiát ismerő mutációs operátorokat:
 * - swapRandomKeys, swapAdjacentKeys, swapKeysCycle3
 * - swapColumns, swapRowsLocal, swapRowsGlobal, mirrorHand
 */
class KeyboardLayoutProblem(
    private val templateLayout: CombinedLayoutData,
    private val corpusData: CorpusData,
    val positions: List<KeyPos>,
    private val maxEvaluations: Int,
    private val populationSize: Int
) : AbstractProblem(1, 1) {

    private val totalPositions = positions.size
    private val mainRows = templateLayout.numRows - 1
    private val leftCols = templateLayout.leftChars[0].size
    private val rightCols = templateLayout.rightChars[0].size

    // Kiértékelés-számláló az adott szigeten a futási előrehaladás (progress) követéséhez
    private var evalCount = 0

    // 2D koordináta -> 1D permutációs index gyorsítótárak (hogy O(1) időben tudjunk oszlopot/sort cserélni)
    private val leftPosIndex = Array(templateLayout.numRows) { r ->
        IntArray(templateLayout.leftChars[r].size) { -1 }
    }
    private val rightPosIndex = Array(templateLayout.numRows) { r ->
        IntArray(templateLayout.rightChars[r].size) { -1 }
    }

    init {
        for ((idx, pos) in positions.withIndex()) {
            if (pos.isLeft) {
                leftPosIndex[pos.row][pos.col] = idx
            } else {
                rightPosIndex[pos.row][pos.col] = idx
            }
        }
    }

    // Kiolvassuk az eredeti karakterkészletet (az '_' üres helyekkel együtt) a kijelölt pozíciókból
    private val baseChars: CharArray = CharArray(totalPositions) { idx ->
        val pos = positions[idx]
        if (pos.isLeft) templateLayout.leftChars[pos.row][pos.col]
        else templateLayout.rightChars[pos.row][pos.col]
    }

    // Az '_' karakterekhez tartozó indexek a baseChars tömbben (a kanonizáláshoz)
    private val underscoreIds: IntArray = baseChars.indices.filter { baseChars[it] == '_' }.toIntArray()

    override fun newSolution(): Solution {
        val solution = Solution(1, 1)
        solution.setVariable(0, EncodingUtils.newPermutation(totalPositions))
        return solution
    }

    // --- 2D TOPOLÓGIA-TUDATOS MUTÁCIÓS PRIMITÍVEK A PERMUTÁCIÓS TÖMBÖN ---

    private fun swapPermKeys(perm: IntArray, left1: Boolean, r1: Int, c1: Int, left2: Boolean, r2: Int, c2: Int) {
        val idx1 = if (left1) leftPosIndex[r1][c1] else rightPosIndex[r1][c1]
        val idx2 = if (left2) leftPosIndex[r2][c2] else rightPosIndex[r2][c2]
        if (idx1 >= 0 && idx2 >= 0) {
            val tmp = perm[idx1]
            perm[idx1] = perm[idx2]
            perm[idx2] = tmp
        }
    }

    // 1. Globális véletlen 2-es csere (beleértve az esetleg engedélyezett thumb gombokat is)
    private fun mutateSwapRandomKeys(perm: IntArray) {
        val i = Random.nextInt(totalPositions)
        var j = Random.nextInt(totalPositions - 1)
        if (j >= i) j++
        val tmp = perm[i]
        perm[i] = perm[j]
        perm[j] = tmp
    }

    // 2. Szomszédos gombok cseréje (egy kézen belül vízszintesen, függőlegesen vagy átlósan)
    private fun mutateSwapAdjacentKeys(perm: IntArray) {
        val isLeft = Random.nextBoolean()
        val cols = if (isLeft) leftCols else rightCols
        val r1 = Random.nextInt(mainRows)
        val c1 = Random.nextInt(cols)

        val dr = Random.nextInt(-1, 2)
        val dc = Random.nextInt(-1, 2)
        val r2 = (r1 + dr).coerceIn(0, mainRows - 1)
        val c2 = (c1 + dc).coerceIn(0, cols - 1)

        if (r1 == r2 && c1 == c2) {
            val fallbackR2 = if (r1 + 1 < mainRows) r1 + 1 else (r1 - 1).coerceAtLeast(0)
            swapPermKeys(perm, isLeft, r1, c1, isLeft, fallbackR2, c1)
        } else {
            swapPermKeys(perm, isLeft, r1, c1, isLeft, r2, c2)
        }
    }

    // 3. Háromelemű cikluscsere (2-opt lokális minimumokból való kitöréshez)
    private fun mutateSwapCycle3(perm: IntArray) {
        val i1 = Random.nextInt(totalPositions)
        var i2 = Random.nextInt(totalPositions)
        while (i2 == i1) i2 = Random.nextInt(totalPositions)
        var i3 = Random.nextInt(totalPositions)
        while (i3 == i1 || i3 == i2) i3 = Random.nextInt(totalPositions)

        val tmp = perm[i1]
        perm[i1] = perm[i2]
        perm[i2] = perm[i3]
        perm[i3] = tmp
    }

    // 4. Teljes oszlopcsere (üres cellákkal együtt, Thumb sor kihagyásával)
    private fun mutateSwapColumns(perm: IntArray) {
        val left1 = Random.nextBoolean()
        val left2 = Random.nextBoolean()
        val cols1 = if (left1) leftCols else rightCols
        val cols2 = if (left2) leftCols else rightCols

        val c1 = Random.nextInt(cols1)
        var c2 = Random.nextInt(cols2)
        while (left1 == left2 && c1 == c2) {
            c2 = Random.nextInt(cols2)
        }

        for (r in 0 until mainRows) {
            swapPermKeys(perm, left1, r, c1, left2, r, c2)
        }
    }

    // 5. Helyi sorcsere (egyik kézen belül, Thumb sor kihagyásával)
    private fun mutateSwapRowsLocal(perm: IntArray, isLeft: Boolean = Random.nextBoolean()) {
        if (mainRows < 2) return
        val r1 = Random.nextInt(mainRows)
        var r2 = Random.nextInt(mainRows)
        while (r1 == r2) r2 = Random.nextInt(mainRows)

        val cols = if (isLeft) leftCols else rightCols
        for (c in 0 until cols) {
            swapPermKeys(perm, isLeft, r1, c, isLeft, r2, c)
        }
    }

    // 6. Globális sorcsere (mindkét térfélen egyszerre, Thumb sor kihagyásával)
    private fun mutateSwapRowsGlobal(perm: IntArray) {
        if (mainRows < 2) return
        val r1 = Random.nextInt(mainRows)
        var r2 = Random.nextInt(mainRows)
        while (r1 == r2) r2 = Random.nextInt(mainRows)

        for (c in 0 until leftCols) swapPermKeys(perm, true, r1, c, true, r2, c)
        for (c in 0 until rightCols) swapPermKeys(perm, false, r1, c, false, r2, c)
    }

    // 7. Kézen belüli tükrözés (egyik kéz oszlopainak megfordítása, Thumb sor kihagyásával)
    private fun mutateMirrorHand(perm: IntArray) {
        val isLeft = Random.nextBoolean()
        val cols = if (isLeft) leftCols else rightCols
        for (r in 0 until mainRows) {
            for (c in 0 until cols / 2) {
                swapPermKeys(perm, isLeft, r, c, isLeft, r, cols - 1 - c)
            }
        }
    }

    /**
     * Végrehajtja a mutációt az utódon a futás előrehaladásától (progress: 0.0 -> 1.0) függő súlyozással,
     * pontosan ugyanazokkal az operátorokkal, amelyeket a Simulated Annealing is használ.
     */
    private fun performTopologyAwareMutation(perm: IntArray, progress: Double) {
        val roll = Random.nextDouble()

        if (progress < 0.30) {
            // KORAI FÁZIS (0% - 30%): Erős strukturális keresés (oszlopok, sorok, tükrözés)
            when {
                roll < 0.35 -> mutateSwapRandomKeys(perm)
                roll < 0.50 -> { mutateSwapRandomKeys(perm); mutateSwapRandomKeys(perm) } // Dupla 2-es csere
                roll < 0.65 -> mutateSwapCycle3(perm)
                roll < 0.80 -> mutateSwapColumns(perm)
                roll < 0.88 -> mutateSwapRowsLocal(perm)
                roll < 0.94 -> mutateSwapRowsGlobal(perm)
                else -> mutateMirrorHand(perm)
            }
        } else if (progress < 0.75) {
            // KÖZÉPSŐ FÁZIS (30% - 75%): Kiegyensúlyozott makromutációk és lokális finomítás
            when {
                roll < 0.30 -> mutateSwapRandomKeys(perm)
                roll < 0.55 -> mutateSwapAdjacentKeys(perm)
                roll < 0.75 -> mutateSwapCycle3(perm)
                roll < 0.88 -> mutateSwapColumns(perm)
                roll < 0.94 -> mutateSwapRowsLocal(perm)
                roll < 0.97 -> mutateSwapRowsGlobal(perm)
                else -> mutateMirrorHand(perm)
            }
        } else {
            // VÉGSŐ FÁZIS (75% - 100%): Intenzív lokális finomhangolás és 3-as ciklusok
            when {
                roll < 0.35 -> mutateSwapRandomKeys(perm)
                roll < 0.70 -> mutateSwapAdjacentKeys(perm)
                roll < 0.90 -> mutateSwapCycle3(perm)
                roll < 0.96 -> mutateSwapColumns(perm)
                roll < 0.98 -> mutateSwapRowsLocal(perm)
                roll < 0.99 -> mutateSwapRowsGlobal(perm)
                else -> mutateMirrorHand(perm)
            }
        }
    }

    /**
     * Kanonizálja a permutációban az '_' (üres hely) azonosítókat,
     * hogy két azonos pozícióban lévő '_' mindig ugyanazt a permutációs ID-t kapja
     * a PMX keresztezés során.
     */
    private fun canonicalizeUnderscores(permutation: IntArray) {
        var uIdx = 0
        for (i in permutation.indices) {
            if (baseChars[permutation[i]] == '_') {
                permutation[i] = underscoreIds[uIdx++]
            }
        }
    }

    /**
     * Permutációs tömb dekódolása CombinedLayoutData példánnyá.
     * A nem optimalizált pozíciók (pl. a kikapcsolt thumb_left és thumb_right)
     * megtartják a templateLayout-ban lévő eredeti értéküket ('_').
     */
    fun decodeLayout(permutation: IntArray, nameSuffix: String = "moea"): CombinedLayoutData {
        val layout = templateLayout.deepCopy("${templateLayout.name}_$nameSuffix")
        for (idx in positions.indices) {
            val pos = positions[idx]
            val ch = baseChars[permutation[idx]]
            if (pos.isLeft) {
                layout.leftChars[pos.row][pos.col] = ch
            } else {
                layout.rightChars[pos.row][pos.col] = ch
            }
        }
        return layout
    }

    override fun evaluate(solution: Solution) {
        evalCount++
        val permutation = EncodingUtils.getPermutation(solution.getVariable(0))

        // A kezdeti populáció (első populationSize egyed) után minden új utódon
        // végrehajtjuk a 2D-topológia-tudatos mutációt (oszlopcsere, sorcsere, tükrözés, stb.)
        if (evalCount > populationSize) {
            val progress = (evalCount.toDouble() / maxEvaluations).coerceIn(0.0, 1.0)
            performTopologyAwareMutation(permutation, progress)
        }

        // Kanonizáljuk az '_' azonosítókat és visszaírjuk a módosított kromoszómát az egyedbe
        canonicalizeUnderscores(permutation)
        EncodingUtils.setPermutation(solution.getVariable(0), permutation)

        val candidateLayout = decodeLayout(permutation)
        val analysis = completeLayoutAnalysis(candidateLayout, corpusData, verbose = false)

        solution.setObjective(0, analysis.totalEffort)
    }
}

/**
 * Párhuzamos Memetikus Optimalizáló (MOEA Framework GA + Topológia-tudatos mutációk + 2-opt Polish).
 * Indítható több független szigeten (islands), majd a legjobbakat 2-opt-tal csiszolja.
 */
fun parallelMoeaImprovement(
    islands: Int = 32,
    evaluationsPerIsland: Int = 100000,
    populationSize: Int = 40,
    keyboardname: String = "ltsnergomini",
    leftThumbEnabled: Boolean = false,  // Jelenleg kikapcsolva: thumb_left nem kap betűt
    rightThumbEnabled: Boolean = false  // Jelenleg kikapcsolva: thumb_right nem kap betűt
) = runBlocking {
    val originalYamlFile = File("keyboards/${keyboardname}.yaml")
    val initialLayout = try {
        LayoutParser.parseLayout(originalYamlFile)
    } catch (e: Exception) {
        println("Hiba a beolvasáskor: ${e.message}")
        return@runBlocking
    }

    val corpusData = CorpusData.loadFromDataset("huszonkettoHuszonnegyeswikipediahuszonegy.txt")
    val positions = collectOptimizablePositions(initialLayout, leftThumbEnabled, rightThumbEnabled)

    println("=== MOEA FRAMEWORK (GA + PMX + TOPOLÓGIA-MUTÁCIÓK) INDÍTÁSA ===")
    println("-> Szigetek (párhuzamos GA futások): $islands | Kiértékelés/sziget: $evaluationsPerIsland | Populáció: $populationSize")
    println("-> Optimalizált pozíciók száma: ${positions.size} (Bal thumb: $leftThumbEnabled, Jobb thumb: $rightThumbEnabled)")
    val startTime = System.currentTimeMillis()
    val completedIslands = AtomicInteger(0)

    // 1. Párhuzamos GA szigetek futtatása a CPU szálain
    val deferredResults = (1..islands).map { i ->
        async(Dispatchers.Default) {
            val problem = KeyboardLayoutProblem(
                templateLayout = initialLayout,
                corpusData = corpusData,
                positions = positions,
                maxEvaluations = evaluationsPerIsland,
                populationSize = populationSize
            )

            val resultPopulation = Executor()
                .withProblem(problem)
                .withAlgorithm("GA")
                .withMaxEvaluations(evaluationsPerIsland)
                .withProperty("populationSize", populationSize)
                // A MOEA-ban csak a PMX keresztezést kérjük (ami mindig lemásolja a szülőket),
                // a 7-féle topológia-tudatos mutációt pedig a KeyboardLayoutProblem hajtja végre!
                .withProperty("operator", "pmx")
                .withProperty("pmx.rate", 0.25)
                .run()

            val bestSolution = resultPopulation[0]
            val bestPerm = EncodingUtils.getPermutation(bestSolution.getVariable(0))
            val bestLayoutInRun = problem.decodeLayout(bestPerm, "ga_island_$i")
            val bestEffort = bestSolution.getObjective(0)

            val done = completedIslands.incrementAndGet()
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            println("[$done / $islands kész | ${String.format("%.1f", elapsedSec)} mp] --- $i. GA sziget végzett. Effort: ${String.format("%.4f", bestEffort)} ---")

            Pair(bestEffort, bestLayoutInRun)
        }
    }

    val rawResults = deferredResults.awaitAll()
    val gaTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("\nMind a $islands GA sziget befejeződött (${String.format("%.1f", gaTimeSeconds)} mp).")

    // 2. Kiválasztjuk a legjobb 32 egyedi jelöltet a 2-opt végső csiszoláshoz
    val topCandidatesForPolish = rawResults
        .sortedBy { it.first }
        .distinctBy { moeaLayoutSignature(it.second) }
        .take(32)

    println("\n=== TOP ${topCandidatesForPolish.size} GA JELÖLT 2-OPT VÉGSŐ CSISZOLÁSA PÁRHUZAMOSAN ===")
    val polishStartTime = System.currentTimeMillis()

    val polishedResults = topCandidatesForPolish.mapIndexed { idx, (gaEffort, candidateLayout) ->
        async(Dispatchers.Default) {
            val polishedLayout = polishLayout2Opt(
                initialLayout = candidateLayout,
                corpusData = corpusData,
                leftThumbEnabled = leftThumbEnabled,
                rightThumbEnabled = rightThumbEnabled
            )
            val polishedAnalysis = completeLayoutAnalysis(polishedLayout, corpusData, verbose = false)
            val diff = polishedAnalysis.totalEffort - gaEffort

            println("--- Jelölt #${idx + 1} csiszolva: ${String.format("%.4f", gaEffort)} -> ${String.format("%.4f", polishedAnalysis.totalEffort)} (Delta: ${String.format("%.4f", diff)}) ---")
            Pair(polishedAnalysis.totalEffort, polishedLayout)
        }
    }.awaitAll()

    val polishTimeSeconds = (System.currentTimeMillis() - polishStartTime) / 1000.0
    val totalTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("2-opt csiszolás kész (${String.format("%.1f", polishTimeSeconds)} mp). Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")

    // 3. Top 3 kiválasztása
    val top3 = polishedResults
        .sortedBy { it.first }
        .distinctBy { moeaLayoutSignature(it.second) }
        .take(3)

    // 4. Mentés a generatedkeyboards és ImprovementLogs mappákba
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    val timestamp = LocalDateTime.now().format(formatter)
    val safeLayoutName = initialLayout.name.replace(" ", "_")
    val runDirName = "${safeLayoutName}_MOEA-$timestamp"

    val outputDir = File("generatedkeyboards/$runDirName")
    outputDir.mkdirs()

    val logDir = File("ImprovementLogs")
    logDir.mkdirs()
    val logFile = File(logDir, "$runDirName.txt")

    logFile.bufferedWriter().use { writer ->
        writer.write("=== MOEA FRAMEWORK (GA + PMX + TOPOLÓGIA-MUTÁCIÓK + 2-OPT) LOG ===")
        writer.newLine()
        writer.write("Billentyűzet: ${initialLayout.name}")
        writer.newLine()
        writer.write("Futtatás időpontja: $timestamp")
        writer.newLine()
        writer.write("Szigetek száma: $islands | Kiértékelés/sziget: $evaluationsPerIsland | Populáció: $populationSize")
        writer.newLine()
        writer.write("Hüvelykujj engedélyezve: Bal=$leftThumbEnabled, Jobb=$rightThumbEnabled")
        writer.newLine()
        writer.write("Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")
        writer.newLine()
        writer.newLine()
        writer.write("--- TOP 3 EREDMÉNY ---")
        writer.newLine()

        for ((index, pair) in top3.withIndex()) {
            val rank = index + 1
            writer.write("$rank. Helyezett:")
            writer.newLine()
            writer.write(" - Effort pontszám: ${String.format("%.4f", pair.first)}")
            writer.newLine()
            writer.write(" - Származás: MOEA Framework GA (PMX + Topológia-mutációk + 2-opt)")
            writer.newLine()
            writer.newLine()
        }
    }
    println("Log fájl elmentve: ${logFile.absolutePath}")

    for ((index, pair) in top3.withIndex()) {
        val rank = index + 1
        val (effort, layout) = pair

        layout.name = "${safeLayoutName}_moea_optimized_$rank"
        println("\n>>> $rank. HELYEZETT (${layout.name}) - Total Effort: ${String.format("%.4f", effort)} <<<")

        printLayout(layout)
        completeLayoutAnalysis(layout, corpusData, verbose = true)
        layoutcharefforts(layout)

        val imageOutputPath = File(outputDir, "${layout.name}_heatmap.png").absolutePath
        renderCharEffortlayout(layout, imageOutputPath)

        val yamlOutputPath = File(outputDir, "${layout.name}.yaml")
        saveLayoutToYaml(layout, originalYamlFile, yamlOutputPath)
    }
}