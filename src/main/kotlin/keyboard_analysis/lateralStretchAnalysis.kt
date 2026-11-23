package keyboard_analysis


import java.io.File

fun launchLateralStretchAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runLateralStretchAnalysis(layoutName, datasetName, folder)
}

fun runLateralStretchAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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
    if (corpusData.bigrams.isEmpty()) {
        println("Hiba: A bigram gyakorisági adatok nem töltődtek be.")
        return null
    }

    // 1. Kéz, Ujj és Koordináta hozzárendelés feltérképezése a közös függvénnyel
    val charToPos = buildCharPositionMap(layoutData)

    return lateralStretchAnalysis(
        charPositions = charToPos,
        bigrams = corpusData.bigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}


fun lateralStretchAnalysis(
    charPositions: Map<Char, KeyPosition>,
    bigrams: Map<String, Double>, // Itt is Double-re cserélve a százalékok miatt
    verbose: Boolean,
    layoutName: String = "",
    datasetName: String = ""
): Double {
    if (verbose) println("Lateral Stretch (Oldalirányú nyújtózás) elemzése folyamatban...")

    var totalStretch = 0.0

    for ((bigram, percentage) in bigrams) {
        if (bigram.length != 2) continue
        val p1 = charPositions[bigram[0]] ?: continue
        val p2 = charPositions[bigram[1]] ?: continue

        // Azonos kéz, hüvelykujjat (finger == 4 vagy 5) kihagyjuk, és a logikai oszlopok távolsága legalább 4 (pl. mutatóujj és kisujj közötti extrém nyújtózás)
        if (p1.hand == p2.hand && p1.finger != 4 && p1.finger != 5 && p2.finger != 4 && p2.finger != 5 && Math.abs(p1.col - p2.col) >= 4) {
            totalStretch += percentage // Közvetlenül a százalékot adjuk hozzá
        }
    }

    // Fájlba és konzolra írás, ha a verbose be van kapcsolva és kaptunk neveket
    if (verbose) {
        if (layoutName.isNotEmpty() && datasetName.isNotEmpty()) {
            val resultDir = java.io.File("analysis_results/$datasetName/$layoutName")
            resultDir.mkdirs()
            val outputFile = java.io.File(resultDir, "lateral_stretch_analyzed.txt")

            outputFile.bufferedWriter().use { writer ->
                writer.write("Teljes Lateral Stretch: %.4f%%".format(totalStretch))
                writer.newLine()
            }
             saveRawMetric(datasetName, layoutName, "lateral_stretch_total", totalStretch)
            println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Lateral Stretch: %.4f%%)".format(totalStretch))
        } else {
            println("Lateral Stretch Bigrams: ${String.format("%.3f", totalStretch)}%")
        }
    }

    return totalStretch
}