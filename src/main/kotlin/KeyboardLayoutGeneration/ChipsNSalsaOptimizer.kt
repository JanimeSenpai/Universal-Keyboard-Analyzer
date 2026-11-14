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
import org.cicirello.search.operators.Initializer
import org.cicirello.search.operators.UndoableMutationOperator
import org.cicirello.search.problems.OptimizationProblem
import org.cicirello.search.sa.ModifiedLam
import org.cicirello.search.sa.SelfTuningLam
import org.cicirello.search.sa.SimulatedAnnealing
import org.cicirello.util.Copyable
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Egyedi ujjlenyomat (string) generálása a karaktertömbökből,
 * hogy ki tudjuk szűrni a pontosan megegyező generált billentyűzeteket.
 */
private fun cnsLayoutSignature(layout: CombinedLayoutData): String {
    val leftStr = layout.leftChars.joinToString("|") { it.concatToString() }
    val rightStr = layout.rightChars.joinToString("|") { it.concatToString() }
    return "$leftStr||$rightStr"
}

/**
 * A Chips-n-Salsa által megkövetelt Copyable burkolóosztály (wrapper) a CombinedLayoutData köré.
 * Mivel UndoableMutationOperator-t használunk, a copy() csak akkor hívódik meg,
 * amikor az algoritmus új globális rekordot (best-so-far) ment el!
 */
class LayoutCandidate(val layout: CombinedLayoutData) : Copyable<LayoutCandidate> {
    override fun copy(): LayoutCandidate {
        return LayoutCandidate(layout.deepCopy(layout.name))
    }
}

/**
 * A Chips-n-Salsa OptimizationProblem implementációja.
 * Minimalizálja a completeLayoutAnalysis által számított totalEffort értéket.
 */
class KeyboardOptimizationProblem(
    private val corpusData: CorpusData
) : OptimizationProblem<LayoutCandidate> {

    override fun cost(candidate: LayoutCandidate): Double {
        return completeLayoutAnalysis(candidate.layout, corpusData, verbose = false).totalEffort
    }

    override fun value(candidate: LayoutCandidate): Double {
        return cost(candidate)
    }
}

/**
 * Kezdőállapot-generátor (Initializer) a Chips-n-Salsa számára.
 * - Ha hotShuffle = false: az eredeti YAML kiosztásból indul.
 * - Ha hotShuffle = true: teljesen összekeveri az optimalizálható pozíciókat (Fisher-Yates shuffle),
 *   tiszteletben tartva a leftThumbEnabled és rightThumbEnabled kapcsolókat.
 */
class KeyboardLayoutInitializer(
    private val templateLayout: CombinedLayoutData,
    private val hotShuffle: Boolean = true,
    private val leftThumbEnabled: Boolean = false,
    private val rightThumbEnabled: Boolean = false
) : Initializer<LayoutCandidate> {

    override fun createCandidateSolution(): LayoutCandidate {
        val copy = templateLayout.deepCopy(
            if (hotShuffle) "${templateLayout.name}_cns_shuffled" else "${templateLayout.name}_cns_orig"
        )

        if (hotShuffle) {
            val positions = collectOptimizablePositions(copy, leftThumbEnabled, rightThumbEnabled)
            // Fisher-Yates keverés közvetlenül a kijelölt pozíciókon
            for (i in positions.size - 1 downTo 1) {
                val j = Random.nextInt(i + 1)
                val p1 = positions[i]
                val p2 = positions[j]
                copy.swapKeys(p1.isLeft, p1.row, p1.col, p2.isLeft, p2.row, p2.col)
            }
        }

        return LayoutCandidate(copy)
    }

    override fun split(): KeyboardLayoutInitializer {
        return KeyboardLayoutInitializer(templateLayout, hotShuffle, leftThumbEnabled, rightThumbEnabled)
    }
}

/**
 * 2D-topológia-tudatos, helyben visszavonható (Undoable) mutációs operátor a Chips-n-Salsa SA-hoz.
 * Nulla objektum-allokációval (deepCopy nélkül) működik: minden mutate() hívás elmenti a lépés
 * paramétereit primitív mezőkbe, az undo() pedig pontosan az inverz műveletet hajtja végre.
 */
