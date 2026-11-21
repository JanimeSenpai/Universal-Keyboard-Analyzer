import EffortModel.ColemakDhEffortGenerator
import EffortModel.ColumnConfig
import EffortModel.CustomKeyboardEffortGenerator
import EffortModel.EffortHeatmapRenderer
import EffortModel.Finger
import EffortModel.Hand
import EffortModel.KeyboardHalf
import EffortModel.StaggerType
import KeyboardLayoutGeneration.renderCharEffortlayout
import keyboard_analysis.CombinedLayoutData
import keyboard_analysis.LayoutParser
import java.io.File

//The purpose of these functions is to test the Effort layout generation and rendering of effort heatmaps.
//physical hardware is stored along the layouts so in general use these functions are not going to be used
fun main() {
    generalRenderingTest("blankcolstagger.yaml")
    generalRenderingTest("keebFourBlank.yaml")
    generalRenderingTest("standardkeyboard.yaml")
}

fun generalRenderingTest(layoutFileName: String) {
    val layoutFile = LayoutParser.resolveLayoutFile(layoutFileName, "keyboards")
    if (layoutFile == null || !layoutFile.exists()) {
        println("Hiba: A layout fájl nem található: $layoutFileName")
        return
    }

    val layoutData = LayoutParser.parseLayout(layoutFile)
    val outputDir = File("effortgrids").apply { mkdirs() }
    val outputPath = File(outputDir, "${layoutFile.nameWithoutExtension}_heatmap.png").path

    renderCharEffortlayout(layoutData, imageOutputPath = outputPath)
    println("Elmentve ide: $outputPath")
}
fun standardKeyboardTest(){
    // Standard ISO/ANSI sor-eltolások az alapsorhoz (2. index) képest:

    // Num (-0.75u), Top (-0.25u), Home (0.0u), Bottom (+0.5u)
    val standardRowOffsets = listOf(-0.75, -0.25, 0.0, 0.5)
    val leftHalf = KeyboardHalf(Hand.LEFT, StaggerType.ROW_STAGGER, 4, 6, standardRowOffsets)
    val leftConfigs = listOf(
        ColumnConfig(0, anchorColIndex = 1, Finger.PINKY),  // Külső kisujj oszlop (Tab / CapsLock sáv)
        ColumnConfig(1, anchorColIndex = 1, Finger.PINKY),  // Kisujj (Q, A, Z)
        ColumnConfig(2, anchorColIndex = 2, Finger.RING),   // Gyűrűs (W, S, X)
        ColumnConfig(3, anchorColIndex = 3, Finger.MIDDLE), // Középső (E, D, C)
        ColumnConfig(4, anchorColIndex = 4, Finger.INDEX),  // Mutató (R, F, V)
        ColumnConfig(5, anchorColIndex = 4, Finger.INDEX)   // Belső mutató (T, G, B)
    )

    val rightHalf = KeyboardHalf(Hand.RIGHT, StaggerType.ROW_STAGGER, 4, 6, standardRowOffsets)
    val rightConfigs = listOf(
        ColumnConfig(0, anchorColIndex = 1, Finger.INDEX),  // Belső mutató (Y, H, N)
        ColumnConfig(1, anchorColIndex = 1, Finger.INDEX),  // Mutató (U, J, M)
        ColumnConfig(2, anchorColIndex = 2, Finger.MIDDLE), // Középső (I, K, ,)
        ColumnConfig(3, anchorColIndex = 3, Finger.RING),   // Gyűrűs (O, L, .)
        ColumnConfig(4, anchorColIndex = 4, Finger.PINKY),  // Kisujj (P, ;, /)
        ColumnConfig(5, anchorColIndex = 4, Finger.PINKY)   // Külső kisujj oszlop ([ / ' / RShift sáv)
    )

    // 1. Kép: Hagyományos ujjrend (a PDF 8. oldalának alsó ábrája)
    val leftTrad = ColemakDhEffortGenerator(leftHalf, leftConfigs, useOptimalFingerAssignment = false).generateEffortMatrix()
    val rightTrad = ColemakDhEffortGenerator(rightHalf, rightConfigs, useOptimalFingerAssignment = false).generateEffortMatrix()
    EffortHeatmapRenderer.renderFullKeyboard(leftHalf, rightHalf, leftTrad, rightTrad,leftConfigs,rightConfigs, outputPath = "standard_traditional_heatmap.png")

    // 2. Kép: Optimális ujjrend (a PDF 8. oldalának felső ábrája)
    val leftOpt = ColemakDhEffortGenerator(leftHalf, leftConfigs, useOptimalFingerAssignment = true).generateEffortMatrix()
    val rightOpt = ColemakDhEffortGenerator(rightHalf, rightConfigs, useOptimalFingerAssignment = true).generateEffortMatrix()
    EffortHeatmapRenderer.renderFullKeyboard(leftHalf, rightHalf, leftOpt, rightOpt,leftConfigs,rightConfigs, outputPath = "standard_optimal_heatmap.png")

    println("Képek sikeresen legenerálva: standard_traditional_heatmap.png és standard_optimal_heatmap.png")
}

