package EffortModel
data class Coordinate(val x: Double, val y: Double)

enum class Hand {
    LEFT,
    RIGHT
}

enum class StaggerType {
    MATRIX,
    ROW_STAGGER,
    COLUMNAR_STAGGER
}

/**
 * Az ujjak anatómiai paraméterei a Colemak-DH dokumentáció alapján:
 * - baseEffort (Pf): Az ujj alap terhelési szorzója
 * - homeOffsetX: Vízszintes eltolás az alapsori gomb középpontjától (bal kézre értelmezve)
 * - homeOffsetY: Függőleges eltolás az alapsori gomb középpontjától (+ felfelé, - lefelé)
 */
enum class Finger(
    var baseEffort: Double,
    var homeOffsetX: Double,
    var homeOffsetY: Double,
    var maxComfortReachY: Double, // <-- ÚJ: Fizikai ujjhossz korlát (Unit-ban)
    var maxComfortReachX: Double
) {
    THUMB(baseEffort = 1.0, homeOffsetX = 0.0, homeOffsetY = 0.0, maxComfortReachY = 1.5, maxComfortReachX = 1.1),
    INDEX(baseEffort = 1.0, homeOffsetX = -0.04, homeOffsetY = -0.32, maxComfortReachY = 1.23, maxComfortReachX = 0.95),
    MIDDLE(baseEffort = 1.1, homeOffsetX = 0.03, homeOffsetY = 0.16, maxComfortReachY = 1.4848, maxComfortReachX = 0.8), // Leghosszabb
    RING(baseEffort = 1.3, homeOffsetX = 0.00, homeOffsetY = 0.14, maxComfortReachY = 1.25, maxComfortReachX = 0.8),
    PINKY(baseEffort = 1.6, homeOffsetX = 0.01, homeOffsetY = -0.15, maxComfortReachY = 0.8, maxComfortReachX = 1.1); // Legrövidebb, hamar "falba ütközik"

    companion object {
        fun resetDefaults() {
            THUMB.apply { baseEffort = 1.0; homeOffsetX = 0.0; homeOffsetY = 0.0; maxComfortReachY = 1.5; maxComfortReachX = 1.1 }
            INDEX.apply { baseEffort = 1.0; homeOffsetX = -0.04; homeOffsetY = -0.32; maxComfortReachY = 1.23; maxComfortReachX = 0.95 }
            MIDDLE.apply { baseEffort = 1.1; homeOffsetX = 0.03; homeOffsetY = 0.16; maxComfortReachY = 1.4848; maxComfortReachX = 0.8 }
            RING.apply { baseEffort = 1.3; homeOffsetX = 0.00; homeOffsetY = 0.14; maxComfortReachY = 1.25; maxComfortReachX = 0.8 }
            PINKY.apply { baseEffort = 1.6; homeOffsetX = 0.01; homeOffsetY = -0.15; maxComfortReachY = 0.8; maxComfortReachX = 1.1 }
        }
    }
}

data class ColumnConfig(
    val colIndex: Int,
    val anchorColIndex: Int,
    val finger: Finger
)

class KeyboardHalf(
    val hand: Hand,
    val staggerType: StaggerType,
    val numRows: Int,
    val numColumns: Int,
    val offsets: List<Double>,
    val homeRowIndex: Int = numRows - 2
) {
    fun getPhysicalCoordinate(rowIndex: Int, colIndex: Int): Coordinate {
        var x = colIndex.toDouble()
        var y = rowIndex.toDouble()

        when (staggerType) {
            StaggerType.MATRIX -> {}
            StaggerType.ROW_STAGGER -> {
                x += offsets.getOrElse(rowIndex) { 0.0 }
            }
            StaggerType.COLUMNAR_STAGGER -> {
                y += offsets.getOrElse(colIndex) { 0.0 }
            }
        }
        return Coordinate(x, y)
    }
}