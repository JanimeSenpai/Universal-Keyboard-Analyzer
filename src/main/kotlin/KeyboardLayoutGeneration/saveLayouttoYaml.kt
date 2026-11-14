package KeyboardLayoutGeneration

import keyboard_analysis.CombinedLayoutData
import java.io.File

/**
 * Segédfüggvény: Kimásolja az eredeti YAML fájl fejlécét (a mátrixig),
 * majd beilleszti az új kiosztás karaktereit és a thumb billentyűket.
 */
fun saveLayoutToYaml(layout: CombinedLayoutData, originalFile: File, outputFile: File) {
    val originalLines = originalFile.readLines()
    val mainRows = layout.numRows - 1
    val thumbRowIdx = layout.numRows - 1

    // Közvetlenül a CombinedLayoutData utolsó (1 elemű) sorából olvassuk ki a thumb karaktereket
    val leftThumbChar = layout.leftChars.getOrNull(thumbRowIdx)?.firstOrNull() ?: '_'
    val rightThumbChar = layout.rightChars.getOrNull(thumbRowIdx)?.firstOrNull() ?: '_'

    outputFile.bufferedWriter().use { writer ->
        // Kimásoljuk az eredeti fájl tetejét a mátrixig
        for (line in originalLines) {
            if (line.trim().startsWith("thumb_left:") || line.trim().startsWith("thumb_right:")) {
                continue
            }
            if (line.trim().startsWith("matrix:")) {
                writer.write("matrix: |\n")
                break // Itt megállunk az olvasással, jön az új mátrix
            }
            writer.write(line + "\n")
        }

        // Beillesztjük az optimalizált fő mátrixot (a thumb sor kihagyásával)
        for (r in 0 until mainRows) {
            writer.write("  ")
            // Egy kis szóközös formázás, hogy vizuálisan szép maradjon a YAML
            writer.write(layout.leftChars[r].joinToString(" "))
            writer.write("   ")
            writer.write(layout.rightChars[r].joinToString(" "))
            writer.write("\n")
        }

        // Visszaírjuk a thumb karaktereket a CombinedLayoutData aktuális állapota alapján
        writer.write("thumb_left: \"${escapeYamlChar(leftThumbChar)}\"\n")
        writer.write("thumb_right: \"${escapeYamlChar(rightThumbChar)}\"\n")
    }
}

/**
 * Biztosítja, hogy idézőjel (") vagy backslash (\) karakter esetén se törjön el a YAML szintaxis.
 */
private fun escapeYamlChar(ch: Char): String = when (ch) {
    '"' -> "\\\""
    '\\' -> "\\\\"
    else -> ch.toString()
}