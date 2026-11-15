package keyboard_analysis

import java.io.File

fun launchRollAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runRollAnalysis(layoutName, datasetName, folder)
}

fun runRollAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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

    // 1. Kéz- és ujj-hozzárendelés feltérképezése
    // A szótár értéke egy Pair lesz: (Kéz indexe, Ujj indexe)
    val charToHandAndFingerMap = mutableMapOf<Char, Pair<Int, Int>>()
    val fingerSets = layoutData.getFingerCharacterSets()
    val fingerKeys = listOf("L_P", "L_R", "L_M", "L_I", "L_T", "R_T", "R_I", "R_M", "R_R", "R_P")

    for ((index, key) in fingerKeys.withIndex()) {
        val hand = if (key.startsWith("L")) 0 else 1 // 0 = Bal, 1 = Jobb
        fingerSets[key]?.forEach { char ->
            charToHandAndFingerMap[char] = Pair(hand, index)
        }
    }

    return rollAnalysis(
        charMap = charToHandAndFingerMap,
        trigrams = corpusData.trigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun rollAnalysis(
    charMap: Map<Char, Pair<Int, Int>>,
    trigrams: Map<String, Double>,
    verbose: Boolean,
    layoutName: String,
    datasetName: String
): Double {
    if (verbose) println("Rollok (2-1 és 1-2) elemzése folyamatban...")

    var totalRolls = 0.0
    var rolls2_1 = 0.0 // Bal-Bal-Jobb vagy Jobb-Jobb-Bal
    var rolls1_2 = 0.0 // Bal-Jobb-Jobb vagy Jobb-Bal-Bal

    // 2. Trigram fájl/adatok feldolgozása
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

                // --- ROLL (2-1) VIZSGÁLAT ---
                // 1. és 2. betű azonos kézen, 3. betű másik kézen, ÉS az 1-2 betű más ujjon van
                if (hand1 == hand2 && hand2 != hand3 && finger1 != finger2) {
                    rolls2_1 += percentage
                    totalRolls += percentage
                }

                // --- ROLL (1-2) VIZSGÁLAT ---
                // 1. betű egyik kézen, 2. és 3. betű azonos másik kézen, ÉS a 2-3 betű más ujjon van
                else if (hand1 != hand2 && hand2 == hand3 && finger2 != finger3) {
                    rolls1_2 += percentage
                    totalRolls += percentage
                }
            }
        }
    }

    // 3. Eredmények kiírása
    if (verbose) {
        val resultDir = File("analysis_results/$datasetName/$layoutName")
        resultDir.mkdirs()

        val outputFile = File(resultDir, "2roll_analyzed.txt")

        outputFile.bufferedWriter().use { writer ->
            writer.write("Teljes Roll (2-1 és 1-2 együttesen): %.4f%%".format(totalRolls))
            writer.newLine()
            writer.newLine()
            writer.write("Részletes bontás:")
            writer.newLine()
            writer.write("-> 2-1 Rollok (Azonos-Azonos-Más): %.4f%%".format(rolls2_1))
            writer.newLine()
            writer.write("-> 1-2 Rollok (Más-Azonos-Azonos): %.4f%%".format(rolls1_2))
            writer.newLine()
        }
        saveRawMetric(datasetName, layoutName, "2roll", totalRolls)
        println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Teljes Roll: %.4f%%)".format(totalRolls))
    }

    return totalRolls
}