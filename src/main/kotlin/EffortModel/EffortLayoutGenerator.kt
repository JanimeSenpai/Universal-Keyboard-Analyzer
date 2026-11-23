package EffortModel

import kotlin.math.*




object EffortGeneratorSettings {
    var lateralPenaltyPx: Double = 2.2
    var distancePenaltyPd: Double = 1.3
    var activationMargin: Double = 0.3
    var boxInKeySpace: Boolean = false
    var biomechanicalWallMultiplierY: Double = 25.0
    var biomechanicalWallMultiplierX: Double = 25.0
    var overrideApplyStaticFingerOffsets: Boolean? = null // null = automatikus (staggerType != COLUMNAR_STAGGER)

    fun resetDefaults() {
        lateralPenaltyPx = 2.2
        distancePenaltyPd = 1.3
        activationMargin = 0.3
        boxInKeySpace = false
        biomechanicalWallMultiplierY = 25.0
        biomechanicalWallMultiplierX = 25.0
        overrideApplyStaticFingerOffsets = null
    }
}

class CustomKeyboardEffortGenerator(
    private val keyboardHalf: KeyboardHalf,
    private val columnConfigs: List<ColumnConfig>,
    private val angleOfApproachDegrees: Double = 0.0, // Osztott billentyűzetnél 0 fok!
    private val lateralPenaltyPx: Double = EffortGeneratorSettings.lateralPenaltyPx, //2.0 eredetileg
    private val distancePenaltyPd: Double = EffortGeneratorSettings.distancePenaltyPd,  //1.3 eredetileg
    private val activationMargin: Double = EffortGeneratorSettings.activationMargin,
    private val useOptimalFingerAssignment: Boolean = false,
    private val boxInKeySpace: Boolean = EffortGeneratorSettings.boxInKeySpace,
    private val applyStaticFingerOffsets: Boolean = EffortGeneratorSettings.overrideApplyStaticFingerOffsets ?: (keyboardHalf.staggerType != StaggerType.COLUMNAR_STAGGER)
) {

    private val handFingers: List<Pair<Int, Finger>> = columnConfigs
        .map { it.anchorColIndex to it.finger }
        .distinct()

    private fun calculateEffortForFinger(
        targetCenter: Coordinate,
        anchorColIndex: Int,
        finger: Finger
    ): Double {
        // MEGOLDÁS 1: A hüvelykujj horgonypontja a legalsó sor, minden más ujjé a homeRowIndex
        val anchorRow = if (finger == Finger.THUMB) (keyboardHalf.numRows - 1) else keyboardHalf.homeRowIndex
        val anchorCenter = keyboardHalf.getPhysicalCoordinate(anchorRow, anchorColIndex)

        val xSign = if (keyboardHalf.hand == Hand.LEFT) 1.0 else -1.0
        val homeX = anchorCenter.x + if (applyStaticFingerOffsets) (finger.homeOffsetX * xSign) else 0.0
        val homeY = anchorCenter.y - if (applyStaticFingerOffsets) finger.homeOffsetY else 0.0

        val dx = targetCenter.x - homeX
        val dy = targetCenter.y - homeY

        val theta = Math.toRadians(angleOfApproachDegrees) * xSign

        val distX: Double
        val distY: Double

        if (boxInKeySpace) {
            val actDx = sign(dx) * max(0.0, abs(dx) - activationMargin)
            val actDy = sign(dy) * max(0.0, abs(dy) - activationMargin)
            distX = abs(actDx * cos(theta) + actDy * sin(theta))
            distY = abs(-actDx * sin(theta) + actDy * cos(theta))
        } else {
            val rotX = dx * cos(theta) + dy * sin(theta)
            val rotY = -dx * sin(theta) + dy * cos(theta)
            distX = max(0.0, abs(rotX) - activationMargin)
            distY = max(0.0, abs(rotY) - activationMargin)
        }

        val fX = log2(1.0 + distancePenaltyPd * distX)
        val fY = log2(1.0 + distancePenaltyPd * distY)

        // Újdonság a colemakdh effort számításhoz képest: Biomechanikai fal a túl rövid ujjak miatt
        // Csak a függőleges nyújtózást (Y) nézzük, mert az ujj ott fogy el
        val overreachy = max(0.0, distY - finger.maxComfortReachY)
        val overreachx = max(0.0, distX - finger.maxComfortReachX)

        // Ha túllépi a határt, hatványozottan drágul (pl. 3. hatvány).
        // A 10.0 egy szorzó, amit feljebb vihetsz, ha még "keményebb" falat akarsz.
        val biomechanicalWallY = Math.pow(overreachy, 3.0) * EffortGeneratorSettings.biomechanicalWallMultiplierY
        val biomechanicalWallX = Math.pow(overreachx, 3.0) * EffortGeneratorSettings.biomechanicalWallMultiplierX

        val pf = finger.baseEffort
        return pf + hypot(pf * fY, lateralPenaltyPx * fX)+ biomechanicalWallY+biomechanicalWallX
    }

    fun calculateEffort(targetRow: Int, targetCol: Int): Double {
        val targetCenter = keyboardHalf.getPhysicalCoordinate(targetRow, targetCol)

        // MEGOLDÁS 2: Ha a hüvelykujj sorát (legalsó sort) vizsgáljuk, kikényszerítjük a hüvelykujj használatát
        if (targetRow == keyboardHalf.numRows - 1) {
            val thumbAnchorCol = columnConfigs.find { it.finger == Finger.THUMB }?.anchorColIndex
                ?: if (keyboardHalf.hand == Hand.LEFT) (keyboardHalf.numColumns - 1) else 0

            return calculateEffortForFinger(targetCenter, thumbAnchorCol, Finger.THUMB)
        }

        return if (useOptimalFingerAssignment) {
            handFingers.minOf { (anchorCol, finger) ->
                calculateEffortForFinger(targetCenter, anchorCol, finger)
            }
        } else {
            val config = columnConfigs.find { it.colIndex == targetCol }
                ?: throw IllegalArgumentException("Hiányzó konfiguráció a(z) $targetCol. oszlophoz!")
            calculateEffortForFinger(targetCenter, config.anchorColIndex, config.finger)
        }
    }

    fun generateEffortMatrix(): List<List<Double>> {
        println("$applyStaticFingerOffsets applystaticfingeroffsets" )
        return (0 until keyboardHalf.numRows).map { r ->
            (0 until keyboardHalf.numColumns).map { c ->
                calculateEffort(r, c)
            }
        }
    }
}









