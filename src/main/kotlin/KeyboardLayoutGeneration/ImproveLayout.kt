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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger


var ltsn = Pair("ltsnergomini","huszonkettoHuszonnegyeswikipediahuszonegy.txt")
var qwertyenglish= Pair("qwertycolstag_en","monke.txt")

fun main(){
    val benchmarkRuns = 512
    val polishTopN = 128 // Futtatás előtt átírható pl. 128-ra

    parallelImprovement(
        runs = benchmarkRuns,
        keyboardname = ltsn.first,
        corpusFileName = ltsn.second,
        iterations = 50000,
        polishTopN = polishTopN
    )

    parallelChipsNSalsaImprovement(
        runs = benchmarkRuns,
        evaluationsPerRun = 50000,
        useSelfTuningLam = true,
        keyboardname = ltsn.first,
        corpusFileName = ltsn.second,
        leftThumbEnabled = false,
        rightThumbEnabled = false,
        polishTopN = polishTopN
    )

    parallelMoeaImprovement(
        islands = benchmarkRuns,
        evaluationsPerIsland = 100000,
        keyboardname = ltsn.first,
        corpusFileName = ltsn.second,
        leftThumbEnabled = false,
        rightThumbEnabled = false,
        polishTopN = polishTopN
    )
}


/**
 * Egyedi ujjlenyomat (string) generálása a karaktertömbökből,
 * hogy ki tudjuk szűrni a pontosan megegyező generált billentyűzeteket.
 */
private fun layoutSignature(layout: CombinedLayoutData): String {
    val leftStr = layout.leftChars.joinToString("|") { it.concatToString() }
    val rightStr = layout.rightChars.joinToString("|") { it.concatToString() }
    return "$leftStr||$rightStr"
}