class TopologyUndoableMutation(
    private val maxEvaluations: Int,
    private val leftThumbEnabled: Boolean = false,
    private val rightThumbEnabled: Boolean = false
) : UndoableMutationOperator<LayoutCandidate> {

    private var stepCount = 0

    // Az utolsó végrehajtott mutáció típusa és paraméterei a gyors undo() művelethez:
    // 1 = swapKeys (2-es csere / szomszédos csere)
    // 2 = swapKeysCycle3 (3-as ciklus)
    // 3 = swapColumns (oszlopcsere)
    // 4 = swapRowsLocal (helyi sorcsere)
    // 5 = swapRowsGlobal (globális sorcsere)
    // 6 = mirrorHand (kézen belüli tükrözés)
    private var lastMutationType = 0
    private var b1 = false
    private var r1 = 0
    private var c1 = 0
    private var b2 = false
    private var r2 = 0
    private var c2 = 0
    private var b3 = false
    private var r3 = 0
    private var c3 = 0

    private fun pickRandomCoord(layout: CombinedLayoutData): Triple<Boolean, Int, Int> {
        val isLeft = Random.nextBoolean()
        val thumbRow = layout.numRows - 1
        val thumbAllowed = if (isLeft) leftThumbEnabled else rightThumbEnabled

        // Ha a hüvelykujj engedélyezve van, kis eséllyel (1 / összes gomb) kiválaszthatjuk a thumb [thumbRow][0] cellát
        val cols = if (isLeft) layout.leftChars[0].size else layout.rightChars[0].size
        val mainKeyCount = thumbRow * cols
        if (thumbAllowed && Random.nextInt(mainKeyCount + 1) == mainKeyCount) {
            return Triple(isLeft, thumbRow, 0)
        }

        val r = Random.nextInt(thumbRow)
        val c = Random.nextInt(cols)
        return Triple(isLeft, r, c)
    }

    override fun mutate(candidate: LayoutCandidate) {
        val layout = candidate.layout
        val mainRows = layout.numRows - 1
        stepCount++

        val progress = (stepCount.toDouble() / maxEvaluations).coerceIn(0.0, 1.0)
        val roll = Random.nextDouble()

        // Folytonos, sima átmenet (lépcsős ugrások nélkül), hogy a SelfTuningLam
        // hőmérséklet-szabályozója ne kapjon sokkot a mutációs eloszlás váltásakor.
        // Az első 75%-ban a makromutációk aránya 35%-ról másodfokú görbével 0%-ra cseng le.
        val macroProb = if (progress < 0.75) {
            val t = 1.0 - (progress / 0.75)
            0.35 * t * t
        } else {
            0.0 // Az utolsó 25%-ban (befagyasztás) 0% makromutáció, csak finomhangolás!
        }

        val action = if (roll < macroProb) {
            // Makromutációk (oszlopcsere, helyi/globális sorcsere, kéztükrözés)
            val macroRoll = roll / macroProb
            when {
                macroRoll < 0.50 -> 3 // Oszlopcsere (50% a makromutációkon belül)
                macroRoll < 0.75 -> 4 // Helyi sorcsere (25%)
                macroRoll < 0.90 -> 5 // Globális sorcsere (15%)
                else -> 6             // Kéztükrözés (10%)
            }
        } else {
            // Mikromutációk (globális 2-es csere, szomszédos 2-es csere, 3-as ciklus)
            val microRoll = (roll - macroProb) / (1.0 - macroProb)
            // A futás előrehaladtával nő a szomszédos cserék aránya (25% -> 55%)
            val adjacentShare = 0.25 + 0.30 * progress
            val cycle3Share = 0.20 * (1.0 - 0.5 * progress) // 20% -> 10%
            when {
                microRoll < adjacentShare -> 7                   // Szomszédos 2-es csere
                microRoll < adjacentShare + cycle3Share -> 2     // 3-as cikluscsere
                else -> 1                                        // Globális véletlen 2-es csere
            }
        }

        when (action) {
            1 -> { // Globális véletlen 2-es csere
                val p1 = pickRandomCoord(layout)
                var p2 = pickRandomCoord(layout)
                while (p1 == p2) p2 = pickRandomCoord(layout)

                b1 = p1.first; r1 = p1.second; c1 = p1.third
                b2 = p2.first; r2 = p2.second; c2 = p2.third
                lastMutationType = 1
                layout.swapKeys(b1, r1, c1, b2, r2, c2)
            }
            7 -> { // Szomszédos 2-es csere (ugyanúgy swapKeys-zel vonjuk vissza -> lastMutationType = 1)
                b1 = Random.nextBoolean()
                b2 = b1
                val cols = if (b1) layout.leftChars[0].size else layout.rightChars[0].size
                r1 = Random.nextInt(mainRows)
                c1 = Random.nextInt(cols)

                val dr = Random.nextInt(-1, 2)
                val dc = Random.nextInt(-1, 2)
                r2 = (r1 + dr).coerceIn(0, mainRows - 1)
                c2 = (c1 + dc).coerceIn(0, cols - 1)
                if (r1 == r2 && c1 == c2) {
                    r2 = if (r1 + 1 < mainRows) r1 + 1 else (r1 - 1).coerceAtLeast(0)
                }

                lastMutationType = 1
                layout.swapKeys(b1, r1, c1, b2, r2, c2)
            }
            2 -> { // 3-as cikluscsere
                val p1 = pickRandomCoord(layout)
                var p2 = pickRandomCoord(layout)
                while (p2 == p1) p2 = pickRandomCoord(layout)
                var p3 = pickRandomCoord(layout)
                while (p3 == p1 || p3 == p2) p3 = pickRandomCoord(layout)

                b1 = p1.first; r1 = p1.second; c1 = p1.third
                b2 = p2.first; r2 = p2.second; c2 = p2.third
                b3 = p3.first; r3 = p3.second; c3 = p3.third
                lastMutationType = 2
                layout.swapKeysCycle3(b1, r1, c1, b2, r2, c2, b3, r3, c3)
            }
            3 -> { // Oszlopcsere
                b1 = Random.nextBoolean()
                b2 = Random.nextBoolean()
                val cols1 = if (b1) layout.leftChars[0].size else layout.rightChars[0].size
                val cols2 = if (b2) layout.leftChars[0].size else layout.rightChars[0].size
                c1 = Random.nextInt(cols1)
                c2 = Random.nextInt(cols2)
                while (b1 == b2 && c1 == c2) {
                    c2 = Random.nextInt(cols2)
                }
                lastMutationType = 3
                layout.swapColumns(b1, c1, b2, c2)
            }
            4 -> { // Helyi sorcsere
                b1 = Random.nextBoolean()
                r1 = Random.nextInt(mainRows)
                r2 = Random.nextInt(mainRows)
                while (r1 == r2) r2 = Random.nextInt(mainRows)
                lastMutationType = 4
                layout.swapRowsLocal(b1, r1, r2)
            }
            5 -> { // Globális sorcsere
                r1 = Random.nextInt(mainRows)
                r2 = Random.nextInt(mainRows)
                while (r1 == r2) r2 = Random.nextInt(mainRows)
                lastMutationType = 5
                layout.swapRowsGlobal(r1, r2)
            }
            6 -> { // Kéztükrözés
                b1 = Random.nextBoolean()
                lastMutationType = 6
                layout.mirrorHand(b1)
            }
        }
    }

    override fun undo(candidate: LayoutCandidate) {
        val layout = candidate.layout
        when (lastMutationType) {
            1 -> layout.swapKeys(b1, r1, c1, b2, r2, c2) // Ön-inverz
            2 -> layout.swapKeysCycle3(b1, r1, c1, b3, r3, c3, b2, r2, c2) // Fordított irányú 3-as ciklus!
            3 -> layout.swapColumns(b1, c1, b2, c2) // Ön-inverz
            4 -> layout.swapRowsLocal(b1, r1, r2) // Ön-inverz
            5 -> layout.swapRowsGlobal(r1, r2) // Ön-inverz
            6 -> layout.mirrorHand(b1) // Ön-inverz
        }
    }

    override fun split(): TopologyUndoableMutation {
        return TopologyUndoableMutation(maxEvaluations, leftThumbEnabled, rightThumbEnabled)
    }
}

