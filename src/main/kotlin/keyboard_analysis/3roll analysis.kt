package keyboard_analysis

import java.io.File


fun launch3RollAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    run3RollAnalysis(layoutName, datasetName, folder)
}

fun run3RollAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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
    if (corpusData.trigrams.isEmpty()) {
        println("Hiba: A trigram gyakorisági adatok nem töltődtek be.")
        return null
    }

    // 1. Kéz- és ujj-hozzárendelés feltérképezése (10 ujj támogatással)
    val charToHandAndFingerMap = mutableMapOf<Char, Pair<Int, Int>>()
    val fingerSets = layoutData.getFingerCharacterSets()
    val fingerKeys = listOf("L_P", "L_R", "L_M", "L_I", "L_T", "R_T", "R_I", "R_M", "R_R", "R_P")

    for ((index, key) in fingerKeys.withIndex()) {
        val hand = if (key.startsWith("L")) 0 else 1 // 0 = Bal, 1 = Jobb
        fingerSets[key]?.forEach { char ->
            charToHandAndFingerMap[char] = Pair(hand, index)
        }
    }

    return threeRollAnalysis(
        charMap = charToHandAndFingerMap,
        trigrams = corpusData.trigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun threeRollAnalysis(
    charMap: Map<Char, Pair<Int, Int>>,
    trigrams: Map<String, Double>,
    verbose: Boolean,
    layoutName: String,
    datasetName: String
): Double {
    if (verbose) println("3Roll (Egykezes rollok Inward/Outward) elemzése folyamatban...")

    var total3Rolls = 0.0
    var inward3Rolls = 0.0
    var outward3Rolls = 0.0

    // 2. Trigram adatok feldolgozása a memóriából
    for ((trigram, percentage) in trigrams) {
        if (trigram.length == 3) {
            val info1 = charMap[trigram[0]]
            val info2 = charMap[trigram[1]]
            val info3 = charMap[trigram[2]]

            if (info1 != null && info2 != null && info3 != null) {
                val hand1 = info1.first
                val hand2 = info2.first
                val hand3 = info3.first
                val finger1 = info1.second
                val finger2 = info2.second
                val finger3 = info3.second

                if (hand1 == hand2 && hand2 == hand3) {
                    val isIncreasing = finger1 < finger2 && finger2 < finger3
                    val isDecreasing = finger1 > finger2 && finger2 > finger3

                    if (isIncreasing || isDecreasing) {
                        total3Rolls += percentage
                        if (hand1 == 0) { // BAL KÉZ
                            if (isIncreasing) inward3Rolls += percentage
                            if (isDecreasing) outward3Rolls += percentage
                        } else { // JOBB KÉZ
                            if (isDecreasing) inward3Rolls += percentage
                            if (isIncreasing) outward3Rolls += percentage
                        }
                    }
                }
            }
        }
    }

    // --- ÚJ RÉSZ: In:Out Ratio kiszámítása ---
    val inOutRatio = if (outward3Rolls > 0) inward3Rolls / outward3Rolls else 0.0

    // 3. Eredmények kiírása
    if (verbose) {
        val resultDir = File("analysis_results/$datasetName/$layoutName")
        resultDir.mkdirs()

        val outputFile = File(resultDir, "3roll_analyzed.txt")

        outputFile.bufferedWriter().use { writer ->
            writer.write("Teljes 3Roll (Egykezes gördülés): %.4f%%".format(total3Rolls))
            writer.newLine()
            writer.newLine()
            writer.write("Irány szerinti bontás:")
            writer.newLine()
            writer.write("-> Inward 3Roll: %.4f%%".format(inward3Rolls))
            writer.newLine()
            writer.write("-> Outward 3Roll: %.4f%%".format(outward3Rolls))
            writer.newLine()
            writer.newLine()

            // Az In:Out arány kiírása
            writer.write("In:Out Ratio: %.4f".format(inOutRatio))
            writer.newLine()
            if (inOutRatio > 1.0) {
                writer.write("(A kiosztás belső orientáltságú - kényelmesebb)")
            } else if (inOutRatio < 1.0 && inOutRatio > 0.0) {
                writer.write("(A kiosztás külső orientáltságú - megterhelőbb)")
            }
        }
        saveRawMetric(datasetName, layoutName, "3roll", total3Rolls)
        println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. In:Out Ratio: %.4f".format(inOutRatio))
    }

    // --- Visszaadjuk az értéket a totalEffort számításhoz ---
    return total3Rolls
}