// A runBlocking összeköti a hagyományos szinkron kódot a korutinok világával
fun parallelImprovement(
    runs: Int = 25,
    keyboardname: String = "ltsnergomini",
    corpusFileName: String = "huszonkettoHuszonnegyeswikipediahuszonegy.txt",
    iterations: Int = 50000,
    folder: String = "keyboards",
    polishTopN: Int = 32
) = runBlocking {
    val cleanKeyboardName = keyboardname.removeSuffix(".yaml")
    val originalYamlFile = LayoutParser.resolveLayoutFile(cleanKeyboardName, folder) ?: File("$folder/${cleanKeyboardName}.yaml")
    val initialLayout = try {
        LayoutParser.parseLayout(originalYamlFile)
    } catch (e: Exception) {
        println("Hiba a beolvasáskor: ${e.message}")
        return@runBlocking
    }

    val corpusData = CorpusData.loadFromDataset(corpusFileName)

    // Kiszámoljuk, hány szál induljon az eredeti layoutból (N/10, minimum 1, maximum 32 — a CNS-sel azonos arány)
    val numOriginalRuns = minOf(32, maxOf(1, runs / 10))
    val shuffledRunsCount = runs - numOriginalRuns

    println("=== SA OPTIMALIZÁLÁS INDÍTÁSA ($runs FUTÁS PÁRHUZAMOSAN, $iterations ITERÁCIÓ/SZÁL) ===")
    println("-> $numOriginalRuns futás az eredeti layoutot javítja, $shuffledRunsCount futás forró keveréssel indul. | 2-opt Top N: $polishTopN")
    val startTime = System.currentTimeMillis()
    val completedRuns = AtomicInteger(0)

    // Párhuzamos indítás a CPU szálain (Dispatchers.Default optimalizálja a magok használatát)
    val deferredResults = (1..runs).map { i ->
        async(Dispatchers.Default) {
            // Az első numOriginalRuns számú szál NEM kér keverést
            val useHotShuffle = i > numOriginalRuns
            val modeTag = if (useHotShuffle) "Shuffled" else "Original"

            val bestLayoutInRun = improveLayout(
                initialLayout = initialLayout,
                corpusData = corpusData,
                iterations = iterations,
                applyHotShuffle = useHotShuffle
            )

            val finalAnalysis = completeLayoutAnalysis(bestLayoutInRun, corpusData, verbose = false)

            val done = completedRuns.incrementAndGet()
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            println("[$done / $runs kész | ${String.format("%.1f", elapsedSec)} mp] --- $i. SA ($modeTag) végzett. Effort: ${String.format("%.4f", finalAnalysis.totalEffort)} ---")

            RunCandidate(
                runIndex = i,
                effort = finalAnalysis.totalEffort,
                layout = bestLayoutInRun,
                mode = modeTag
            )
        }
    }

    val rawResults = deferredResults.awaitAll()
    val saTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("\nMind a $runs SA futás befejeződött (${String.format("%.1f", saTimeSeconds)} mp).")

    // 2. Rendezzük az SA eredményeket, kiszűrjük a duplikációkat, és kivesszük a legjobb polishTopN-t
    val topCandidatesForPolish = rawResults
        .sortedBy { it.effort }
        .distinctBy { layoutSignature(it.layout) }
        .take(polishTopN.coerceAtLeast(0))

    println("\n=== TOP ${topCandidatesForPolish.size} JELÖLT 2-OPT VÉGSŐ CSISZOLÁSA PÁRHUZAMOSAN ===")
    val polishStartTime = System.currentTimeMillis()

    // 3. Párhuzamos 2-opt csiszolás a legjobb jelölteken
    val polishedResults = topCandidatesForPolish.mapIndexed { idx, candidate ->
        async(Dispatchers.Default) {
            val polishedLayout = polishLayout2Opt(candidate.layout, corpusData)
            val polishedAnalysis = completeLayoutAnalysis(polishedLayout, corpusData, verbose = false)
            val diff = polishedAnalysis.totalEffort - candidate.effort

            println("--- Jelölt #${idx + 1} (Szál #${candidate.runIndex}, ${candidate.mode}) csiszolva: ${String.format("%.4f", candidate.effort)} -> ${String.format("%.4f", polishedAnalysis.totalEffort)} (Delta: ${String.format("%.4f", diff)}) ---")

            PolishedCandidate(
                runIndex = candidate.runIndex,
                beforeEffort = candidate.effort,
                afterEffort = polishedAnalysis.totalEffort,
                layout = polishedLayout,
                mode = candidate.mode
            )
        }
    }.awaitAll()

    val polishTimeSeconds = (System.currentTimeMillis() - polishStartTime) / 1000.0
    val totalTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("2-opt csiszolás kész (${String.format("%.1f", polishTimeSeconds)} mp). Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")

    // 4. Újrarendezés a csiszolt pontszámok alapján, ismételt duplikációszűrés, majd Top 3
    val top3: List<Triple<Double, CombinedLayoutData, String>> = if (polishedResults.isNotEmpty()) {
        polishedResults
            .sortedBy { it.afterEffort }
            .distinctBy { layoutSignature(it.layout) }
            .take(3)
            .map { Triple(it.afterEffort, it.layout, it.mode) }
    } else {
        rawResults
            .sortedBy { it.effort }
            .distinctBy { layoutSignature(it.layout) }
            .take(3)
            .map { Triple(it.effort, it.layout, it.mode) }
    }

    println("\n==================================================")
    println("=== A LEGJOBB 3 KIOSZTÁS KIÉRTÉKELÉSE ÉS MENTÉSE ===")
    println("==================================================")

    // Mappák és név generálása
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    val timestamp = LocalDateTime.now().format(formatter)
    val safeLayoutName = initialLayout.name.replace(" ", "_")
    val runDirName = "$safeLayoutName-$timestamp"

    // Layout mappa
    val outputDir = File("generatedkeyboards/$runDirName")
    outputDir.mkdirs()
    println("Eredmények mentése ide: ${outputDir.absolutePath}")

    // --- ImprovementLogs mappa és log + CSV fájl elkészítése ---
    val logDir = File("ImprovementLogs")
    logDir.mkdirs()
    val logFile = File(logDir, "$runDirName.txt")
    val csvFile = File(logDir, "$runDirName.csv")

    writeDetailedLog(
        logFile = logFile,
        logTitle = "SA + 2-OPT OPTIMALIZÁLÁSI LOG",
        keyboardName = initialLayout.name,
        timestamp = timestamp,
        corpusName = corpusData.datasetName,
        algorithmDetails = listOf(
            "Összes szál: $runs (Original: $numOriginalRuns, Shuffled: $shuffledRunsCount) | Iteráció/szál: $iterations",
            "2-opt csiszolási limit (polishTopN): $polishTopN (Ténylegesen csiszolva: ${polishedResults.size}) | Hüvelykujj: Bal=false, Jobb=false"
        ),
        totalTimeSeconds = totalTimeSeconds,
        topResults = top3.map { (effort, _, mode) ->
            val methodStr = if (mode == "Shuffled") "Hot Shuffle (Shuffled)" else "Eredeti Layout finomhangolása (Original)"
            effort to methodStr
        },
        rawRunResults = rawResults.map { RawRunLogEntry(it.runIndex, it.effort, it.mode) },
        polishedResults = polishedResults.map { PolishedRunLogEntry(it.runIndex, it.beforeEffort, it.afterEffort, it.mode) },
        csvFile = csvFile,
        algorithmTag = "SA"
    )
    // ---------------------------------------------------------------

    // A top 3 mentése egymás után
    for ((index, triple) in top3.withIndex()) {
        val rank = index + 1
        val (effort, layout, _) = triple

        layout.name = "${safeLayoutName}_optimized_$rank"

        println("\n>>> $rank. HELYEZETT (${layout.name}) - Total Effort: ${String.format("%.4f", effort)} <<<")

        printLayout(layout)
        // Verbose = true: itt készülnek el az analysis_results mappába a txt fájlok
        completeLayoutAnalysis(layout, corpusData, verbose = true)
        layoutcharefforts(layout)

        // 1. Kép mentése az új mappába
        val imageOutputPath = File(outputDir, "${layout.name}_heatmap.png").absolutePath
        renderCharEffortlayout(layout, imageOutputPath)

        // 2. YAML fájl mentése az új mappába
        val yamlOutputPath = File(outputDir, "${layout.name}.yaml")
        saveLayoutToYaml(layout, originalYamlFile, yamlOutputPath)
    }
}