/**
 * Párhuzamos optimalizáló a Chips-n-Salsa könyvtár adaptív Simulated Annealing motorjával
 * (Self-Tuning Lam / Modified Lam hűtési ütemezés) + 2-opt végső csiszolással.
 */
fun parallelChipsNSalsaImprovement(
    runs: Int = 64,
    evaluationsPerRun: Int = 50000,
    useSelfTuningLam: Boolean = true, // true = SelfTuningLam (Cicirello 2021), false = ModifiedLam (Lam & Delosme 1988)
    keyboardname: String = "ltsnergomini",
    leftThumbEnabled: Boolean = false,
    rightThumbEnabled: Boolean = false
) = runBlocking {
    val originalYamlFile = File("keyboards/${keyboardname}.yaml")
    val initialLayout = try {
        LayoutParser.parseLayout(originalYamlFile)
    } catch (e: Exception) {
        println("Hiba a beolvasáskor: ${e.message}")
        return@runBlocking
    }

    val corpusData = CorpusData.loadFromDataset("huszonkettoHuszonnegyeswikipediahuszonegy.txt")
    val scheduleName = if (useSelfTuningLam) "SelfTuningLam" else "ModifiedLam"

    // A futások ~10%-a az eredeti kiosztást finomhangolja, ~90%-a Hot Shuffle-ből indul
    val originalRunsCount = minOf(32, maxOf(1, runs / 10))
    val shuffledRunsCount = runs - originalRunsCount

    println("=== CHIPS-N-SALSA ($scheduleName SA + UNDOABLE 2D MUTÁCIÓK) INDÍTÁSA ===")
    println("-> Párhuzamos futások: $runs (Eredeti: $originalRunsCount, Shuffled: $shuffledRunsCount) | Lépés/futás: $evaluationsPerRun")
    println("-> Hüvelykujj engedélyezve: Bal=$leftThumbEnabled, Jobb=$rightThumbEnabled")
    val startTime = System.currentTimeMillis()
    val completedRuns = AtomicInteger(0)

    // 1. Párhuzamos Chips-n-Salsa SA szálak futtatása
    val deferredResults = (1..runs).map { i ->
        val isShuffled = i > originalRunsCount
        async(Dispatchers.Default) {
            val problem = KeyboardOptimizationProblem(corpusData)
            val initializer = KeyboardLayoutInitializer(
                templateLayout = initialLayout,
                hotShuffle = isShuffled,
                leftThumbEnabled = leftThumbEnabled,
                rightThumbEnabled = rightThumbEnabled
            )
            val mutation = TopologyUndoableMutation(
                maxEvaluations = evaluationsPerRun,
                leftThumbEnabled = leftThumbEnabled,
                rightThumbEnabled = rightThumbEnabled
            )
            val schedule = if (useSelfTuningLam) SelfTuningLam() else ModifiedLam()

            val sa = SimulatedAnnealing(problem, mutation, initializer, schedule)
            val solutionPair = sa.optimize(evaluationsPerRun)

            val bestLayoutInRun = solutionPair.solution.layout
            val bestEffort = solutionPair.costDouble

            val done = completedRuns.incrementAndGet()
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            val modeTag = if (isShuffled) "Shuffled" else "Original"
            println("[$done / $runs kész | ${String.format("%.1f", elapsedSec)} mp] --- $i. CNS ($modeTag) végzett. Effort: ${String.format("%.4f", bestEffort)} ---")

            Triple(bestEffort, bestLayoutInRun, isShuffled)
        }
    }

    val rawResults = deferredResults.awaitAll()
    val saTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("\nMind a $runs Chips-n-Salsa futás befejeződött (${String.format("%.1f", saTimeSeconds)} mp).")

    // 2. Kiválasztjuk a legjobb 32 egyedi jelöltet a 2-opt végső csiszoláshoz
    val topCandidatesForPolish = rawResults
        .sortedBy { it.first }
        .distinctBy { cnsLayoutSignature(it.second) }
        .take(32)

    println("\n=== TOP ${topCandidatesForPolish.size} CHIPS-N-SALSA JELÖLT 2-OPT VÉGSŐ CSISZOLÁSA ===")
    val polishStartTime = System.currentTimeMillis()

    val polishedResults = topCandidatesForPolish.mapIndexed { idx, (saEffort, candidateLayout, isShuffled) ->
        async(Dispatchers.Default) {
            val polishedLayout = polishLayout2Opt(
                initialLayout = candidateLayout,
                corpusData = corpusData,
                leftThumbEnabled = leftThumbEnabled,
                rightThumbEnabled = rightThumbEnabled
            )
            val polishedAnalysis = completeLayoutAnalysis(polishedLayout, corpusData, verbose = false)
            val diff = polishedAnalysis.totalEffort - saEffort

            println("--- Jelölt #${idx + 1} csiszolva: ${String.format("%.4f", saEffort)} -> ${String.format("%.4f", polishedAnalysis.totalEffort)} (Delta: ${String.format("%.4f", diff)}) ---")
            Triple(polishedAnalysis.totalEffort, polishedLayout, isShuffled)
        }
    }.awaitAll()

    val polishTimeSeconds = (System.currentTimeMillis() - polishStartTime) / 1000.0
    val totalTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("2-opt csiszolás kész (${String.format("%.1f", polishTimeSeconds)} mp). Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")

    // 3. Top 3 kiválasztása
    val top3 = polishedResults
        .sortedBy { it.first }
        .distinctBy { cnsLayoutSignature(it.second) }
        .take(3)

    // 4. Mentés a generatedkeyboards és ImprovementLogs mappákba
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    val timestamp = LocalDateTime.now().format(formatter)
    val safeLayoutName = initialLayout.name.replace(" ", "_")
    val runDirName = "${safeLayoutName}_CNS-$timestamp"

    val outputDir = File("generatedkeyboards/$runDirName")
    outputDir.mkdirs()

    val logDir = File("ImprovementLogs")
    logDir.mkdirs()
    val logFile = File(logDir, "$runDirName.txt")

    logFile.bufferedWriter().use { writer ->
        writer.write("=== CHIPS-N-SALSA ($scheduleName SA + 2-OPT) OPTIMALIZÁLÁSI LOG ===")
        writer.newLine()
        writer.write("Billentyűzet: ${initialLayout.name}")
        writer.newLine()
        writer.write("Futtatás időpontja: $timestamp")
        writer.newLine()
        writer.write("Összes szál: $runs (Eredeti: $originalRunsCount, Shuffled: $shuffledRunsCount) | Lépés/szál: $evaluationsPerRun")
        writer.newLine()
        writer.write("Hűtési ütemezés: $scheduleName | Hüvelykujj: Bal=$leftThumbEnabled, Jobb=$rightThumbEnabled")
        writer.newLine()
        writer.write("Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")
        writer.newLine()
        writer.write("Korpusz neve: ${corpusData.datasetName}")
        writer.newLine()
        writer.newLine()
        writer.write("--- TOP 3 EREDMÉNY ---")
        writer.newLine()

        for ((index, triple) in top3.withIndex()) {
            val rank = index + 1
            val originStr = if (triple.third) "Chips-n-Salsa $scheduleName (Shuffled)" else "Chips-n-Salsa $scheduleName (Eredeti)"
            writer.write("$rank. Helyezett:")
            writer.newLine()
            writer.write(" - Effort pontszám: ${String.format("%.4f", triple.first)}")
            writer.newLine()
            writer.write(" - Származás: $originStr")
            writer.newLine()
            writer.newLine()
        }
    }
    println("Log fájl elmentve: ${logFile.absolutePath}")

    for ((index, triple) in top3.withIndex()) {
        val rank = index + 1
        val (effort, layout, _) = triple

        layout.name = "${safeLayoutName}_cns_optimized_$rank"
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