fun testMySymmetricalRowstaggerKeyboard(){

    // szimmetrikus eltolások a fizikai megvalósításnak megfelelően

    // Num (-0.5u), Top (-0.25u), Home (0.0u), Bottom (+0.5u)
    val offsetsleft = listOf(-0.25, -0.25, 0.0, -0.5)
    val offsetsright = listOf(0.25, 0.25, 0.0, 0.5)
    val leftHalf = KeyboardHalf(Hand.LEFT, StaggerType.ROW_STAGGER, 4, 8, offsetsleft)
    val leftConfigs = listOf(
        ColumnConfig(colIndex = 0, anchorColIndex = 2, finger = Finger.PINKY),  // Külső extra 2
        ColumnConfig(colIndex = 1, anchorColIndex = 2, finger = Finger.PINKY),  // Külső extra 1
        ColumnConfig(colIndex = 2, anchorColIndex = 2, finger = Finger.PINKY),  // Kisujj alapsor (anchor = 2)
        ColumnConfig(colIndex = 3, anchorColIndex = 3, finger = Finger.RING),   // Gyűrűs alapsor (anchor = 3)
        ColumnConfig(colIndex = 4, anchorColIndex = 4, finger = Finger.MIDDLE), // Középső alapsor (anchor = 4)
        ColumnConfig(colIndex = 5, anchorColIndex = 5, finger = Finger.INDEX),  // MUTATÓ ALAPSOR -> ITT LESZ AZ 1.0 (anchor = 5)
        ColumnConfig(colIndex = 6, anchorColIndex = 5, finger = Finger.INDEX),  // Belső mutató 1 (a mutatóujj horgonytól mérve)
        ColumnConfig(colIndex = 7, anchorColIndex = 5, finger = Finger.INDEX)   // Belső mutató 2
    )

    val rightHalf = KeyboardHalf(Hand.RIGHT, StaggerType.ROW_STAGGER, 4, 8, offsetsright)
    val rightConfigs = listOf(
        ColumnConfig(colIndex = 0, anchorColIndex = 2, finger = Finger.INDEX),  // Belső mutató 2 (a mutatóujj horgonytól mérve)
        ColumnConfig(colIndex = 1, anchorColIndex = 2, finger = Finger.INDEX),  // Belső mutató 1
        ColumnConfig(colIndex = 2, anchorColIndex = 2, finger = Finger.INDEX),  // MUTATÓ ALAPSOR -> ITT LESZ AZ 1.0 (anchor = 2)
        ColumnConfig(colIndex = 3, anchorColIndex = 3, finger = Finger.MIDDLE), // Középső alapsor (anchor = 3)
        ColumnConfig(colIndex = 4, anchorColIndex = 4, finger = Finger.RING),   // Gyűrűs alapsor (anchor = 4)
        ColumnConfig(colIndex = 5, anchorColIndex = 5, finger = Finger.PINKY),  // Kisujj alapsor (anchor = 5)
        ColumnConfig(colIndex = 6, anchorColIndex = 5, finger = Finger.PINKY),  // Külső extra 1
        ColumnConfig(colIndex = 7, anchorColIndex = 5, finger = Finger.PINKY)   // Külső extra 2
    )



   // Kép: Optimális ujjrend (a PDF 8. oldalának felső ábrája)
    val leftOpt = ColemakDhEffortGenerator(leftHalf, leftConfigs, useOptimalFingerAssignment = true).generateEffortMatrix()
    val rightOpt = ColemakDhEffortGenerator(rightHalf, rightConfigs, useOptimalFingerAssignment = true).generateEffortMatrix()
    EffortHeatmapRenderer.renderFullKeyboard(leftHalf, rightHalf, leftOpt, rightOpt, leftConfigs,rightConfigs, outputPath = "MySymmetricalRowstaggerKeyboard_optimal_heatmap.png")

    println("Kép sikeresen legenerálva: MySymmetricalRowstaggerKeyboard_optimal_heatmap.png")


}

