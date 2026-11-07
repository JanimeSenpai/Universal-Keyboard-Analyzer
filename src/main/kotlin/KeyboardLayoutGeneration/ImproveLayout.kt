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

fun main(){
    parallelImprovement(416)
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
fun parallelImprovement(runs: Int = 25, keyboardname: String = "ltsnergomini") = runBlocking {
    val originalYamlFile = File("keyboards/${keyboardname}.yaml")
    val initialLayout = try {
        LayoutParser.parseLayout(originalYamlFile)
    } catch (e: Exception) {
        println("Hiba a beolvasáskor: ${e.message}")
        return@runBlocking
    }

    val corpusData = CorpusData.loadFromDataset("huszonkettoHuszonnegyeswikipediahuszonegy.txt")

    // Kiszámoljuk, hány szál induljon az eredeti layoutból (N/10, de minimum 1)
    val numOriginalRuns = maxOf(1, runs / 10)

    println("=== SA OPTIMALIZÁLÁS INDÍTÁSA ($runs FUTÁS PÁRHUZAMOSAN) ===")
    println("-> $numOriginalRuns futás az eredeti layoutot javítja, ${runs - numOriginalRuns} futás forró keveréssel indul.")
    val startTime = System.currentTimeMillis()

    // Párhuzamos indítás a CPU szálain (Dispatchers.Default optimalizálja a magok használatát)
    val deferredResults = (1..runs).map { i ->
        async(Dispatchers.Default) {
            // Az első numOriginalRuns számú szál NEM kér keverést
            val useHotShuffle = i > numOriginalRuns
            // A deepCopy() miatt minden szál a saját layout példányával dolgozik

            val bestLayoutInRun = improveLayout(
                initialLayout = initialLayout,
                corpusData = corpusData,
                applyHotShuffle = useHotShuffle
            )

            val finalAnalysis = completeLayoutAnalysis(bestLayoutInRun, corpusData, verbose = false)

            val modeStr = if (useHotShuffle) "Kevert" else "Eredeti"
            println("--- $i. szál végzett ($modeStr). SA Effort: ${String.format("%.4f", finalAnalysis.totalEffort)} ---")

            // Visszaadjuk a kiszámolt párost
            Pair(finalAnalysis.totalEffort, bestLayoutInRun)
        }
    }

    val rawResults = deferredResults.awaitAll()
    val saTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("\nMind a $runs SA futás befejeződött (${String.format("%.1f", saTimeSeconds)} mp).")

    // 2. Rendezzük az SA eredményeket, kiszűrjük a duplikációkat, és kivesszük a legjobb 32-t
    val topCandidatesForPolish = rawResults
        .sortedBy { it.first }
        .distinctBy { layoutSignature(it.second) }
        .take(32)

    println("\n=== TOP ${topCandidatesForPolish.size} JELÖLT 2-OPT VÉGSŐ CSISZOLÁSA PÁRHUZAMOSAN ===")
    val polishStartTime = System.currentTimeMillis()

    // 3. Párhuzamos 2-opt csiszolás a legjobb jelölteken (egyetlen 32 szálas CPU-hullám)
    val polishedResults = topCandidatesForPolish.mapIndexed { idx, (saEffort, candidateLayout) ->
        async(Dispatchers.Default) {
            val polishedLayout = polishLayout2Opt(candidateLayout, corpusData)
            val polishedAnalysis = completeLayoutAnalysis(polishedLayout, corpusData, verbose = false)
            val diff = polishedAnalysis.totalEffort - saEffort
            println("--- Jelölt #${idx + 1} csiszolva: ${String.format("%.4f", saEffort)} -> ${String.format("%.4f", polishedAnalysis.totalEffort)} (Delta: ${String.format("%.4f", diff)}) ---")
            Pair(polishedAnalysis.totalEffort, polishedLayout)
        }
    }.awaitAll()

    val polishTimeSeconds = (System.currentTimeMillis() - polishStartTime) / 1000.0
    val totalTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("2-opt csiszolás kész (${String.format("%.1f", polishTimeSeconds)} mp). Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")

    // 4. Újrarendezés a csiszolt pontszámok alapján, ismételt duplikációszűrés (ha két szál ugyanabba a 2-opt minimumba esett), majd Top 3
    val top3 = polishedResults
        .sortedBy { it.first }
        .distinctBy { layoutSignature(it.second) }
        .take(3)

    println("\n==================================================")
    println("=== A LEGJOBB 3 KIOSZTÁS KIÉRTÉKELÉSE ÉS MENTÉSE ===")
    println("==================================================")

    // Mappa létrehozása a mentéshez
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    val timestamp = LocalDateTime.now().format(formatter)
    val safeLayoutName = initialLayout.name.replace(" ", "_")

    val runDirName = "$safeLayoutName-$timestamp"
    val outputDir = File("generatedkeyboards/$runDirName")
    outputDir.mkdirs() // Létrehozza a mappát, ha nem létezik

    println("Eredmények mentése ide: ${outputDir.absolutePath}")

    // A top 3 mentése egymás után
    for ((index, pair) in top3.withIndex()) {
        val rank = index + 1
        val (effort, layout) = pair

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