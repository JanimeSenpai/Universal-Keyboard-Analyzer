package keyboard_analysis

/*
* keyboard layouts are located in keyboards_simple folder
* such as qwerty.txt
* each line of characters corresponds to a finger
* the order of line goes as the following: left pinky, left ring, left middle, left index, right index, right middle, right ring, right pinky
*
* kérdezd meg a usert, hogy mi az elemzendő keyboardlayout neve
* A névből képezd a file elérési útvonalát: keyboards_simple\NÉV.txt
* a .txt végződést adjuk hozzá
*
* azután kérdezzük meg, hogy mely adathalmaz alapján végezzük az elemzést
* nyissuk meg az adathalmazt, melynek elérési útja: analysisresults\adathalmazneve.txt\bigramfrequency.txt
* itt az adathalmazneve.txt egyben kapott paraméter, mert mappa elérési útját másolja ki a felhasználó a mappakezelővel
*
* ezután kezdődik a tényleges elemzés
* olvassuk be soronként a billyentyűzetet
* képezd az egyes betűkből az ujjakra eső összes lehetséges bigramot.
*
* javasolt adatstruktúra: egy nyolcelemű lista, aminek az egyes elemei az egyes ujjakat reprezentálják
* az egyes elemekben az egyes betűpárok
* ezután menjünk végig minden egyes ujj minden egyes betűpárján, és keressük meg, hogy hol van a bigramfrequency.txt fájlban.
* a hozzá tartozó bigram százalékot adjuk hozzá az ujjhoz tartozó bigramszázalékhoz
*
* A végén készítsünk egy output filet: billentyűzetneve_sfb_analyzed.txt
* első sor: teljes sfb%
* következő sorok: egyes ujjakhoz tartozó sfb%-ok
*
*
*
* */
import java.io.File

// 1. KONZOLOS INDÍTÓ FÜGGVÉNY (Egyszeri futás, betölti a fájlokat memóriába)
fun launchSimpleSfbAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pl. aminek a mappája az analysis_results-ban van):")
    val datasetName = readln().trim()

    runSimpleSfbAnalysis(layoutName, datasetName, folder)
}

fun runSimpleSfbAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
    val layoutFile = LayoutParser.resolveLayoutFile(layoutName, folder)
    if (layoutFile == null || !layoutFile.exists()) {
        println("Hiba: A billentyűzet fájl nem található a '$folder' mappában ($layoutName).")
        return null
    }

    // Új architektúra: a teljes parseren és memóriastruktúrán keresztül futtatjuk
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

    // Előállítjuk a paramétereket az elemzőhöz
    val charToFingerMap = mutableMapOf<Char, Int>()
    val fingerSets = layoutData.getFingerCharacterSets()
    val fingerKeys = listOf("L_P", "L_R", "L_M", "L_I", "L_T", "R_T", "R_I", "R_M", "R_R", "R_P")
    for ((index, key) in fingerKeys.withIndex()) {
        fingerSets[key]?.forEach { char -> charToFingerMap[char] = index }
    }

    // Futtatjuk az elemzést (a launch verbose módja mindig true)
    return simpleSfbAnalysis(
        charToFinger = charToFingerMap,
        bigrams = corpusData.bigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun simpleSfbAnalysis(
    charToFinger: Map<Char, Int>,
    bigrams: Map<String, Double>,
    verbose: Boolean,
    layoutName: String,
    datasetName: String
): Double {
    if (verbose) println("Billentyűzet elemzése folyamatban (SFB és SKS szétválasztásával 10 ujjra)...")

    // Immár 10-es méretű tömbök a hüvelykujjak miatt
    val fingerSfbs = DoubleArray(10) { 0.0 }
    val fingerSks = DoubleArray(10) { 0.0 }
    var totalSfb = 0.0
    var totalSks = 0.0

    for ((bigram, percentage) in bigrams) {
        if (bigram.length == 2) {
            val finger1 = charToFinger[bigram[0]]
            val finger2 = charToFinger[bigram[1]]

                    // Ha mindkét betű rajta van a billentyűzeten, ÉS ugyanaz az ujj nyomja őket
                    if (finger1 != null && finger1 == finger2) {

                        // --- ÚJ RÉSZ: SKS és SFB szétválasztása ---
                        if (bigram[0] == bigram[1]) {
                            // SKS: Ugyanaz a betű egymás után (pl. "tt", "ll")
                            fingerSks[finger1] += percentage
                            totalSks += percentage
                        } else {
                            // Valódi SFB: Két különböző betű ugyanazzal az ujjal (pl. "ed", "rt")
                            fingerSfbs[finger1] += percentage
                            totalSfb += percentage
                        }
                    }
                }
            }


    if (verbose) {
        val resultDir = File("analysis_results/$datasetName/$layoutName")
        resultDir.mkdirs()
        val outputFile = File(resultDir, "sfb_analyzed.txt")

        // Kiegészítve a hüvelykujjak neveivel
        val fingerNames = listOf(
            "Left Pinky", "Left Ring", "Left Middle", "Left Index", "Left Thumb",
            "Right Thumb", "Right Index", "Right Middle", "Right Ring", "Right Pinky"
        )

    outputFile.bufferedWriter().use { writer ->
        // Összesített eredmények a fájl tetején
        writer.write("Teljes SFB (Valódi, külön gombos): %.4f%%".format(totalSfb))
        writer.newLine()
        writer.write("Teljes SKB (Azonos gomb duplázása): %.4f%%".format(totalSks))
        writer.newLine()
        writer.newLine()

        // Valódi SFB ujjankénti lebontás
            writer.write("--- UJJANKÉNTI SFB ---")
            writer.newLine()
            for (i in 0 until 10) {
                writer.write("${fingerNames[i]}: %.4f%%".format(fingerSfbs[i]))
                writer.newLine()
            }

            writer.newLine()

        // SKS ujjankénti lebontás (Informatív, hogy lásd, melyik ujjat terhelik a duplázások)
        writer.write("--- UJJANKÉNTI SKB (same key bigram) ---")
            writer.newLine()
            for (i in 0 until 10) {
                writer.write("${fingerNames[i]}: %.4f%%".format(fingerSks[i]))
                writer.newLine()
            }
        }

        saveRawMetric(datasetName, layoutName, "sfb_total", totalSfb)
        println("Sikeresen végrehajtva: ${outputFile.path} létrehozva.")
    }

    return totalSfb
}