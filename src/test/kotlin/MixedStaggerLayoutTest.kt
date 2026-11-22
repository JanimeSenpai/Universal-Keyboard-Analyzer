import EffortModel.StaggerType
import KeyboardLayoutGeneration.renderCharEffortlayout
import KeyboardLayoutGeneration.saveLayoutToYaml
import keyboard_analysis.LayoutParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MixedStaggerLayoutTest {

    @Test
    fun `backward compatible parsing of existing keyboards`() {
        val colStagFile = File("keyboards/ltsnergomini.yaml")
        assertTrue(colStagFile.exists(), "keyboards/ltsnergomini.yaml must exist")
        val colData = LayoutParser.parseLayout(colStagFile)
        assertEquals(StaggerType.COLUMNAR_STAGGER, colData.hardwareYaml.resolveLeftStaggerType())
        assertEquals(StaggerType.COLUMNAR_STAGGER, colData.hardwareYaml.resolveRightStaggerType())

        val rowStagFile = File("keyboards/ltsnergorowstagger.yaml")
        assertTrue(rowStagFile.exists(), "keyboards/ltsnergorowstagger.yaml must exist")
        val rowData = LayoutParser.parseLayout(rowStagFile)
        assertEquals(StaggerType.ROW_STAGGER, rowData.hardwareYaml.resolveLeftStaggerType())
        assertEquals(StaggerType.ROW_STAGGER, rowData.hardwareYaml.resolveRightStaggerType())
    }

    @Test
    fun `mixed left row_stagger and right columnar_stagger without top-level stagger_type`(@TempDir tempDir: Path) {
        val yamlFile = tempDir.resolve("mixed_keyboard.yaml").toFile()
        yamlFile.writeText(
            """
            name: "Mixed Test Split"
            hardware:
              num_rows: 5
              home_row_index: 2
              optimal_finger_assignment: false
              angle_of_approach_degrees: 0.0
              left:
                stagger_type: "row_stagger"
                offsets: [-0.25, -0.25, 0.0, -0.5]
                fingers: ["P", "P", "R", "M", "I", "I"]
                anchors: [1, 1, 2, 3, 4, 4]
              right:
                stagger_type: "columnar_stagger"
                offsets: [0.38, 0.25, 0.00, 0.25, 0.50, 0.50]
                fingers: ["I", "I", "M", "R", "P", "P"]
                anchors: [1, 1, 2, 3, 4, 4]
            matrix: |
              __ _ _ __   __ _ w xq
              úó u á yí   fz k d h_
              öi o e aő   bl t s np
              cü . , éű   vr m g j-
            thumb_left: "_"
            thumb_right: " "
            """.trimIndent()
        )

        val layoutData = LayoutParser.parseLayout(yamlFile)
        assertEquals(StaggerType.ROW_STAGGER, layoutData.hardwareYaml.resolveLeftStaggerType())
        assertEquals(StaggerType.COLUMNAR_STAGGER, layoutData.hardwareYaml.resolveRightStaggerType())

        // Verify left (row stagger) and right (columnar stagger) produce genuinely different effort geometries
        assertNotEquals(
            layoutData.leftEffortMatrix[1][2],
            layoutData.rightEffortMatrix[1][3],
            "Left row_stagger and right columnar_stagger should yield distinct physical effort values"
        )

        // Verify heatmap rendering works cleanly
        val heatmapFile = tempDir.resolve("mixed_heatmap.png").toFile()
        renderCharEffortlayout(layoutData, heatmapFile.absolutePath)
        assertTrue(heatmapFile.exists() && heatmapFile.length() > 0, "Heatmap PNG should be generated")

        // Verify saveLayoutToYaml preserves per-hand stagger_type and round-trips cleanly
        val savedYaml = tempDir.resolve("mixed_saved.yaml").toFile()
        saveLayoutToYaml(layoutData, yamlFile, savedYaml)
        val reloaded = LayoutParser.parseLayout(savedYaml)
        assertEquals(StaggerType.ROW_STAGGER, reloaded.hardwareYaml.resolveLeftStaggerType())
        assertEquals(StaggerType.COLUMNAR_STAGGER, reloaded.hardwareYaml.resolveRightStaggerType())
    }

    @Test
    fun `per-hand stagger overrides top-level fallback`(@TempDir tempDir: Path) {
        val yamlFile = tempDir.resolve("override_keyboard.yaml").toFile()
        yamlFile.writeText(
            """
            name: "Override Test"
            hardware:
              stagger_type: "columnar_stagger"
              num_rows: 5
              home_row_index: 2
              optimal_finger_assignment: false
              angle_of_approach_degrees: 0.0
              left:
                stagger_type: "row_stagger"
                offsets: [-0.25, -0.25, 0.0, -0.5]
                fingers: ["P", "P", "R", "M", "I", "I"]
                anchors: [1, 1, 2, 3, 4, 4]
              right:
                offsets: [0.38, 0.25, 0.00, 0.25, 0.50, 0.50]
                fingers: ["I", "I", "M", "R", "P", "P"]
                anchors: [1, 1, 2, 3, 4, 4]
            matrix: |
              __ _ _ __   __ _ w xq
              úó u á yí   fz k d h_
              öi o e aő   bl t s np
              cü . , éű   vr m g j-
            thumb_left: "_"
            thumb_right: "_"
            """.trimIndent()
        )

        val layoutData = LayoutParser.parseLayout(yamlFile)
        assertEquals(StaggerType.ROW_STAGGER, layoutData.hardwareYaml.resolveLeftStaggerType())
        assertEquals(StaggerType.COLUMNAR_STAGGER, layoutData.hardwareYaml.resolveRightStaggerType())
    }

    @Test
    fun `asymmetric column counts render without IndexOutOfBoundsException`(@TempDir tempDir: Path) {
        val yamlFile = tempDir.resolve("asym_cols.yaml").toFile()
        yamlFile.writeText(
            """
            name: "Asymmetric Cols Test"
            hardware:
              num_rows: 5
              home_row_index: 2
              optimal_finger_assignment: false
              angle_of_approach_degrees: 0.0
              left:
                stagger_type: "row_stagger"
                offsets: [-0.25, -0.25, 0.0, -0.5]
                fingers: ["P", "P", "R", "M", "I", "I"]
                anchors: [1, 1, 2, 3, 4, 4]
              right:
                stagger_type: "columnar_stagger"
                offsets: [0.38, 0.25, 0.00, 0.25, 0.50]
                fingers: ["I", "I", "M", "R", "P"]
                anchors: [1, 1, 2, 3, 4]
            matrix: |
              ______   ____w
              qwertz   uiop_
              asdfgh   jkléá
              yxcvbn   m,.-_
            thumb_left: "_"
            thumb_right: " "
            """.trimIndent()
        )

        val layoutData = LayoutParser.parseLayout(yamlFile)
        val heatmapFile = tempDir.resolve("asym_cols_heatmap.png").toFile()
        renderCharEffortlayout(layoutData, heatmapFile.absolutePath)
        assertTrue(heatmapFile.exists() && heatmapFile.length() > 0)
    }

    @Test
    fun `missing stagger_type and mismatched per-hand offsets fail validation`(@TempDir tempDir: Path) {
        val missingStagger = tempDir.resolve("missing_stagger.yaml").toFile()
        missingStagger.writeText(
            """
            name: "Missing Stagger"
            hardware:
              num_rows: 5
              home_row_index: 2
              left:
                offsets: [0.0, 0.0, 0.0, 0.0]
                fingers: ["P", "R", "M", "I"]
                anchors: [0, 1, 2, 3]
              right:
                offsets: [0.0, 0.0, 0.0, 0.0]
                fingers: ["I", "M", "R", "P"]
                anchors: [0, 1, 2, 3]
            matrix: |
              ____ ____
              abcd efgh
              ijkl mnop
              qrst uvwx
            thumb_left: "_"
            thumb_right: "_"
            """.trimIndent()
        )
        assertFailsWith<IllegalArgumentException> {
            LayoutParser.parseLayout(missingStagger)
        }

        val badRightOffsets = tempDir.resolve("bad_right_offsets.yaml").toFile()
        badRightOffsets.writeText(
            """
            name: "Bad Right Offsets"
            hardware:
              num_rows: 5
              home_row_index: 2
              left:
                stagger_type: "row_stagger"
                offsets: [-0.25, -0.25, 0.0, -0.5]
                fingers: ["P", "P", "R", "M", "I", "I"]
                anchors: [1, 1, 2, 3, 4, 4]
              right:
                stagger_type: "columnar_stagger"
                offsets: [0.0, 0.0, 0.0, 0.0]
                fingers: ["I", "I", "M", "R", "P", "P"]
                anchors: [1, 1, 2, 3, 4, 4]
            matrix: |
              ______ ______
              qwertz uiopőú
              asdfgh jkléáű
              yxcvbn m,.-__
            thumb_left: "_"
            thumb_right: "_"
            """.trimIndent()
        )
        assertFailsWith<IllegalArgumentException> {
            LayoutParser.parseLayout(badRightOffsets)
        }
    }
}