fun testblankcolstagger() {
    println("--- EGYEDI COL-STAGGER HŐTÉRKÉP GENERÁLÁSA YAML ALAPJÁN ---")

    // 1. Használjuk a már létező, szigorúan validált YAML parsert!
    // Feltételezzük, hogy a keyboards mappában ott a fájl.
    val layoutFile = File("keyboards/blankcolstagger.yaml")
    if (!layoutFile.exists()) {
        println("Hiba: A ${layoutFile.path} nem található!")
        return
    }

    val layoutData = try {
        LayoutParser.parseLayout(layoutFile)
    } catch (e: Exception) {
        println("Beolvasási hiba: ${e.message}")
        return
    }

    // 2. Kinyerjük a KeyboardHalf és ColumnConfig adatokat a LayoutParserből
    // Mivel a LayoutParser belsőleg már létrehozza ezeket, de a CombinedLayoutData nem
    // tárolja a KeyboardHalf és ColumnConfig objektumokat (csak az Effort mátrixokat és a karaktereket),
    // a hőtérképhez nekünk itt a tesztben egy pillanatra újra fel kell építenünk ezt a két apróságot,
    // vagy egyből a CombinedLayoutData adataiból táplálkozunk.

    val hw = layoutData.hardwareYaml
    val stagger = when (hw.staggerType.lowercase()) {
        "columnar_stagger" -> StaggerType.COLUMNAR_STAGGER
        "row_stagger" -> StaggerType.ROW_STAGGER
        else -> StaggerType.MATRIX
    }

    val leftHalf = KeyboardHalf(Hand.LEFT, stagger, hw.numRows, hw.left.fingers.size, hw.left.offsets, hw.homeRowIndex)
    val rightHalf = KeyboardHalf(Hand.RIGHT, stagger, hw.numRows, hw.right.fingers.size, hw.right.offsets, hw.homeRowIndex)

    val leftConfigs = hw.left.fingers.indices.map { i ->
        ColumnConfig(
            colIndex = i,
            anchorColIndex = hw.left.anchors[i],
            finger = Finger.valueOf(when (hw.left.fingers[i].uppercase()) {
                "P" -> "PINKY"
                "R" -> "RING"
                "M" -> "MIDDLE"
                "I" -> "INDEX"
                else -> "THUMB"
            })
        )
    }

    val rightConfigs = hw.right.fingers.indices.map { i ->
        ColumnConfig(
            colIndex = i,
            anchorColIndex = hw.right.anchors[i],
            finger = Finger.valueOf(when (hw.right.fingers[i].uppercase()) {
                "P" -> "PINKY"
                "R" -> "RING"
                "M" -> "MIDDLE"
                "I" -> "INDEX"
                else -> "THUMB"
            })
        )
    }

    // 3. A renderer meghívása az opcionális karaktertömbök átadásával
    EffortHeatmapRenderer.renderFullKeyboard(
        leftHalf = leftHalf,
        rightHalf = rightHalf,
        leftEffort = layoutData.leftEffortMatrix.map { it.toList() }, // Array<DoubleArray> -> List<List<Double>>
        rightEffort = layoutData.rightEffortMatrix.map { it.toList() },
        leftcolumnConfig = leftConfigs,
        rightcolumnConfig = rightConfigs,
        leftChars = layoutData.leftChars,   // Átadjuk a tényleges betűket is!
        rightChars = layoutData.rightChars,
        outputPath = "blankcolstagger_heatmap.png"
    )

    println("A saját billentyűzet hőtérképe legenerálva: blankcolstagger")
}