class   ColemakDhEffortGenerator(
    private val keyboardHalf: KeyboardHalf,
    private val columnConfigs: List<ColumnConfig>,
    private val angleOfApproachDegrees: Double = 10.0,
    private val lateralPenaltyPx: Double = 2.0,
    private val distancePenaltyPd: Double = 1.3, // log2-höz tartozó skálázó konstans (1.30 - 1.35)
    private val activationMargin: Double = 0.3,
    private val useOptimalFingerAssignment: Boolean = true,
    private val boxInKeySpace: Boolean = false // true = image_6d063a (szigorúbb külső oszlopok), false = image_6d6e7d
) {
    private val handFingers: List<Pair<Int, Finger>> = columnConfigs
        .map { it.anchorColIndex to it.finger }
        .distinct()

    private fun calculateEffortForFinger(
        targetCenter: Coordinate,
        anchorColIndex: Int,
        finger: Finger
    ): Double {
        val anchorCenter = keyboardHalf.getPhysicalCoordinate(keyboardHalf.homeRowIndex, anchorColIndex)

        val xSign = if (keyboardHalf.hand == Hand.LEFT) 1.0 else -1.0 //Előjel
        val homeX = anchorCenter.x + (finger.homeOffsetX * xSign)
        val homeY = anchorCenter.y - finger.homeOffsetY

        val dx = targetCenter.x - homeX
        val dy = targetCenter.y - homeY

        val theta = Math.toRadians(angleOfApproachDegrees) * xSign

        val distX: Double
        val distY: Double

        if (boxInKeySpace) {
            // Aktivációs doboz levonása a fizikai gomb terében (erősebb büntetés a CapsLock és felső sorokra)
            val actDx = sign(dx) * max(0.0, abs(dx) - activationMargin)
            val actDy = sign(dy) * max(0.0, abs(dy) - activationMargin)
            distX = abs(actDx * cos(theta) + actDy * sin(theta))
            distY = abs(-actDx * sin(theta) + actDy * cos(theta))
        } else {
            // Aktivációs doboz levonása a forgatott (alkar) térben
            val rotX = dx * cos(theta) + dy * sin(theta)
            val rotY = -dx * sin(theta) + dy * cos(theta)
            distX = max(0.0, abs(rotX) - activationMargin)
            distY = max(0.0, abs(rotY) - activationMargin)
        }

        // Fitts törvénye 2-es alapú logaritmussal
        val fX = log2(1.0 + distancePenaltyPd * distX)
        val fY = log2(1.0 + distancePenaltyPd * distY)

        val pf = finger.baseEffort
        return pf + hypot(pf * fY, lateralPenaltyPx * fX)
    }

    fun calculateEffort(targetRow: Int, targetCol: Int): Double {
        val targetCenter = keyboardHalf.getPhysicalCoordinate(targetRow, targetCol)

        return if (useOptimalFingerAssignment) {
            handFingers.minOf { (anchorCol, finger) ->
                calculateEffortForFinger(targetCenter, anchorCol, finger)
            }
        } else {
            val config = columnConfigs.find { it.colIndex == targetCol }
                ?: throw IllegalArgumentException("Hiányzó konfiguráció a(z) $targetCol. oszlophoz!")
            calculateEffortForFinger(targetCenter, config.anchorColIndex, config.finger)
        }
    }

    fun generateEffortMatrix(): List<List<Double>> {
        return (0 until keyboardHalf.numRows).map { r ->
            (0 until keyboardHalf.numColumns).map { c ->
                calculateEffort(r, c)
            }
        }
    }
}