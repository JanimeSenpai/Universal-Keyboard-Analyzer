package keyboard_analysis

import java.io.File

fun launchRedirectAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runRedirectAnalysis(layoutName, datasetName, folder)
}

fun runRedirectAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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

    return redirectAnalysis(
        charMap = charToHandAndFingerMap,
        trigrams = corpusData.trigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun redirectAnalysis(
    charMap: Map<Char, Pair<Int, Int>>,
    trigrams: Map<String, Double>,
    verbose: Boolean,
    layoutName: String,
    datasetName: String
): Double {
    if (verbose) println("Redirect (Egykezes irányváltás) elemzése folyamatban...")

    var totalRedirects = 0.0
    var leftHandRedirects = 0.0
    var rightHandRedirects = 0.0

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

                // --- REDIRECT VIZSGÁLAT ---
                // Feltétel 1: Mindhárom betű ugyanazon a kézen van
                if (hand1 == hand2 && hand2 == hand3) {

                    // Feltétel 2: Az irány menet közben megfordul
                    // "Hegy" (Peak): Befelé indul, aztán kifelé pattan (pl. Kisujj -> Mutatóujj -> Középső)
                    val isPeak = finger1 < finger2 && finger2 > finger3
                    // "Völgy" (Valley): Kifelé indul, aztán befelé pattan (pl. Mutatóujj -> Kisujj -> Gyűrűs)
                    val isValley = finger1 > finger2 && finger2 < finger3

                    if (isPeak || isValley) {
                        totalRedirects += percentage

                        // Kéz szerinti bontás
                        if (hand1 == 0) {
                            leftHandRedirects += percentage
                        } else {
                            rightHandRedirects += percentage
                        }
                    }
                }
            }
        }
    }

    // 3. Eredmények kiírása
    if (verbose) {
        val resultDir = File("analysis_results/$datasetName/$layoutName")
        resultDir.mkdirs()

        val outputFile = File(resultDir, "redirect_analyzed.txt")

        outputFile.bufferedWriter().use { writer ->
            writer.write("Teljes Redirect (Egykezes irányváltások): %.4f%%".format(totalRedirects))
            writer.newLine()
            writer.write("(Cél: Ezt az értéket minél alacsonyabban tartani)")
            writer.newLine()
            writer.newLine()

            writer.write("Kéz szerinti bontás:")
            writer.newLine()
            writer.write("-> Bal kéz Redirect: %.4f%%".format(leftHandRedirects))
            writer.newLine()
            writer.write("-> Jobb kéz Redirect: %.4f%%".format(rightHandRedirects))
            writer.newLine()
        }
        saveRawMetric(datasetName, layoutName, "redirect_total", totalRedirects)
        println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Teljes Redirect: %.4f%%)".format(totalRedirects))
    }

    // --- Visszaadjuk az értéket a motorhoz ---
    return totalRedirects
}