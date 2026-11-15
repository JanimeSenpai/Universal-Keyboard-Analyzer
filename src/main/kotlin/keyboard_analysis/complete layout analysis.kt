package keyboard_analysis

import KeyboardLayoutGeneration.calculateTotalEffort
import java.io.File

data class LayoutAnalysisResult(
    val layoutName: String,
    val totalEffort: Double,
    val charReachEffort: Double,//based on effort layout, keyboard layout and letter frequency in corpus
    val rowPercentages: DoubleArray,
    val homeRowPercentage: Double,
    val handBalanceLeft: Double,
    val handBalanceRight: Double,
    val pinkyOffPercentage: Double,
    val sfbPercentage: Double,
    val alternationPercentage: Double,
    val rollPercentage: Double, // 2-1 és 1-2 együtt
    val threerollPercentage: Double,
    val redirectPercentage: Double,
    val spaceSfs1Penalty: Double,
    val lateralStretchPercentage: Double,
    val pinkystretchPercentage: Double,
    val fullScissorPercentage: Double,
    val baseGravityEffort: Double, //láthatóvá tesszük a ritka karakterek mozgását a billentyűzet szélén
    val halfScissorPercentage: Double
)

// 1. KONZOLOS INDÍTÓ FÜGGVÉNY (Egyszeri futás)
fun launchCompleteLayoutAnalysis(folder: String = "keyboards") {
    println("--- TELJES BILLENTYŰZET ELEMZÉS ---")

    // 1. Billentyűzet bekérése (Már az új YAML rendszert használjuk)
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    val layoutFile = LayoutParser.resolveLayoutFile(layoutName, folder)
    if (layoutFile == null || !layoutFile.exists()) {
        println("Hiba: A billentyűzet fájl nem található a '$folder' mappában ($layoutName).")
        return
    }

    // 2. Adathalmaz bekérése
    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    // 3. Adatok beolvasása a memóriába
    val layoutData = try {
        LayoutParser.parseLayout(layoutFile)
    } catch (e: Exception) {
        println("Hiba a billentyűzet beolvasásakor: ${e.message}")
        return
    }

    val corpusData = CorpusData.loadFromDataset(datasetName)

    println("\n==================================================")
    println("ELEMZÉS INDÍTÁSA: ${layoutData.name} (Adathalmaz: $datasetName, Mappa: $folder)")
    println("==================================================")

    // 4. Elemző motor meghívása beszédes (verbose) módban
    val result = completeLayoutAnalysis(layoutData, corpusData, verbose = true)

    println("==================================================")
    println("MINDEN ELEMZÉS SIKERESEN BEFEJEZŐDÖTT!")
    println("Összesített Effort: ${String.format("%.4f", result.totalEffort)}")
    println("==================================================")
}

/**
 * Billentyűzet-elemző függvény, amely paraméterként kapja meg, hogy a "keyboards"
 * vagy a "generatedkeyboards" mappában keresse meg az elemzendő kiosztást.
 */
fun completeLayoutAnalysis(
    layoutName: String,
    datasetName: String,
    folder: String = "keyboards",
    verbose: Boolean = true
): LayoutAnalysisResult? {
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
    return completeLayoutAnalysis(layoutData, corpusData, verbose)
}

