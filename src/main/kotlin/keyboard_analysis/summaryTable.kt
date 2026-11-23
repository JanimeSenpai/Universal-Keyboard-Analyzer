package keyboard_analysis

import java.io.File
import java.util.Locale



// Korábbi nyers adatok törlése, hogy ne duplázódjanak újra-futtatáskor
fun clearRawMetrics(datasetName: String, layoutName: String) {
    val file = File("analysis_results/$datasetName/$layoutName/raw_metrics.txt")
    if (file.exists()) file.delete()
}

// Nyers adat kimentése
fun saveRawMetric(datasetName: String, layoutName: String, key: String, value: Double) {
    val dir = File("analysis_results/$datasetName/$layoutName")
    dir.mkdirs()
    val file = File(dir, "raw_metrics.txt")
    // Mindig ponttal menti a tizedestörtet
    file.appendText("$key=${String.format(Locale.US, "%.4f", value)}\n")
}

// A CSV generáló UI-ja
fun launchGenerateSummaryCsv() {
    println("--- CSV ÖSSZESÍTŐ GENERÁLÁSA ---")
    println("Kérem az adathalmaz nevét (ahol a layoutok mappái vannak, pl. 'szoveg.txt'):")
    var datasetName = readln().trim()
    if (!datasetName.endsWith(".txt")) datasetName += ".txt"

    generateSummaryCsv(datasetName)
}

// A tényleges CSV generáló logika
fun generateSummaryCsv(datasetName: String) {
    val baseDir = File("analysis_results/$datasetName")
    if (!baseDir.exists() || !baseDir.isDirectory) {
        println("Hiba: A mappa nem található ($baseDir).")
        return
    }

    // Itt definiáljuk a CSV oszlopait és azok sorrendjét
    val metricKeys = listOf(
        "total_effort",
        "char_reach_effort",
        "base_gravity_effort",
        "left_hand", "right_hand",
        "row_home",
        "sfb_total", "space_sfs1_total", "space_sfs1_penalty",
        "alternation_total",
        "2roll", "3roll",
        "redirect_total",
        "pinky_off",
        "lateral_stretch_total",
        "pinky_stretch_total",
        "full_scissor_total",
        "half_scissor_total"
    )

    val summaryFile = File(baseDir, "összesítő_táblázat.csv")
    // Kikeressük az összes mappát, ami a korpuszon belül van (ezek a layoutok)
    val layouts = baseDir.listFiles { file -> file.isDirectory } ?: emptyArray()

    if (layouts.isEmpty()) {
        println("Nem találtam kiosztás mappákat a $baseDir helyen.")
        return
    }

    summaryFile.bufferedWriter().use { writer ->
        // Fejléc
        writer.write("Layout," + metricKeys.joinToString(","))
        writer.newLine()

        for (layoutDir in layouts) {
            val metricsFile = File(layoutDir, "raw_metrics.txt")
            val metricsMap = mutableMapOf<String, String>()

            if (metricsFile.exists()) {
                metricsFile.useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split("=")
                        if (parts.size == 2) {
                            metricsMap[parts[0].trim()] = parts[1].trim()
                        }
                    }
                }
            }

            val row = mutableListOf(layoutDir.name)
            for (key in metricKeys) {
                // Ha a metrika nem található az adott layoutnál, N/A kerül a helyére
                row.add(metricsMap[key] ?: "N/A")
            }
            writer.write(row.joinToString(","))
            writer.newLine()
        }
    }

    println("A CSV összesítő sikeresen elkészült: ${summaryFile.path}")
}