fun testMyCustomColStaggerKeyboard() {
    // A KLE fájlodból kinyert pontos oszlop-eltolások (a középső ujj 0.00u szintjéhez képest):
    // Bal oldal 8 oszlopa:
    // Col 0 (Caps/Tab/Bksp): +0.50u
    // Col 1 (Ö / Ú / C):     +0.50u
    // Col 2 (I / Ó / Ü):     +0.50u
    // Col 3 (O / U / .):     +0.25u (Gyűrűs)
    // Col 4 (E / Á / ,):      0.00u (Középső)
    // Col 5 (A / Y / É):     +0.25u (Mutató - homing)
    // Col 6 (Ő / Í / Ű):     +0.38u (Belső mutató 1)
    // Col 7 (Alt / Esc / C):  0.10u (Belső mutató 2)
    val leftColOffsets = listOf(0.50, 0.50, 0.50, 0.25, 0.00, 0.25, 0.38, 0.10)

    // Jobb oldal 8 oszlopa:
    // Col 0 (; / *):         +0.10u (Belső mutató 2)
    // Col 1 (B / F / V):     +0.38u (Belső mutató 1)
    // Col 2 (L / Z / R):     +0.25u (Mutató - homing)
    // Col 3 (T / K / M):      0.00u (Középső)
    // Col 4 (S / D / G):     +0.25u (Gyűrűs)
    // Col 5 (N / H / J):     +0.50u (Kisujj alap)
    // Col 6 (P / Q / -):     +0.50u (Kisujj külső 1)
    // Col 7 (- / layer):     +0.50u (Kisujj külső 2)
    val rightColOffsets = listOf(0.10, 0.38, 0.25, 0.00, 0.25, 0.50, 0.50, 0.50)

    // 5 soros modell (0: F-sor, 1: Számsor, 2: Felső sor, 3: Alapsor [homeRowIndex = 3], 4: Alsó sor)
    val leftHalf = KeyboardHalf(
        hand = Hand.LEFT,
        staggerType = StaggerType.COLUMNAR_STAGGER,
        numRows = 5,
        numColumns = 8,
        offsets = leftColOffsets,
        homeRowIndex = 3
    )

    val leftConfigs = listOf(
        ColumnConfig(0, anchorColIndex = 2, Finger.PINKY),  // Külső kisujj 2 (CapsLock)
        ColumnConfig(1, anchorColIndex = 2, Finger.PINKY),  // Külső kisujj 1 (Ö)
        ColumnConfig(2, anchorColIndex = 2, Finger.PINKY),  // Kisujj alap (I) -> Ha az Ö az alap, írd át anchorColIndex = 1-re!
        ColumnConfig(3, anchorColIndex = 3, Finger.RING),   // Gyűrűs (O)
        ColumnConfig(4, anchorColIndex = 4, Finger.MIDDLE), // Középső (E)
        ColumnConfig(5, anchorColIndex = 5, Finger.INDEX),  // Mutató (A - homing)
        ColumnConfig(6, anchorColIndex = 5, Finger.INDEX),  // Belső mutató 1 (Ő)
        ColumnConfig(7, anchorColIndex = 5, Finger.INDEX)   // Belső mutató 2 (Alt/C)
    )

    val rightHalf = KeyboardHalf(
        hand = Hand.RIGHT,
        staggerType = StaggerType.COLUMNAR_STAGGER,
        numRows = 5,
        numColumns = 8,
        offsets = rightColOffsets,
        homeRowIndex = 3
    )

    val rightConfigs = listOf(
        ColumnConfig(0, anchorColIndex = 2, Finger.INDEX),  // Belső mutató 2 (;)
        ColumnConfig(1, anchorColIndex = 2, Finger.INDEX),  // Belső mutató 1 (B)
        ColumnConfig(2, anchorColIndex = 2, Finger.INDEX),  // Mutató (L - homing)
        ColumnConfig(3, anchorColIndex = 3, Finger.MIDDLE), // Középső (T)
        ColumnConfig(4, anchorColIndex = 4, Finger.RING),   // Gyűrűs (S)
        ColumnConfig(5, anchorColIndex = 5, Finger.PINKY),  // Kisujj alap (N)
        ColumnConfig(6, anchorColIndex = 5, Finger.PINKY),  // Külső kisujj 1 (P)
        ColumnConfig(7, anchorColIndex = 5, Finger.PINKY)   // Külső kisujj 2 (-)
    )

    val leftEffort = CustomKeyboardEffortGenerator(leftHalf, leftConfigs, useOptimalFingerAssignment = true).generateEffortMatrix()
    val rightEffort = CustomKeyboardEffortGenerator(rightHalf, rightConfigs, useOptimalFingerAssignment = true).generateEffortMatrix()

    EffortHeatmapRenderer.renderFullKeyboard(
        leftHalf = leftHalf,
        rightHalf = rightHalf,
        leftEffort = leftEffort,
        rightEffort = rightEffort,
        leftConfigs,
        rightConfigs,
        outputPath = "my_colstagger_heatmap.png"
    )
    println("A saját billentyűzet hőtérképe legenerálva: my_colstagger_heatmap.png")
}



// Segédfüggvény a teszthez a string -> Finger konverzióra
 fun parseFingerForTest(code: String): Finger = when (code.uppercase()) {
    "P" -> Finger.PINKY
    "R" -> Finger.RING
    "M" -> Finger.MIDDLE
    "I" -> Finger.INDEX
    "T" -> Finger.THUMB
    else -> throw IllegalArgumentException("Ismeretlen ujj kód: $code")
}


