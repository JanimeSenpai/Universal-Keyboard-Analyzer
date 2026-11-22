package keyboard_analysis

import EffortModel.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

data class KeyboardConfigYaml(
    @JsonProperty("name") val name: String = "Unknown",
    @JsonProperty("hardware") val hardware: HardwareYaml,
    @JsonProperty("matrix") val matrix: String,
    @JsonProperty("thumb_left") val thumbLeft: String = "_",
    @JsonProperty("thumb_right") val thumbRight: String = "_"
)

data class HardwareYaml(
    @JsonProperty("stagger_type") val staggerType: String? = null,
    @JsonProperty("num_rows") val numRows: Int,
    @JsonProperty("home_row_index") val homeRowIndex: Int,
    @JsonProperty("optimal_finger_assignment") val optimalFingerAssignment: Boolean = false,//minden billentyűt mi mondunk meg melyik ujj üssön le
    @JsonProperty("angle_of_approach_degrees") val angleOfApproachDegrees: Double = 10.0,
    @JsonProperty("left") val left: HardwareHalfYaml,
    @JsonProperty("right") val right: HardwareHalfYaml
) {
    @JsonIgnore
    fun resolveLeftStaggerTypeRaw(): String? =
        left.staggerType?.takeIf { it.isNotBlank() } ?: staggerType?.takeIf { it.isNotBlank() }

    @JsonIgnore
    fun resolveRightStaggerTypeRaw(): String? =
        right.staggerType?.takeIf { it.isNotBlank() } ?: staggerType?.takeIf { it.isNotBlank() }

    @JsonIgnore
    fun resolveLeftStaggerType(): StaggerType = parseStaggerType(resolveLeftStaggerTypeRaw(), "bal")

    @JsonIgnore
    fun resolveRightStaggerType(): StaggerType = parseStaggerType(resolveRightStaggerTypeRaw(), "jobb")

    companion object {
        fun parseStaggerType(raw: String?, handLabel: String): StaggerType {
            val normalized = raw?.trim()?.lowercase()
                ?: throw IllegalArgumentException(
                    "Invalid layout error: Hiányzó stagger_type megadás a(z) $handLabel kézhez (sem a kéz alatt, sem a hardware blokk tetején nincs megadva)."
                )
            return when (normalized) {
                "columnar_stagger" -> StaggerType.COLUMNAR_STAGGER
                "row_stagger" -> StaggerType.ROW_STAGGER
                "matrix" -> StaggerType.MATRIX
                else -> throw IllegalArgumentException(
                    "Invalid layout error: Érvénytelen stagger_type megadás a(z) $handLabel kéznél: '$raw'. Elfogadott értékek: columnar_stagger, row_stagger, matrix."
                )
            }
        }
    }
}

data class HardwareHalfYaml(
    @JsonProperty("stagger_type") val staggerType: String? = null,
    @JsonProperty("offsets") val offsets: List<Double>,
    @JsonProperty("fingers") val fingers: List<String>,
    @JsonProperty("anchors") val anchors: List<Int>
)

