package keyboard_analysis

import java.io.File

fun launchHandBalanceAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runHandBalanceAnalysis(layoutName, datasetName, folder)
}

fun runHandBalanceAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Pair<Double, Double>? {
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

    return handBalanceAnalysis(
        leftChars = layoutData.leftChars,
        rightChars = layoutData.rightChars,
        letters = corpusData.letters, // A szóköz nélküli százalékos térkép!
        spaceincludedletters = corpusData.spaceincludedletters,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun handBalanceAnalysis(
    leftChars: Array<CharArray>,
    rightChars: Array<CharArray>,
    letters: Map<String, Double>,
    spaceincludedletters: Map<String, Double>,
    verbose: Boolean,
    layoutName: String,
    datasetName: String
): Pair<Double, Double> {
    if (verbose) println("Bal-Jobb Kéz Terhelés Elemzése folyamatban...")

    // 1. Kiosztás beolvasása közvetlenül a bal és jobb oldali mátrixokból
    val charToHand = mutableMapOf<String, Int>()

    // Bal kéz karakterei (0)
    for (row in leftChars) {
        for (char in row) {
            val lowerChar = char.lowercaseChar()
            if (lowerChar != '_' && !lowerChar.isWhitespace()) {
                charToHand[lowerChar.toString()] = 0
            }
        }
    }

    // Jobb kéz karakterei (1)
    for (row in rightChars) {
        for (char in row) {
            val lowerChar = char.lowercaseChar()
            if (lowerChar != '_' && !lowerChar.isWhitespace()) {
                charToHand[lowerChar.toString()] = 1
            }
        }
    }

    var leftHand = 0.0
    var rightHand = 0.0
    var leftHandTotal = 0.0
    var rightHandTotal = 0.0
//println("space hand is ${charToHand["[SPACE]"]}")
    // 2. Karaktergyakoriság adatok feldolgozása (szóköz nélkül!)
    for ((letter, percentage) in letters) {
        val hand = charToHand[letter]
        if (hand != null) {
            if (hand == 0) {
                leftHand += percentage
            } else {
                rightHand += percentage
            }
        }
    }

    for ((letter, percentage) in spaceincludedletters) {
        val hand = charToHand[letter]
        if (hand != null) {
            if (hand == 0) {
                leftHandTotal += percentage
            } else {
                rightHandTotal += percentage
            }
        }
    }


    // A normalizálást továbbra is elvégezzük, biztosítva, hogy
    // pontosan 100%-ot adjon ki a bal és jobb aránya együttesen.
    val totalValidHits = leftHand + rightHand

    val leftRatio = if (totalValidHits > 0) (leftHand / totalValidHits) * 100.0 else 0.0
    val rightRatio = if (totalValidHits > 0) (rightHand / totalValidHits) * 100.0 else 0.0

    // 3. Eredmények kiírása
    if (verbose) {
        val resultDir = File("analysis_results/$datasetName/$layoutName")
        resultDir.mkdirs()

        val outputFile = File(resultDir, "hand_balance_analyzed.txt")

        outputFile.bufferedWriter().use { writer ->
            writer.write("--- BAL-JOBB KÉZ HASZNÁLATI ARÁNY ---")
            writer.newLine()
            writer.write("Bal kéz: %.2f%%".format(leftRatio))
            writer.newLine()
            writer.write("Jobb kéz: %.2f%%".format(rightRatio))
            writer.newLine()
            writer.newLine()
            writer.write("Nyers százalékos összeg (szóköz és layoutban nem szereplő karakterek nélkül):")
            writer.newLine()
            writer.write("Bal kéz: %.4f%%".format(leftHandTotal))
            writer.newLine()
            writer.write("Jobb kéz: %.4f%%".format(rightHandTotal))
            writer.newLine()
        }

        saveRawMetric(datasetName, layoutName, "left_hand", leftRatio)
        saveRawMetric(datasetName, layoutName, "right_hand", rightRatio)

        println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Bal: %.2f%% - Jobb: %.2f%%)".format(leftRatio, rightRatio))
    }

    return Pair(leftRatio, rightRatio)
}