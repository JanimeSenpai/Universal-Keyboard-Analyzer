package keyboard_analysis

import java.io.File


fun launchFullScissorAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runFullScissorAnalysis(layoutName, datasetName, folder)
}

fun runFullScissorAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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

    // 1. Kéz, Ujj és Koordináta hozzárendelés feltérképezése
    val charToPos = buildCharPositionMap(layoutData)

    return fullScissorAnalysis(
        charPositions = charToPos,
        bigrams = corpusData.bigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun fullScissorAnalysis(
    charPositions: Map<Char, KeyPosition>,
    bigrams: Map<String, Double>, // <-- JAVÍTVA: Long helyett Double
    verbose: Boolean,
    layoutName: String = "",      // <-- JAVÍTVA: Hozzáadva a launcher miatt
    datasetName: String = ""      // <-- JAVÍTVA: Hozzáadva a launcher miatt
): Double {
    if (verbose) println("Full Scissor Bigrams (FSB) elemzése folyamatban...")

    var totalScissor = 0.0 // <-- JAVÍTVA: Long helyett Double, és csak ezt adjuk össze

    for ((bigram, percentage) in bigrams) { // freq helyett percentage
        if (bigram.length != 2) continue
        val p1 = charPositions[bigram[0]] ?: continue
        val p2 = charPositions[bigram[1]] ?: continue

        // Azonos kéz, különböző ujjak, hüvelykujjat (finger == 4 vagy 5) kihagyjuk
        if (p1.hand == p2.hand && p1.finger != p2.finger && p1.finger != 4 && p1.finger != 5 && p2.finger != 4 && p2.finger != 5) {
            if (Math.abs(p1.row - p2.row) >= 2) {//legalább két sor különbség van a két billentyű között
                // A 'row' index fentről indul, tehát a nagyobb index a fizikailag alsóbb sor
                val lowerPos = if (p1.row > p2.row) p1 else p2
                val higherPos = if (p1.row < p2.row) p1 else p2

                // Normalizáljuk az ujjindexet kéztől függetlenül, hogy 0-tól (kisujj) 3-ig (mutató) fusson
                val fLower = if (lowerPos.hand == 0) lowerPos.finger else 9 - lowerPos.finger
                val fHigher = if (higherPos.hand == 0) higherPos.finger else 9 - higherPos.finger


                //todo ezeket ízlés szerint finomhangolni
                val isScissor = when (fLower) {
                    2 -> true // Középső ujj lent -> Mindig büntetendő
                    1 -> true // Gyűrűs ujj lent -> Mindig büntetendő
                    0 -> fHigher == 2 || fHigher == 1//kisujj lent keyboard layout doc szerint csak a magas mutatóujj büntetendő itt, de nekem pont az elviselhető, a több meg nem szeretem//fHigher == 3 || fHigher == 1 // Kisujj lent -> Csak I és R ellen büntetendő
                    else -> false // Mutatóujj lent (fLower == 3) -> Sosem büntetendő
                }

                if (isScissor) totalScissor += percentage // Közvetlenül a százalékot adjuk hozzá
            }
        }
    }

    // Fájlba és konzolra írás, ha a verbose be van kapcsolva és kaptunk neveket
    if (verbose) {
        if (layoutName.isNotEmpty() && datasetName.isNotEmpty()) {
            val resultDir = java.io.File("analysis_results/$datasetName/$layoutName")
            resultDir.mkdirs()
            val outputFile = java.io.File(resultDir, "full_scissor_analyzed.txt")

            outputFile.bufferedWriter().use { writer ->
                writer.write("Teljes Full Scissor (FSB): %.4f%%".format(totalScissor))
                writer.newLine()
            }
            saveRawMetric(datasetName, layoutName, "full_scissor_total", totalScissor)
            println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (FSB: %.4f%%)".format(totalScissor))
        } else {
            println("Full Scissor Bigrams (FSB): ${String.format("%.3f", totalScissor)}%")
        }
    }

    return totalScissor
}