class CombinedLayoutData(
    var name: String,
    val hardwareYaml: HardwareYaml,
    val leftEffortMatrix: Array<DoubleArray>,
    val rightEffortMatrix: Array<DoubleArray>,
    val leftChars: Array<CharArray>,
    val rightChars: Array<CharArray>
) {
    val numRows: Int get() = hardwareYaml.numRows
    val homeRowIndex: Int get() = hardwareYaml.homeRowIndex

    // A fizikai oszlop indexének megkeresése a hüvelykujjhoz
    private val leftThumbPhysicalCol = hardwareYaml.left.fingers.indexOfFirst { it.equals("T", true) }.takeIf { it >= 0 } ?: (hardwareYaml.left.fingers.size - 1)
    private val rightThumbPhysicalCol = hardwareYaml.right.fingers.indexOfFirst { it.equals("T", true) }.takeIf { it >= 0 } ?: 0

    // Gyors csere művelet a Simulated Annealing számára
    fun swapKeys(left1: Boolean, r1: Int, c1: Int, left2: Boolean, r2: Int, c2: Int):Boolean {
        val grid1 = if (left1) leftChars else rightChars
        val grid2 = if (left2) leftChars else rightChars
        val temp = grid1[r1][c1]
        grid1[r1][c1] = grid2[r2][c2]
        grid2[r2][c2] = temp
        return true
    }

    fun swapKeysCycle3(
        left1: Boolean, r1: Int, c1: Int,
        left2: Boolean, r2: Int, c2: Int,
        left3: Boolean, r3: Int, c3: Int
    ): Boolean {
        val grid1 = if (left1) leftChars else rightChars
        val grid2 = if (left2) leftChars else rightChars
        val grid3 = if (left3) leftChars else rightChars

        val temp = grid1[r1][c1]
        grid1[r1][c1] = grid2[r2][c2]
        grid2[r2][c2] = grid3[r3][c3]
        grid3[r3][c3] = temp
        return true
    }

    // 1. Oszlopcsere (üres cellákkal együtt, Thumb key-ek kihagyásával)
    fun swapColumns(left1: Boolean, col1: Int, left2: Boolean, col2: Int): Boolean {
        val grid1 = if (left1) leftChars else rightChars
        val grid2 = if (left2) leftChars else rightChars
        val thumbRow = numRows - 1

        for (r in 0 until thumbRow) {
            val temp = grid1[r][col1]
            grid1[r][col1] = grid2[r][col2]
            grid2[r][col2] = temp
        }
        return true
    }

    // 2. Helyi Sorcsere (üres cellákkal együtt, Thumb sor kizárva)
    fun swapRowsLocal(isLeft: Boolean, row1: Int, row2: Int): Boolean {
        val thumbRow = numRows - 1
        if (row1 == thumbRow || row2 == thumbRow) return false

        val grid = if (isLeft) leftChars else rightChars

        for (c in grid[row1].indices) {
            val temp = grid[row1][c]
            grid[row1][c] = grid[row2][c]
            grid[row2][c] = temp
        }
        return true
    }

    // 3. Globális Sorcsere (üres cellákkal együtt, Thumb sor kizárva)
    fun swapRowsGlobal(row1: Int, row2: Int): Boolean {
        val thumbRow = numRows - 1
        if (row1 == thumbRow || row2 == thumbRow) return false

        swapRowsLocal(true, row1, row2)
        swapRowsLocal(false, row1, row2)
        return true
    }

    // 4. Kézen belüli tükrözés (üres cellákkal együtt, Thumb sor kihagyásával)
    fun mirrorHand(isLeft: Boolean) {
        val grid = if (isLeft) leftChars else rightChars
        val thumbRow = numRows - 1

        for (r in 0 until thumbRow) {
            grid[r].reverse()
        }
    }

    // Mélymásolat készítése, ha az SA el akarja menteni az eddigi legjobb állapotot
    fun deepCopy(newName: String = this.name): CombinedLayoutData {
        return CombinedLayoutData(
            name = newName,
            hardwareYaml = hardwareYaml,
            leftEffortMatrix = leftEffortMatrix, // A fizikai effort mátrix konstans, nem kell klónozni
            rightEffortMatrix = rightEffortMatrix,
            leftChars = Array(leftChars.size) { r -> leftChars[r].clone() },
            rightChars = Array(rightChars.size) { r -> rightChars[r].clone() }
        )
    }

    // Karakter -> Effort hozzárendelés az aktuális betűpozíciók alapján
    fun buildCharEffortMap(): Map<Char, Double> {
        val map = HashMap<Char, Double>(64)
        for (r in 0 until numRows) {
            for (c in leftChars[r].indices) {
                val ch = leftChars[r][c]
                if (ch != '_') {
                    val physCol = if (r == numRows - 1) leftThumbPhysicalCol else c
                    map[ch] = leftEffortMatrix[r][physCol]
                }
            }
            for (c in rightChars[r].indices) {
                val ch = rightChars[r][c]
                if (ch != '_') {
                    val physCol = if (r == numRows - 1) rightThumbPhysicalCol else c
                    map[ch] = rightEffortMatrix[r][physCol]
                }
            }
        }
        return map
    }

    // Sorok kinyerése a row analysis számára (bal és jobb oldal egyesítve soronként)
    fun getRows(): List<CharArray> {
        return List(numRows) { r ->
            (leftChars[r] + rightChars[r]).filter { it != '_' }.toCharArray()
        }
    }

    // Ujjankénti karakterhalmazok (pl. "L_P", "R_I") az SFB és pinky elemzésekhez
    fun getFingerCharacterSets(): Map<String, Set<Char>> {
        val fingerMap = HashMap<String, MutableSet<Char>>()

        for (r in 0 until numRows) { //az r változó segítségével megyünk lefele végig az egész oszlopon
            for (c in leftChars[r].indices) { //minden egyes oszlopra bal kézen
                val ch = leftChars[r][c] //vesszük az adott koordinátán levő karaktert
                if (ch != '_') {
                    // Anatómiai ujj direkthezrendelése az SFB miatt
                    val fingerKey = if (r == numRows - 1) "L_T" else "L_${hardwareYaml.left.fingers[c]}"
                    val set = fingerMap.getOrPut(fingerKey) { HashSet() }
                    set.add(ch)
                }
            }
            for (c in rightChars[r].indices) {
                val ch = rightChars[r][c]
                if (ch != '_') {
                    // Anatómiai ujj direkthezrendelése az SFB miatt
                    val fingerKey = if (r == numRows - 1) "R_T" else "R_${hardwareYaml.right.fingers[c]}"
                    val set = fingerMap.getOrPut(fingerKey) { HashSet() }
                    set.add(ch)
                }
            }
        }
        return fingerMap
    }
}