// 2. MAGFÜGGVÉNY / MOTOR (A Simulated Annealing és a launch hívja)
fun completeLayoutAnalysis(
    layoutData: CombinedLayoutData,
    corpusData: CorpusData,
    verbose: Boolean,
): LayoutAnalysisResult {

    if (verbose) {
        println("--------------------------------------------------")
        println("1. Effort (Fizikai Terhelés) számítása...")
    }
    val charEffortMap = layoutData.buildCharEffortMap()
    val charReachEffort = calculateWeightedReachEffortSum(charEffortMap, corpusData, verbose)

    if (verbose){
        //hogyha egy darab layoutot elemzünk, akkor ki fogjuk írni a végeredményt fájlba, és először töröljük a fájlban levő adatokat, hogy appendeljük az új információt
        clearRawMetrics(corpusData.datasetName, layoutData.name)
    }


    if (verbose) println("--------------------------------------------------")
    val rows = layoutData.getRows()
    val rowPercentages = rowUsageAnalysis(rows, layoutData.homeRowIndex, corpusData.letters,corpusData.spaceincludedletters, verbose, layoutData.name, corpusData.datasetName)
    val homeRowPercentage = if (layoutData.homeRowIndex in rowPercentages.indices) rowPercentages[layoutData.homeRowIndex] else 0.0


    if (verbose) println("--------------------------------------------------")
    val (handBalanceLeft, handBalanceRight) = handBalanceAnalysis(
        leftChars = layoutData.leftChars,
        rightChars = layoutData.rightChars,
        letters = corpusData.letters, // <-- Ezt be kell kötni
        spaceincludedletters = corpusData.spaceincludedletters,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

    if (verbose) println("--------------------------------------------------")
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

    // A magfüggvény hívása (ez teljesen változatlan!)
    val pinkyOffPercentage = pinkyOffAnalysis(
        leftPinkyChars = leftPinkyChars,
        rightPinkyChars = rightPinkyChars,
        leftPinkyHomeChar = leftPinkyHomeChar,
        rightPinkyHomeChar = rightPinkyHomeChar,
        letters = corpusData.spaceincludedletters, // eredetileg így mértük, egyelőre marad
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
    if (verbose) println("--------------------------------------------------")

    // 10 ujjra kiterjesztett Karakter -> Ujj térkép
    val charToFingerMap = mutableMapOf<Char, Int>()
    // Kiegészítve L_T és R_T értékekkel középen
    val fingerKeys = listOf("L_P", "L_R", "L_M", "L_I", "L_T", "R_T", "R_I", "R_M", "R_R", "R_P")

    for ((index, key) in fingerKeys.withIndex()) {
        fingerSets[key]?.forEach { char -> charToFingerMap[char] = index }
    }

    val sfbPercentage = simpleSfbAnalysis(
        charToFinger = charToFingerMap,
        bigrams = corpusData.bigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

    if (verbose) println("--------------------------------------------------")

    // 1. Kéz-hozzárendelés feltérképezése (Karakter -> Kéz: 0 = Bal, 1 = Jobb)
    // Ez automatikusan támogatja a hüvelykujjakat (Thumb clusterek) is!
    val charToHandMap = mutableMapOf<Char, Int>()
    for ((key, chars) in fingerSets) {
        val hand = if (key.startsWith("L")) 0 else 1
        chars.forEach { char -> charToHandMap[char] = hand }
    }

    val alternationPercentage = alternationAnalysis(
        charToHand = charToHandMap,
        trigrams = corpusData.trigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

    if (verbose) println("--------------------------------------------------")

    // 1. Kéz- és ujj-hozzárendelés feltérképezése a Roll elemzéshez
    // A szótár értéke egy Pair lesz: (Kéz indexe, Ujj indexe)
    val charToHandAndFingerMap = mutableMapOf<Char, Pair<Int, Int>>()
    val fingerKeysForRoll = listOf("L_P", "L_R", "L_M", "L_I", "L_T", "R_T", "R_I", "R_M", "R_R", "R_P")

    for ((index, key) in fingerKeysForRoll.withIndex()) {
        val hand = if (key.startsWith("L")) 0 else 1 // 0 = Bal, 1 = Jobb
        fingerSets[key]?.forEach { char ->
            charToHandAndFingerMap[char] = Pair(hand, index)
        }
    }

    val rollPercentage = rollAnalysis(
        charMap = charToHandAndFingerMap,
        trigrams = corpusData.trigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

    // A 3-roll elemzést is lefuttathatjuk, de egyelőre nem rakjuk be a kötelező eredmény objektumba
    val threeRollPercentage = threeRollAnalysis(
        charMap = charToHandAndFingerMap,
        trigrams = corpusData.trigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

    if (verbose) println("--------------------------------------------------")
    val redirectPercentage = redirectAnalysis(
        charMap = charToHandAndFingerMap, // Ugyanaz a térkép, amit a rollAnalysis is használt!
        trigrams = corpusData.trigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

    if (verbose) println("--------------------------------------------------")
    val spaceSfs1Penalty = spaceSfs1Analysis(layoutData, corpusData, verbose)

    val charToPos = buildCharPositionMap(layoutData)

    val lateralStretchPercentage = lateralStretchAnalysis(
        charPositions = charToPos,
        bigrams = corpusData.bigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

    val pinkyStretchPercentage = pinkyStretchAnalysis(
        charPositions = charToPos,
        bigrams = corpusData.bigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName)

    val fullScissorPercentage = fullScissorAnalysis(
        charPositions = charToPos,
        bigrams = corpusData.bigrams,
        verbose = verbose,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )

val halfScissorPercentage = halfScissorAnalysis(
    charPositions = charToPos,
    bigrams = corpusData.bigrams,
    verbose = verbose,
    layoutName = layoutData.name,
    datasetName = corpusData.datasetName
)

    val baseGravityEffort = charEffortMap.values.sum()

// Előzetes eredményobjektum összeállítása
    val preliminaryResult = LayoutAnalysisResult(
        layoutName = layoutData.name,
        totalEffort = 0.0,
        charReachEffort = charReachEffort,
        rowPercentages = rowPercentages,
        homeRowPercentage = homeRowPercentage,
        handBalanceLeft = handBalanceLeft,
        handBalanceRight = handBalanceRight,
        pinkyOffPercentage = pinkyOffPercentage,
        sfbPercentage = sfbPercentage,
        alternationPercentage = alternationPercentage,
        rollPercentage = rollPercentage,
        threerollPercentage = threeRollPercentage,
        redirectPercentage = redirectPercentage,
        spaceSfs1Penalty = spaceSfs1Penalty,
        lateralStretchPercentage = lateralStretchPercentage,
        pinkystretchPercentage =pinkyStretchPercentage ,
        fullScissorPercentage = fullScissorPercentage,
        halfScissorPercentage = halfScissorPercentage,
        baseGravityEffort = baseGravityEffort
    )

    val totalEffort = calculateTotalEffort(preliminaryResult)
    return preliminaryResult.copy(totalEffort = totalEffort)
}

