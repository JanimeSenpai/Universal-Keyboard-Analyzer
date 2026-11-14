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
    parallelChipsNSalsaImprovement(
        runs = 32,
        evaluationsPerRun = 50000,
        useSelfTuningLam = true,
        keyboardname = "ltsnergomini"
    )
    //parallelMoeaImprovement(keyboardname = "ltsnergomini")
    //parallelImprovement(128, "ltsnergorowstagger")
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

            // VÁLTOZÁS: Triple-t adunk vissza, hogy megőrizzük a Hot Shuffle információt
            Triple(finalAnalysis.totalEffort, bestLayoutInRun, useHotShuffle)
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
    val polishedResults = topCandidatesForPolish.mapIndexed { idx, candidate ->
        async(Dispatchers.Default) {
            val (saEffort, candidateLayout, usedHotShuffle) = candidate

            val polishedLayout = polishLayout2Opt(candidateLayout, corpusData)
            val polishedAnalysis = completeLayoutAnalysis(polishedLayout, corpusData, verbose = false)
            val diff = polishedAnalysis.totalEffort - saEffort

            println("--- Jelölt #${idx + 1} csiszolva: ${String.format("%.4f", saEffort)} -> ${String.format("%.4f", polishedAnalysis.totalEffort)} (Delta: ${String.format("%.4f", diff)}) ---")

            // VÁLTOZÁS: A csiszolás után is megtartjuk a módszer flag-jét
            Triple(polishedAnalysis.totalEffort, polishedLayout, usedHotShuffle)
        }
    }.awaitAll()

    val polishTimeSeconds = (System.currentTimeMillis() - polishStartTime) / 1000.0
    val totalTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
    println("2-opt csiszolás kész (${String.format("%.1f", polishTimeSeconds)} mp). Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")

    // 4. Újrarendezés a csiszolt pontszámok alapján, ismételt duplikációszűrés, majd Top 3
    val top3 = polishedResults
        .sortedBy { it.first }
        .distinctBy { layoutSignature(it.second) }
        .take(3)

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

    // --- ÚJ RÉSZ: ImprovementLogs mappa és log fájl elkészítése ---
    val logDir = File("ImprovementLogs")
    logDir.mkdirs()
    val logFile = File(logDir, "$runDirName.txt")

    logFile.bufferedWriter().use { writer ->
        writer.write("=== OPTIMALIZÁLÁSI LOG ===")
        writer.newLine()
        writer.write("Billentyűzet: ${initialLayout.name}")
        writer.newLine()
        writer.write("Futtatás időpontja: $timestamp")
        writer.newLine()
        writer.write("Futtatott SA iterációk szálanként: Párhuzamos futás")
        writer.newLine()
        writer.write("Összes szál: $runs (Ebből eredeti: $numOriginalRuns, Hot Shuffle: ${runs - numOriginalRuns})")
        writer.newLine()
        writer.write("Teljes futásidő: ${String.format("%.1f", totalTimeSeconds)} mp")
        writer.newLine()
        writer.newLine()
        writer.write("--- TOP 3 EREDMÉNY ---")
        writer.newLine()

        for ((index, triple) in top3.withIndex()) {
            val rank = index + 1
            val effort = triple.first
            val layout = triple.second
            val usedHotShuffle = triple.third

            val methodStr = if (usedHotShuffle) "Hot Shuffle (Kevert kezdőállapot)" else "Eredeti Layout finomhangolása"

            writer.write("$rank. Helyezett:")
            writer.newLine()
            writer.write(" - Effort pontszám: ${String.format("%.4f", effort)}")
            writer.newLine()
            writer.write(" - Származás: $methodStr")
            writer.newLine()
            writer.newLine()
        }
    }
    println("Log fájl elmentve: ${logFile.absolutePath}")
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