package keyboard_analysis

import java.io.File


/** A kézháton található inak anatómiája az ergonómiai billentyűzettervezés egyik legkeményebb fizikai korlátja, amelyet te magad is azonnal letesztelhetsz a saját kezeden.

Juncturae tendinum (Inak közötti kötések): A kézháton a közös ujjfeszítő izom (extensor digitorum communis) inai futnak a mutató-, középső-, gyűrűs- és kisujjhoz.
 A csukló és az ujjpercek között ezeket az inakat kötőszövetes hidak, a juncturae tendinum-ok kötik össze. Ez a fizikai összeköttetés leginkább a gyűrűs-, a középső- és a kisujj között erős.
  Ha a kisujjadat oldalra feszíted (abdukció) egy szélső gombért, ez a kötőszövetes híd mechanikailag meghúzza a gyűrűsujj inát is, kifordítva azt az ideális gépelési szögből.
  Bármilyen standard anatómiai tankönyv (pl. Gray's Anatomy, 42. kiadás, vagy Netter: Atlas of Human Anatomy)
   a kézhát (dorsum manus) anatómiájánál részletesen ábrázolja ezt a kötőszövetes hálózatot.

Az asztali teszt: Hajlítsd be a középső ujjadat, és tedd a kézfejed laposan egy asztalra (úgy, hogy a középső ujjad behajlított ujjperce az asztalhoz ér).
 Próbáld meg felemelni a hüvelyk-, mutató-, és kisujjadat. Könnyen menni fog. Most próbáld meg felemelni a gyűrűsujjadat. Szinte lehetetlen.
 A juncturae tendinum lehorgonyozza a gyűrűsujjadat a behajlított középső ujjhoz.
  Ugyanez az aszimmetrikus mechanikai feszültség lép fel a billentyűzeten a Pinky Stretch Bigramoknál.

Extensor indicis (A mutatóujj függetlensége):
 A mutatóujjnak a közös feszítőhálózaton túl van egy teljesen saját, független feszítőizma is, az extensor indicis proprius.
 Ez az oka annak, hogy a mutatóujjaddal teljesen függetlenül tudsz mutogatni anélkül, hogy a többi ujjad megmozdulna,
 és ezért bírja a mutatóujj nagyságrendekkel jobban a belső oszlopok felé történő oldalirányú nyújtózást ergonómiai büntetés nélkül.
 **/



fun launchPinkyStretchAnalysis(folder: String = "keyboards") {
    println("Kérem az elemzendő billentyűzetkiosztás nevét (a $folder mappából):")
    val layoutName = readln().trim()

    println("Kérem az adathalmaz nevét (pontosan úgy, ahogy a mappa neve szerepel, pl. 'szoveg.txt'):")
    val datasetName = readln().trim()

    runPinkyStretchAnalysis(layoutName, datasetName, folder)
}

fun runPinkyStretchAnalysis(layoutName: String, datasetName: String, folder: String = "keyboards"): Double? {
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

    return pinkyStretchAnalysis(
        charPositions = charToPos,
        bigrams = corpusData.bigrams,
        verbose = true,
        layoutName = layoutData.name,
        datasetName = corpusData.datasetName
    )
}

fun pinkyStretchAnalysis(
    charPositions: Map<Char, KeyPosition>,
    bigrams: Map<String, Double>,
    verbose: Boolean,
    layoutName: String = "",
    datasetName: String = ""
): Double {
    if (verbose) println("Pinky Stretch (Kisujj oldalirányú nyújtózás) elemzése folyamatban...")

    var totalPinkyStretch = 0.0

    for ((bigram, percentage) in bigrams) {
        if (bigram.length != 2) continue
        val p1 = charPositions[bigram[0]] ?: continue
        val p2 = charPositions[bigram[1]] ?: continue

        // Csak azonos kézen lévő bigramokat vizsgálunk
        if (p1.hand == p2.hand) {

            // Ujjindexek normalizálása (0: kisujj, 1: gyűrűs, 2: középső, 3: mutató, 4/5: hüvelyk)
            val f1 = if (p1.hand == 0) p1.finger else 9 - p1.finger
            val f2 = if (p2.hand == 0) p2.finger else 9 - p2.finger

            // Megnézzük, hogy a bigram tartalmaz-e kisujjat, ÉS mellé gyűrűs- vagy középső ujjat
            val hasPinky = f1 == 0 || f2 == 0
            val hasRingOrMiddle = f1 == 1 || f2 == 1 || f1 == 2 || f2 == 2

            if (hasPinky && hasRingOrMiddle) {
                // A két billentyű oszlop-távolsága
                val colDist = Math.abs(p1.col - p2.col)

                // Kisujj (0) és Gyűrűsujj (1) normál távolsága 1. Ha >= 2, akkor a kisujj stretch bigram.
                val isPinkyRingStretch = (f1 == 1 || f2 == 1) && colDist >= 2

                // Kisujj (0) és Középső ujj (2) normál távolsága 2. Ha >= 3, akkor a kisujj stretch bigram.
                val isPinkyMiddleStretch = (f1 == 2 || f2 == 2) && colDist >= 3

                // Ha bármelyik fájdalmas nyújtózás teljesül, büntetjük
                if (isPinkyRingStretch || isPinkyMiddleStretch) {
                    totalPinkyStretch += percentage
                }
            }
        }
    }

    // Fájlba és konzolra írás
    if (verbose) {
        if (layoutName.isNotEmpty() && datasetName.isNotEmpty()) {
            val resultDir = java.io.File("analysis_results/$datasetName/$layoutName")
            resultDir.mkdirs()
            val outputFile = java.io.File(resultDir, "pinky_stretch_analyzed.txt")

            outputFile.bufferedWriter().use { writer ->
                writer.write("Teljes Pinky Stretch: %.4f%%".format(totalPinkyStretch))
                writer.newLine()
            }
            saveRawMetric(datasetName, layoutName, "pinky_stretch_total", totalPinkyStretch)
            println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Pinky Stretch: %.4f%%)".format(totalPinkyStretch))
        } else {
            println("Pinky Stretch Bigrams: ${String.format("%.3f", totalPinkyStretch)}%")
        }
    }

    return totalPinkyStretch
}