object LayoutParser {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun parseLayout(yamlFile: File): CombinedLayoutData {
        val config = mapper.readValue(yamlFile, KeyboardConfigYaml::class.java)
        val hw = config.hardware

        val matrixLines = config.matrix.trim().lines().filter { it.isNotBlank() }

        // --- VALIDÁCIÓ (Szigorú ellenőrzés beolvasás előtt) ---
        val leftOffs = hw.left.offsets.size
        val leftFings = hw.left.fingers.size
        val leftAnchs = hw.left.anchors.size

        val rightOffs = hw.right.offsets.size
        val rightFings = hw.right.fingers.size
        val rightAnchs = hw.right.anchors.size

        // A teljes sorszámból 1 a thumb sor, a többi a fő mátrix sora
        val expectedMainRows = hw.numRows - 1

        // 1. Mátrix sorainak ellenőrzése
        if (matrixLines.size != expectedMainRows) {
            throw IllegalArgumentException("Invalid layout error: A mátrix sorainak száma (${matrixLines.size}) eltér a konfigurációban várt fő sorok számától ($expectedMainRows).")
        }

        val leftStagger = hw.resolveLeftStaggerType()
        val rightStagger = hw.resolveRightStaggerType()

        fun validateHalfGeometry(
            handLabel: String,
            stagger: StaggerType,
            offs: Int,
            fings: Int,
            anchs: Int
        ) {
            when (stagger) {
                StaggerType.COLUMNAR_STAGGER, StaggerType.MATRIX -> {
                    // Oszlop-eltolásnál az offseteknek az oszlopok (ujjak) számával kell megegyezniük
                    if (offs != fings || fings != anchs) {
                        throw IllegalArgumentException(
                            "Invalid layout error: Columnar/Matrix kialakítás esetén a(z) $handLabel kéz oszlopparamétereinek száma nem egyezik (offsets: $offs, fingers: $fings, anchors: $anchs)."
                        )
                    }
                }
                StaggerType.ROW_STAGGER -> {
                    // Sor-eltolásnál az offseteknek a fő sorok számával kell megegyezniük
                    if (offs != expectedMainRows) {
                        throw IllegalArgumentException(
                            "Invalid layout error: Row stagger kialakítás esetén a(z) $handLabel kéz offset paramétereinek száma ($offs) nem egyezik a fő sorok számával ($expectedMainRows)."
                        )
                    }
                    // Az ujjak és horgonyok száma (oszlopok) viszont továbbra is meg kell egyezzen egymással
                    if (fings != anchs) {
                        throw IllegalArgumentException(
                            "Invalid layout error: A(z) $handLabel kéz ujj- és horgonyparamétereinek száma nem egyezik (fingers: $fings, anchors: $anchs)."
                        )
                    }
                }
            }
        }

        // 2. Kézfelénkénti kialakítás-specifikus ellenőrzések
        validateHalfGeometry("bal", leftStagger, leftOffs, leftFings, leftAnchs)
        validateHalfGeometry("jobb", rightStagger, rightOffs, rightFings, rightAnchs)
        // -------------------------------------------------------

        val leftCols = leftFings
        val rightCols = rightFings

        val leftHalf = KeyboardHalf(Hand.LEFT, leftStagger, hw.numRows, leftCols, hw.left.offsets, hw.homeRowIndex)
        val rightHalf = KeyboardHalf(Hand.RIGHT, rightStagger, hw.numRows, rightCols, hw.right.offsets, hw.homeRowIndex)

        val leftConfigs = hw.left.fingers.indices.map { i ->
            ColumnConfig(
                colIndex = i,
                anchorColIndex = hw.left.anchors[i],
                finger = parseFinger(hw.left.fingers[i])
            )
        }
        val rightConfigs = hw.right.fingers.indices.map { i ->
            ColumnConfig(
                colIndex = i,
                anchorColIndex = hw.right.anchors[i],
                finger = parseFinger(hw.right.fingers[i])
            )
        }