fun improvementTest (){
    var layout = LayoutParser.parseLayout(File("keyboards/ltsnergomini.yaml"))
    var corpusData = CorpusData.loadFromDataset("huszonkettoHuszonnegyeswikipediahuszonegy.txt")

    var bestlayout=  improveLayout(layout, corpusData)
    printLayout(bestlayout)
    completeLayoutAnalysis(bestlayout, corpusData, verbose = true)
    layoutcharefforts(bestlayout)
    renderCharEffortlayout(bestlayout)

}

fun longerimprovementTest(runs:Int=15) {
    val initialLayout = try {
        LayoutParser.parseLayout(File("keyboards/ltsnergomini.yaml"))
    } catch (e: Exception) {
        println("Hiba a beolvasáskor: ${e.message}")
        return
    }

    val corpusData = CorpusData.loadFromDataset("huszonkettoHuszonnegyeswikipediahuszonegy.txt")

    val results = mutableListOf<Pair<Double, CombinedLayoutData>>()

    println("=== SA OPTIMALIZÁLÁS INDÍTÁSA ($runs FUTÁS) ===")
    val startTime = System.currentTimeMillis()

    // 1. Lefuttatjuk az optimalizálást 15 alkalommal
    for (i in 1..runs) {
        println("\n--- $i. optimalizációs futás indítása ---")

        // Maga a Simulated Annealing motor
        val bestLayoutInRun = improveLayout(initialLayout, corpusData)

        // Még egyszer gyorsan kiértékeljük csendben, hogy biztosan megkapjuk a végleges Total Effort értéket
        val finalAnalysis = completeLayoutAnalysis(bestLayoutInRun, corpusData, verbose = false)

        results.add(Pair(finalAnalysis.totalEffort, bestLayoutInRun))
        println("--- $i. futás kész. Effort: ${String.format("%.4f", finalAnalysis.totalEffort)} ---")
    }

    val totalTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("\nMind a $runs futás befejeződött. Teljes idő: ${String.format("%.1f", totalTimeSeconds)} mp")

    // 2. Rendezzük az eredményeket a legkisebb (legjobb) Effort szerint növekvő sorrendbe
    results.sortBy { it.first }

    // 3. Kiválasztjuk a legjobb 3-at
    val top3 = results.take(3)

    println("\n==================================================")
    println("=== A LEGJOBB 3 KIOSZTÁS KIÉRTÉKELÉSE ÉS MENTÉSE ===")
    println("==================================================")

    for ((index, pair) in top3.withIndex()) {
        val rank = index + 1
        val (effort, layout) = pair

        // Dinamikusan átnevezzük a layoutot. Így a completeLayoutAnalysis
        // automatikusan ebbe a nevű mappába generálja a fájlokat!
        layout.name = "${initialLayout.name}_optimized_$rank"

        println("\n>>> $rank. HELYEZETT (${layout.name}) - Total Effort: ${String.format("%.4f", effort)} <<<")

        printLayout(layout)

        // Verbose = true: itt készülnek el az analysis_results mappába a txt fájlok
        completeLayoutAnalysis(layout, corpusData, verbose = true)

        layoutcharefforts(layout)
        renderCharEffortlayout(layout,)
    }
}