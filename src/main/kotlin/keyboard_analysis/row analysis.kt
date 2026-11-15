package keyboard_analysis

import java.io.File

fun launchRowUsageAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runRowUsageAnalysis(layoutName, datasetName, folder)
}

fun runRowUsageAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): DoubleArray? {
    val layoutFile = LayoutParser.resolveLayoutFile(layoutName, folder)
    if (layoutFile == null || !layoutFile.exists()) {
        println("Hiba: A billentyűzet fájl nem található a '$folder' mappában ($layoutName).")
        return null
    }

    val layoutData = try {
        LayoutParser.parseLayout(layoutFile)
    } catch (e: Exception) {
        println("Hiba a billentyűzet beolvasásakor: ${e.message}")
        return null
    }

    val corpusData = CorpusData.loadFromDataset(datasetName)
    if (corpusData.letters.isEmpty()) {
        println("Hiba: A karaktergyakorisági adatok nem töltődtek be.")
        return null
    }

    val rows = layoutData.getRows()

    return rowUsageAnalysis(
        rows = rows,
        homeRowIndex = layoutData.homeRowIndex,
        letters = corpusData.letters, // Itt már a százalékos Map<String, Double> megy át
        spaceincludedletters = corpusData.spaceincludedletters,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun rowUsageAnalysis(
    rows: List<CharArray>,
    homeRowIndex: Int,
    letters: Map<String, Double>,
    spaceincludedletters:Map<String, Double>,
    verbose: Boolean,
    layoutName: String,
    datasetName: String
): DoubleArray {
    if (verbose) println("Sorok Terhelésének Elemzése (Row Usage) folyamatban...")

    // 1. Kiosztás beolvasása (Itt a Sor (Row) koordináta kell)
    val charToRow = mutableMapOf<String, Int>()

    for (r in rows.indices) {
        for (char in rows[r]) { // <-- Itt is javítva a bejárás
            val lowerChar = char.lowercaseChar()
            // A helykitöltő alulvonást és a szóközöket átugorjuk
            if (lowerChar != '_' && !lowerChar.isWhitespace()) {
                charToRow[lowerChar.toString()] = r
            }
        }
    }
    // Változók a sorok százalékainak gyűjtésére (dinamikusan a sorok számához igazítva)
    val numRows = rows.size
    val rowTotals = DoubleArray(numRows) { 0.0 }
    val rowTotalSpace = DoubleArray(numRows) { 0.0 }

    // 2. Karaktergyakoriság fájl/adat feldolgozása
    for ((letter, percentage) in letters) {
        val row = charToRow[letter]
        if (row != null) {
            rowTotals[row] += percentage
        }
    }
    //szóközös verzió
    for ((letter, percentage) in spaceincludedletters) {
        val row = charToRow[letter]
        if (row != null) {
            rowTotalSpace[row] += percentage
        }
    }

    // Normalizálás 100%-ra (szóköz és ismeretlen karakterek nélkül)
    // Mivel ezek eleve százalékok, a totalValidHits kb 98-100% körül lesz, a hiányzó karakterektől függően
    val totalValidHits = rowTotals.sum()
    val rowRatios = DoubleArray(numRows) { 0.0 }

    if (totalValidHits > 0) {
        for (i in 0 until numRows) {
            rowRatios[i] = (rowTotals[i] / totalValidHits) * 100.0
        }
    }

    // 3. Eredmények kiírása
    if (verbose) {
        val resultDir = File("analysis_results/$datasetName/$layoutName")
        resultDir.mkdirs()

        val outputFile = File(resultDir, "row_usage_analyzed.txt")

        outputFile.bufferedWriter().use { writer ->
            writer.write("--- SOROK TERHELÉSÉNEK ARÁNYA (ROW USAGE) ---")
            writer.newLine()
            writer.write("(Normalizálva, kizárólag a kiosztáson szereplő karakterekre)")
            writer.newLine()
            writer.newLine()

            for (i in 0 until numRows) {
                // Dinamikus sor-elnevezés
                val rowName = when (i - homeRowIndex) {
                    -2 -> "Számsor (Num)"
                    -1 -> "Felső sor (Top)"
                    0 -> "Alapsor (Home)"
                    1 -> "Alsó sor (Bottom)"
                    else -> "Sor $i"
                }

                writer.write("$rowName: %.2f%%".format(rowRatios[i]))
                writer.newLine()
            }

            writer.newLine()
            writer.write("Nyers gyakoriság összeg (szóköz nélkül):")
            writer.newLine()
            for (i in 0 until numRows) {
                val rowName = when (i - homeRowIndex) {
                    -2 -> "Számsor (Num)"
                    -1 -> "Felső sor (Top)"
                    0 -> "Alapsor (Home)"
                    1 -> "Alsó sor (Bottom)"
                    else -> "Sor $i"
                }
                writer.write("$rowName: %.4f%%".format(rowTotalSpace[i]))
                writer.newLine()
            }
        }

        // Metrikák mentése
        for (i in 0 until numRows) {
            val metricName = when (i - homeRowIndex) {
                -2 -> "row_num"
                -1 -> "row_top"
                0 -> "row_home"
                1 -> "row_bottom"
                else -> "row_$i"
            }
            saveRawMetric(datasetName, layoutName, metricName, rowRatios[i])
        }

        println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Home sor aránya: %.2f%%)".format(
            if (homeRowIndex in rowRatios.indices) rowRatios[homeRowIndex] else 0.0
        ))
    }

    return rowRatios
}