fun testLtsnErgoHeatmapRendering() {
    val layoutName = "ltsnergorowstagger.yaml"
    val layoutFile = File("keyboards/$layoutName")

    assert(layoutFile.exists()) { "Hiba: A fájl nem található: ${layoutFile.absolutePath}" }

    // 1. Layout betöltése az új, thumb-key barát parserünkkel
    val layoutData = LayoutParser.parseLayout(layoutFile)
    val hw = layoutData.hardwareYaml

    // 2. Szükséges konfigurációk visszakonvertálása a renderelő által várt típusokra
    val stagger = when (hw.staggerType.lowercase()) {
        "columnar_stagger" -> StaggerType.COLUMNAR_STAGGER
        "row_stagger" -> StaggerType.ROW_STAGGER
        else -> StaggerType.MATRIX
    }

// Figyelem: A tesztben is az ujjak száma (fingers.size) kell, hogy adja az oszlopokat!
    val leftHalf = KeyboardHalf(Hand.LEFT, stagger, hw.numRows, hw.left.fingers.size, hw.left.offsets, hw.homeRowIndex)
    val rightHalf = KeyboardHalf(Hand.RIGHT, stagger, hw.numRows, hw.right.fingers.size, hw.right.offsets, hw.homeRowIndex)

    val leftConfigs = hw.left.fingers.indices.map { i ->
        ColumnConfig(colIndex = i, anchorColIndex = hw.left.anchors[i], finger = parseFingerForTest(hw.left.fingers[i]))
    }
    val rightConfigs = hw.right.fingers.indices.map { i ->
        ColumnConfig(colIndex = i, anchorColIndex = hw.right.anchors[i], finger = parseFingerForTest(hw.right.fingers[i]))
    }

    // 3. Adapter a Rongyos Tömbhöz (Padding a renderelő miatt)
    val cols = leftHalf.numColumns // Pl. 8

    // A bal hüvelykujj fizikai oszlopát keressük (hova rajzolja a renderelő a gombot)
    val leftThumbCol = hw.left.fingers.indexOfFirst { it.equals("T", true) }.takeIf { it >= 0 } ?: (cols - 1)
    val rightThumbCol = hw.right.fingers.indexOfFirst { it.equals("T", true) }.takeIf { it >= 0 } ?: 0

    val paddedLeftEffort = layoutData.leftEffortMatrix.mapIndexed { r, row ->
        if (r == hw.numRows - 1) { // Ha a hüvelykujj sora
            // row[0] helyett row[leftThumbCol]-t kérünk!
            List(cols) { c -> if (c == leftThumbCol) row[leftThumbCol] else 99.9 }
        } else {
            row.toList()
        }
    }

    val paddedRightEffort = layoutData.rightEffortMatrix.mapIndexed { r, row ->
        if (r == hw.numRows - 1) {
            // row[0] helyett row[rightThumbCol]-t kérünk!
            List(cols) { c -> if (c == rightThumbCol) row[rightThumbCol] else 99.9 }
        } else {
            row.toList()
        }
    }

    val paddedLeftChars = layoutData.leftChars.mapIndexed { r, row ->
        if (r == hw.numRows - 1) {
            CharArray(cols) { c -> if (c == leftThumbCol) row[0] else '_' }
        } else {
            row.clone()
        }
    }.toTypedArray()

    val paddedRightChars = layoutData.rightChars.mapIndexed { r, row ->
        if (r == hw.numRows - 1) {
            CharArray(cols) { c -> if (c == rightThumbCol) row[0] else '_' }
        } else {
            row.clone()
        }
    }.toTypedArray()

    println("=== Heatmap renderelés indítása: LTSN_Ergo ===")

    // 4. A renderelő meghívása a vasalt (padded) mátrixokkal
    EffortHeatmapRenderer.renderFullKeyboard(
        leftHalf = leftHalf,
        rightHalf = rightHalf,
        leftEffort = paddedLeftEffort,
        rightEffort = paddedRightEffort,
        leftcolumnConfig = leftConfigs,
        rightcolumnConfig = rightConfigs,
        leftChars = paddedLeftChars,     // <--- Bekerült az opcionális paraméter
        rightChars = paddedRightChars,   // <--- Bekerült az opcionális paraméter
        outputPath = "${layoutName}_heatmap.png"
    )
    println("A billentyűzet hőtérképe legenerálva: ${layoutName}_heatmap.png")
}