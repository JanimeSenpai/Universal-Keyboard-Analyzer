package keyboard_analysis

import java.io.File

fun launchHalfScissorAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runHalfScissorAnalysis(layoutName, datasetName, folder)
}

fun runHalfScissorAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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

    val charToPos = buildCharPositionMap(layoutData)

    return halfScissorAnalysis(
        charPositions = charToPos,
        bigrams = corpusData.bigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun halfScissorAnalysis(
    charPositions: Map<Char, KeyPosition>,
    bigrams: Map<String, Double>,
    verbose: Boolean,
    layoutName: String = "",
    datasetName: String = ""
): Double {
    if (verbose) println("Half Scissor Bigrams (HSB) elemzése folyamatban...")

    var totalHalfScissor = 0.0

    for ((bigram, percentage) in bigrams) {
        if (bigram.length != 2) continue
        val p1 = charPositions[bigram[0]] ?: continue
        val p2 = charPositions[bigram[1]] ?: continue

        // Azonos kéz, különböző ujjak, hüvelykujjat kihagyjuk
        if (p1.hand == p2.hand && p1.finger != p2.finger && p1.finger != 4 && p1.finger != 5 && p2.finger != 4 && p2.finger != 5) {

            // HSB: PONTOSAN 1 sor különbség van a két billentyű között
            if (Math.abs(p1.row - p2.row) == 1) {
                val lowerPos = if (p1.row > p2.row) p1 else p2
                val higherPos = if (p1.row < p2.row) p1 else p2

                // Ujjindexek normalizálása: 0 (kisujj) -> 3 (mutatóujj)
                val fLower = if (lowerPos.hand == 0) lowerPos.finger else 9 - lowerPos.finger
                val fHigher = if (higherPos.hand == 0) higherPos.finger else 9 - higherPos.finger

                val isHalfScissor = when (fLower) {
                    // Középső ujj lent -> Mindig természetellenes és büntetendő
                    2 -> true

                    // Gyűrűs ujj lent -> Csak kisujj (0) vagy mutató (3) ellen büntetendő
                    // (Ha a középső (2) van fent, az nem baj)
                    1 -> fHigher == 0 || fHigher == 3

                    // Kisujj lent -> Sosem büntetendő (nem baj, ha lentebb van bármelyiknél)
                    0 -> false

                    // Mutatóujj lent -> Sosem büntetendő
                    else -> false
                }

                if (isHalfScissor) totalHalfScissor += percentage
            }
        }
    }

    if (verbose) {
        if (layoutName.isNotEmpty() && datasetName.isNotEmpty()) {
            val resultDir = java.io.File("analysis_results/$datasetName/$layoutName")
            resultDir.mkdirs()
            val outputFile = java.io.File(resultDir, "half_scissor_analyzed.txt")

            outputFile.bufferedWriter().use { writer ->
                writer.write("Teljes Half Scissor (HSB): %.4f%%".format(totalHalfScissor))
                writer.newLine()
            }
            saveRawMetric(datasetName, layoutName, "half_scissor_total", totalHalfScissor)
            println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (HSB: %.4f%%)".format(totalHalfScissor))
        } else {
            println("Half Scissor Bigrams (HSB): ${String.format("%.3f", totalHalfScissor)}%")
        }
    }

    return totalHalfScissor
}