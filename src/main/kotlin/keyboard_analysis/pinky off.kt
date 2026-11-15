package keyboard_analysis
import java.io.File

fun launchPinkyOffAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runPinkyOffAnalysis(layoutName, datasetName, folder)
}

fun runPinkyOffAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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

    val fingerSets = layoutData.getFingerCharacterSets()
    val leftPinkyChars = fingerSets["L_P"] ?: emptySet()
    val rightPinkyChars = fingerSets["R_P"] ?: emptySet()

    // 1. Dinamikus alapgomb (Anchor) keresés a HardwareYaml alapján
    var leftPinkyHomeChar: Char? = null
    // Megkeressük, melyik oszlop(ok)hoz van rendelve a bal kisujj ('P')
    val leftPinkyColIndex = layoutData.hardwareYaml.left.fingers.indexOfFirst { it.uppercase() == "P" }
    if (leftPinkyColIndex != -1) {
        // Kikeressük ennek a kisujjoszlopnak az anchor (horgony) oszlopát
        val leftPinkyAnchorCol = layoutData.hardwareYaml.left.anchors[leftPinkyColIndex]

        // A karakter az [X = homeRowIndex, Y = anchorCol] koordinátán lesz a bal mátrixban
        if (layoutData.homeRowIndex in layoutData.leftChars.indices && leftPinkyAnchorCol in layoutData.leftChars[layoutData.homeRowIndex].indices) {
            val charAtAnchor = layoutData.leftChars[layoutData.homeRowIndex][leftPinkyAnchorCol]
            if (charAtAnchor != '_' && !charAtAnchor.isWhitespace()) {
                leftPinkyHomeChar = charAtAnchor.lowercaseChar()
            }
        }
    }

    var rightPinkyHomeChar: Char? = null
    // Megkeressük, melyik oszlop(ok)hoz van rendelve a jobb kisujj ('P')
    val rightPinkyColIndex = layoutData.hardwareYaml.right.fingers.indexOfFirst { it.uppercase() == "P" }
    if (rightPinkyColIndex != -1) {
        // Kikeressük ennek a kisujjoszlopnak az anchor (horgony) oszlopát
        val rightPinkyAnchorCol = layoutData.hardwareYaml.right.anchors[rightPinkyColIndex]

        // A karakter az [X = homeRowIndex, Y = anchorCol] koordinátán lesz a jobb mátrixban
        if (layoutData.homeRowIndex in layoutData.rightChars.indices && rightPinkyAnchorCol in layoutData.rightChars[layoutData.homeRowIndex].indices) {
            val charAtAnchor = layoutData.rightChars[layoutData.homeRowIndex][rightPinkyAnchorCol]
            if (charAtAnchor != '_' && !charAtAnchor.isWhitespace()) {
                rightPinkyHomeChar = charAtAnchor.lowercaseChar()
            }
        }
    }

    return pinkyOffAnalysis(
        leftPinkyChars = leftPinkyChars,
        rightPinkyChars = rightPinkyChars,
        leftPinkyHomeChar = leftPinkyHomeChar,
        rightPinkyHomeChar = rightPinkyHomeChar,
        letters = corpusData.spaceincludedletters, // eredetileg így mértük, egyelőre marad
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun pinkyOffAnalysis(
    leftPinkyChars: Set<Char>,
    rightPinkyChars: Set<Char>,
    leftPinkyHomeChar: Char?,
    rightPinkyHomeChar: Char?,
    letters: Map<String, Double>,
    verbose: Boolean,
    layoutName: String,
    datasetName: String
): Double {
    if (verbose) println("Pinky Off (Kisujj Nyújtózás) elemzése folyamatban...")

    var totalPinkyOff = 0.0
    var leftPinkyOff = 0.0
    var rightPinkyOff = 0.0

    // 2. Gyakoriságok beolvasása és összegzése a memóriából
    for ((charStr, percentage) in letters) {
        // A szóköz eleve nincs a 'letters' térképben az új CorpusData miatt, de biztosítékként benne hagyjuk a checket
        if (charStr.isNotEmpty() && charStr != "[SPACE]") {
            val char = charStr[0]

            // Ha a betűt a BAL kisujj nyomja, DE az nem az alapgomb
            if (char in leftPinkyChars && char != leftPinkyHomeChar) {
                leftPinkyOff += percentage
                totalPinkyOff += percentage
            }
            // Ha a betűt a JOBB kisujj nyomja, DE az nem az alapgomb
            else if (char in rightPinkyChars && char != rightPinkyHomeChar) {
                rightPinkyOff += percentage
                totalPinkyOff += percentage
            }
        }
    }

    // 3. Eredmények kiírása és CSV mentés hívása
    if (verbose) {
        val resultDir = File("analysis_results/$datasetName/$layoutName")
        resultDir.mkdirs()
        val outputFile = File(resultDir, "pinky_off_analyzed.txt")

        outputFile.bufferedWriter().use { writer ->
            writer.write("--- PINKY OFF (Kizárólag nyújtózásos kisujj leütések) ---")
            writer.newLine()
            writer.write("Bal kisujj alapgomb: ${leftPinkyHomeChar ?: "Nincs"}")
            writer.newLine()
            writer.write("Jobb kisujj alapgomb: ${rightPinkyHomeChar ?: "Nincs"}")
            writer.newLine()
            writer.newLine()
            writer.write("Teljes Pinky Off arány: %.4f%%".format(totalPinkyOff))
            writer.newLine()
            writer.write("-> Bal kéz nyújtózás: %.4f%%".format(leftPinkyOff))
            writer.newLine()
            writer.write("-> Jobb kéz nyújtózás: %.4f%%".format(rightPinkyOff))
            writer.newLine()
        }

        saveRawMetric(datasetName, layoutName, "pinky_off", totalPinkyOff)
        println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Pinky Off: %.4f%%)".format(totalPinkyOff))
    }

    return totalPinkyOff
}