        val leftEffort = CustomKeyboardEffortGenerator(
            keyboardHalf = leftHalf,
            columnConfigs = leftConfigs,
            angleOfApproachDegrees = hw.angleOfApproachDegrees,
            useOptimalFingerAssignment = hw.optimalFingerAssignment
        ).generateEffortMatrix().map { it.toDoubleArray() }.toTypedArray()

        val rightEffort = CustomKeyboardEffortGenerator(
            keyboardHalf = rightHalf,
            columnConfigs = rightConfigs,
            angleOfApproachDegrees = hw.angleOfApproachDegrees,
            useOptimalFingerAssignment = hw.optimalFingerAssignment
        ).generateEffortMatrix().map { it.toDoubleArray() }.toTypedArray()

        val leftChars = Array(hw.numRows) { CharArray(leftCols) { '_' } }
        val rightChars = Array(hw.numRows) { CharArray(rightCols) { '_' } }

        for (r in 0 until expectedMainRows) {
            val cleanLine = matrixLines[r].replace(Regex("\\s+"), "")

            if (cleanLine.length < leftCols + rightCols) {
                throw IllegalArgumentException("Invalid layout error: A mátrix $r. sorában túl kevés karakter található (${cleanLine.length}). Pontosan ${leftCols + rightCols} karakter szükséges a szóközök (szeparátorok) nélkül.")
            }

            // A bal kéz levágja az elejéről a saját karaktereit (pontosan annyit, ahány oszlopa van)
            val leftString = cleanLine.take(leftCols)

            // A jobb kéz megkapja a maradékot
            val rightString = cleanLine.drop(leftCols).take(rightCols)

            for (c in 0 until leftCols) leftChars[r][c] = leftString[c]
            for (c in 0 until rightCols) rightChars[r][c] = rightString[c]
        }

        // Thumb sor beolvasása közvetlenül a stringekből (1 karakteres méretű tömb)
        val thumbRowIdx = hw.numRows - 1
        leftChars[thumbRowIdx] = charArrayOf(config.thumbLeft.firstOrNull() ?: '_')
        rightChars[thumbRowIdx] = charArrayOf(config.thumbRight.firstOrNull() ?: '_')

        return CombinedLayoutData(config.name, hw, leftEffort, rightEffort, leftChars, rightChars)
    }

    private fun parseFinger(code: String): Finger = when (code.uppercase()) {
        "P" -> Finger.PINKY
        "R" -> Finger.RING
        "M" -> Finger.MIDDLE
        "I" -> Finger.INDEX
        "T" -> Finger.THUMB
        else -> throw IllegalArgumentException("Invalid layout error: Ismeretlen ujj kód a konfigurációban: '$code'. Elfogadott értékek: P, R, M, I, T.")
    }

    /**
     * Megkeresi a billentyűzet YAML fájlt a megadott mappában ("keyboards" vagy "generatedkeyboards").
     * Támogatja az almappás útvonalat (pl. "LTSN_Ergo_Mini_CNS-.../LTSN_Ergo_Mini_cns_optimized_1.yaml")
     * és a sima fájlnevet is (rekurzív kereséssel a megadott mappán belül).
     */
    fun resolveLayoutFile(layoutName: String, folder: String = "keyboards"): File? {
        var cleanName = layoutName.trim()
        if (!cleanName.endsWith(".yaml")) cleanName += ".yaml"

        // 1. Ha teljes vagy közvetlenül létező útvonalat kapott
        val directFile = File(cleanName)
        if (directFile.exists() && directFile.isFile) return directFile

        // 2. A megadott mappán belüli relatív útvonal (pl. keyboards/foo.yaml vagy generatedkeyboards/sub/foo.yaml)
        val inFolder = File(folder, cleanName)
        if (inFolder.exists() && inFolder.isFile) return inFolder

        // 3. Ha a generatedkeyboards (vagy más) mappában csak a fájlnevet adták meg almappa nélkül, megkeressük rekurzívan
        val baseDir = File(folder)
        if (baseDir.exists() && baseDir.isDirectory) {
            val found = baseDir.walkTopDown().firstOrNull {
                it.isFile && (it.name.equals(cleanName, ignoreCase = true) || it.nameWithoutExtension.equals(layoutName.trim(), ignoreCase = true))
            }
            if (found != null) return found
        }

        return null
    }
}