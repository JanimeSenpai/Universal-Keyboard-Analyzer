package KeyboardLayoutGeneration

import EffortModel.ColumnConfig
import EffortModel.EffortHeatmapRenderer
import EffortModel.Hand
import EffortModel.KeyboardHalf
import EffortModel.StaggerType
import keyboard_analysis.CombinedLayoutData
import parseFingerForTest

fun renderCharEffortlayout(layoutData: CombinedLayoutData, imageOutputPath: String ="${layoutData.name}_heatmap.png") {
    val layoutName = layoutData.name


    val hw = layoutData.hardwareYaml

    // 2. Szükséges konfigurációk visszakonvertálása a renderelő által várt típusokra
    val leftStagger = hw.resolveLeftStaggerType()
    val rightStagger = hw.resolveRightStaggerType()

    // Figyelem: Az ujjak száma (fingers.size) adja meg az oszlopokat kézfelénként!
    val leftHalf = KeyboardHalf(Hand.LEFT, leftStagger, hw.numRows, hw.left.fingers.size, hw.left.offsets, hw.homeRowIndex)
    val rightHalf = KeyboardHalf(Hand.RIGHT, rightStagger, hw.numRows, hw.right.fingers.size, hw.right.offsets, hw.homeRowIndex)

    val leftConfigs = hw.left.fingers.indices.map { i ->
        ColumnConfig(colIndex = i, anchorColIndex = hw.left.anchors[i], finger = parseFingerForTest(hw.left.fingers[i]))
    }
    val rightConfigs = hw.right.fingers.indices.map { i ->
        ColumnConfig(colIndex = i, anchorColIndex = hw.right.anchors[i], finger = parseFingerForTest(hw.right.fingers[i]))
    }

    // 3. Adapter a Rongyos Tömbhöz (Padding a renderelő miatt, külön bal és jobb oszlopszámmal)
    val leftCols = leftHalf.numColumns
    val rightCols = rightHalf.numColumns

    // A bal és jobb hüvelykujj fizikai oszlopát keressük (hova rajzolja a renderelő a gombot)
    val leftThumbCol = hw.left.fingers.indexOfFirst { it.equals("T", true) }.takeIf { it >= 0 } ?: (leftCols - 1)
    val rightThumbCol = hw.right.fingers.indexOfFirst { it.equals("T", true) }.takeIf { it >= 0 } ?: 0

    val paddedLeftEffort = layoutData.leftEffortMatrix.mapIndexed { r, row ->
        if (r == hw.numRows - 1) { // Ha a hüvelykujj sora
            List(leftCols) { c -> if (c == leftThumbCol) row[leftThumbCol] else 99.9 }
        } else {
            row.toList()
        }
    }

    val paddedRightEffort = layoutData.rightEffortMatrix.mapIndexed { r, row ->
        if (r == hw.numRows - 1) {
            List(rightCols) { c -> if (c == rightThumbCol) row[rightThumbCol] else 99.9 }
        } else {
            row.toList()
        }
    }

    val paddedLeftChars = layoutData.leftChars.mapIndexed { r, row ->
        if (r == hw.numRows - 1) {
            CharArray(leftCols) { c -> if (c == leftThumbCol) row[0] else '_' }
        } else {
            row.clone()
        }
    }.toTypedArray()

    val paddedRightChars = layoutData.rightChars.mapIndexed { r, row ->
        if (r == hw.numRows - 1) {
            CharArray(rightCols) { c -> if (c == rightThumbCol) row[0] else '_' }
        } else {
            row.clone()
        }
    }.toTypedArray()

    println("=== Heatmap renderelés indítása: ${layoutData.name} ===")

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
        outputPath =imageOutputPath //"$imageOutputPath/${layoutName}_heatmap.png"
    )
    println("A billentyűzet hőtérképe legenerálva: ${layoutName}_heatmap.png")
}