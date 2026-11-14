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
 */
class KeyboardLayoutProblem(
    private val templateLayout: CombinedLayoutData,
    private val corpusData: CorpusData,
    val positions: List<KeyPos>
) : AbstractProblem(1, 1) {

    private val totalPositions = positions.size

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
        val permutation = EncodingUtils.getPermutation(solution.getVariable(0))

        // Kanonizáljuk az '_' azonosítókat és visszaírjuk az egyedbe,
        // hogy a PMX keresztezés ne cserélgessen feleslegesen két üres helyet
        canonicalizeUnderscores(permutation)
        EncodingUtils.setPermutation(solution.getVariable(0), permutation)

        val candidateLayout = decodeLayout(permutation)
        val analysis = completeLayoutAnalysis(candidateLayout, corpusData, verbose = false)

        solution.setObjective(0, analysis.totalEffort)
    }
}

/**
 * Párhuzamos Memetikus Optimalizáló (MOEA Framework GA + 2-opt Polish).
 * Indítható több független szigeten (islands), majd a legjobbakat 2-opt-tal csiszolja.
 */
fun parallelMoeaImprovement(
    islands: Int = 32,
    evaluationsPerIsland: Int = 50000,
    populationSize: Int = 200,
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

    println("=== MOEA FRAMEWORK (GA + PMX) OPTIMALIZÁLÁS INDÍTÁSA ===")
    println("-> Szigetek (párhuzamos GA futások): $islands | Kiértékelés/sziget: $evaluationsPerIsland | Populáció: $populationSize")
    println("-> Optimalizált pozíciók száma: ${positions.size} (Bal thumb: $leftThumbEnabled, Jobb thumb: $rightThumbEnabled)")
    val startTime = System.currentTimeMillis()
    val completedIslands = AtomicInteger(0)

    // 1. Párhuzamos GA szigetek futtatása a CPU szálain
    val deferredResults = (1..islands).map { i ->
        async(Dispatchers.Default) {
            val problem = KeyboardLayoutProblem(initialLayout, corpusData, positions)

            val resultPopulation = Executor()
                .withProblem(problem)
                .withAlgorithm("GA") // Genetikus Algoritmus (Permutációra automatikusan PMX + Swap/Insertion)
                .withMaxEvaluations(evaluationsPerIsland)
                .withProperty("populationSize", populationSize)
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
        writer.write("=== MOEA FRAMEWORK (GA + PMX + 2-OPT) OPTIMALIZÁLÁSI LOG ===")
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
            writer.write(" - Származás: MOEA Framework GA (PMX + 2-opt)")
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