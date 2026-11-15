package gui

import CorpusAnalyzer.*
import EffortModel.*
import KeyboardLayoutGeneration.*
import keyboard_analysis.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Magas felbontású (HiDPI-tudatos), natív BufferedImage megjelenítő panel.
 * Elkerüli a Swing ImageIcon alacsony logikai felbontásra történő előzetes lekicsinyítését,
 * és közvetlenül a képernyő fizikai (device) pixeleire skáláz többlépcsős bicubic interpolációval.
 */
class HeatmapImagePanel(private var emptyMessage: String) : JPanel(BorderLayout()) {
    private var sourceImage: BufferedImage? = null
    private var sourceFile: File? = null
    private var fitToWindow: Boolean = true
    private var zoomFactor: Double = 1.0

    // Gyorsítótár a fizikai pixelekre előre átméretezett képhez
    private var cachedScaledImage: BufferedImage? = null
    private var cachedPhysW: Int = -1
    private var cachedPhysH: Int = -1

    private val zoomLabel = JLabel("Illesztve").apply {
        font = Font("SansSerif", Font.PLAIN, 11)
        foreground = Color(90, 90, 90)
    }

    private val canvas = object : JPanel() {
        init {
            background = Color(245, 245, 245)
            isOpaque = true
        }

        override fun getPreferredSize(): Dimension {
            val img = sourceImage ?: return Dimension(400, 260)
            return if (fitToWindow) {
                Dimension(400, 260)
            } else {
                Dimension((img.width * zoomFactor).roundToInt(), (img.height * zoomFactor).roundToInt())
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.KEY_ANTIALIASING.let { RenderingHints.VALUE_ANTIALIAS_ON })
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)

                val img = sourceImage
                if (img == null) {
                    g2.color = Color(110, 110, 110)
                    g2.font = Font("SansSerif", Font.PLAIN, 13)
                    val fm = g2.fontMetrics
                    val tx = max(10, (width - fm.stringWidth(emptyMessage)) / 2)
                    val ty = max(20, (height + fm.ascent) / 2)
                    g2.drawString(emptyMessage, tx, ty)
                    return
                }

                // Lekérdezzük a kijelző valós HiDPI skálázási faktorát (pl. 1.25x, 1.5x, 2.0x Windows-on)
                val tx: AffineTransform = g2.transform
                val dpiScaleX = if (tx.scaleX > 0.0) tx.scaleX else 1.0
                val dpiScaleY = if (tx.scaleY > 0.0) tx.scaleY else 1.0

                val pad = 8
                val availW = max(50, width - pad * 2)
                val availH = max(50, height - pad * 2)

                val logicalScale = if (fitToWindow) {
                    min(availW.toDouble() / img.width, availH.toDouble() / img.height)
                } else {
                    zoomFactor
                }

                val drawLogicalW = max(1, (img.width * logicalScale).roundToInt())
                val drawLogicalH = max(1, (img.height * logicalScale).roundToInt())
                val offsetX = max(0, (width - drawLogicalW) / 2)
                val offsetY = max(0, (height - drawLogicalH) / 2)

                // A célzott fizikai (eszköz) felbontás kiszámítása
                val targetPhysW = max(1, (drawLogicalW * dpiScaleX).roundToInt())
                val targetPhysH = max(1, (drawLogicalH * dpiScaleY).roundToInt())

                if (fitToWindow) {
                    val pct = (logicalScale * 100.0).roundToInt()
                    zoomLabel.text = "Illesztve ($pct% | Natív: ${img.width}×${img.height} px)"
                } else {
                    val pct = (zoomFactor * 100.0).roundToInt()
                    zoomLabel.text = "Zoom: $pct% (Natív: ${img.width}×${img.height} px)"
                }

                val scaledImg = getOrCreateHighQualityScaled(img, targetPhysW, targetPhysH)

                // Közvetlenül 1:1 fizikai pixelen rajzoljuk ki a képet, megkerülve a Swing elmosódott skálázását
                val origTransform = g2.transform
                g2.translate(offsetX.toDouble(), offsetY.toDouble())
                g2.scale(1.0 / dpiScaleX, 1.0 / dpiScaleY)
                g2.drawImage(scaledImg, 0, 0, null)
                g2.transform = origTransform
            } finally {
                g2.dispose()
            }
        }
    }

    private val scrollPane = JScrollPane(canvas).apply {
        border = null
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = 16
        horizontalScrollBar.unitIncrement = 16
    }

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
            background = Color(238, 238, 242)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color(210, 210, 215))

            val btnFit = JButton("Ablakhoz igazítás").apply {
                font = Font("SansSerif", Font.PLAIN, 11)
                margin = Insets(1, 6, 1, 6)
                addActionListener {
                    fitToWindow = true
                    revalidateAndRepaint()
                }
            }
            val btn100 = JButton("100% (Eredeti)").apply {
                font = Font("SansSerif", Font.PLAIN, 11)
                margin = Insets(1, 6, 1, 6)
                addActionListener {
                    fitToWindow = false
                    zoomFactor = 1.0
                    revalidateAndRepaint()
                }
            }
            val btnZoomOut = JButton("−").apply {
                font = Font("SansSerif", Font.BOLD, 11)
                margin = Insets(1, 6, 1, 6)
                toolTipText = "Kicsinyítés"
                addActionListener {
                    stepZoom(0.85)
                }
            }
            val btnZoomIn = JButton("+").apply {
                font = Font("SansSerif", Font.BOLD, 11)
                margin = Insets(1, 6, 1, 6)
                toolTipText = "Nagyítás"
                addActionListener {
                    stepZoom(1.18)
                }
            }
            val btnOpenExternal = JButton("Megnyitás képnézegetőben").apply {
                font = Font("SansSerif", Font.PLAIN, 11)
                margin = Insets(1, 6, 1, 6)
                addActionListener {
                    val f = sourceFile
                    if (f != null && f.exists() && Desktop.isDesktopSupported()) {
                        try {
                            Desktop.getDesktop().open(f)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            add(zoomLabel)
            add(btnFit)
            add(btn100)
            add(btnZoomOut)
            add(btnZoomIn)
            add(btnOpenExternal)
        }

        // Ctrl + egérgörgő vagy sima görgő nagyítás támogatása
        canvas.addMouseWheelListener { e: MouseWheelEvent ->
            if (e.isControlDown || fitToWindow) {
                if (e.wheelRotation < 0) stepZoom(1.12) else stepZoom(0.89)
                e.consume()
            }
        }

        // Dupla kattintással váltás az Ablakhoz igazítás és a 100%-os natív méret között
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    fitToWindow = !fitToWindow
                    if (!fitToWindow) zoomFactor = 1.0
                    revalidateAndRepaint()
                }
            }
        })

        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun stepZoom(multiplier: Double) {
        val img = sourceImage ?: return
        if (fitToWindow) {
            val availW = max(50, canvas.width - 16)
            val availH = max(50, canvas.height - 16)
            zoomFactor = min(availW.toDouble() / img.width, availH.toDouble() / img.height)
            fitToWindow = false
        }
        zoomFactor = (zoomFactor * multiplier).coerceIn(0.25, 3.0)
        revalidateAndRepaint()
    }

    private fun revalidateAndRepaint() {
        canvas.revalidate()
        scrollPane.revalidate()
        canvas.repaint()
    }

    fun setImage(img: BufferedImage?, file: File? = null) {
        sourceImage = img
        sourceFile = file
        cachedScaledImage = null
        cachedPhysW = -1
        cachedPhysH = -1
        revalidateAndRepaint()
    }

    fun showMessage(msg: String) {
        sourceImage = null
        sourceFile = null
        cachedScaledImage = null
        emptyMessage = msg
        zoomLabel.text = ""
        revalidateAndRepaint()
    }

    /**
     * Többlépcsős (progressive) bicubic átméretezés pontosan a célzott fizikai (device) pixelméretre.
     * Megakadályozza az élek és feliratok elmosódását vagy recézettségét kicsinyítéskor.
     */
    private fun getOrCreateHighQualityScaled(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
        if (targetW == src.width && targetH == src.height) return src
        val cached = cachedScaledImage
        if (cached != null && cachedPhysW == targetW && cachedPhysH == targetH) {
            return cached
        }

        var current = src
        var curW = src.width
        var curH = src.height

        // Ha 50%-nál nagyobb mértékű kicsinyítés kell, lépcsőzetesen felezünk (multi-step downscaling)
        while (curW / 2 >= targetW && curH / 2 >= targetH) {
            curW = max(targetW, curW / 2)
            curH = max(targetH, curH / 2)
            val stepImg = BufferedImage(curW, curH, BufferedImage.TYPE_INT_ARGB)
            val g2 = stepImg.createGraphics()
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(current, 0, 0, curW, curH, null)
            g2.dispose()
            current = stepImg
        }

        // Végső lépés: Bicubic interpoláció a pontos fizikai pixelméretre
        val finalImg = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
        val g2 = finalImg.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.drawImage(current, 0, 0, targetW, targetH, null)
        g2.dispose()

        cachedScaledImage = finalImg
        cachedPhysW = targetW
        cachedPhysH = targetH
        return finalImg
    }
}

fun main() {
    launchGui()
}

fun launchGui() {
    SwingUtilities.invokeLater {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
        }
        val frame = MainGuiFrame()
        frame.isVisible = true
    }
}

class MainGuiFrame : JFrame("Universal Keyboard Analyzer - Grafikus Felület") {
    private val consoleTextArea = JTextArea().apply {
        isEditable = false
        font = Font("Consolas", Font.PLAIN, 12)
        background = Color(30, 30, 35)
        foreground = Color(220, 220, 220)
        caretColor = Color.WHITE
    }
    private val statusLabel = JLabel("Kész.")
    private val progressBar = JProgressBar().apply {
        isIndeterminate = false
        isVisible = false
        preferredSize = Dimension(180, 18)
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1360, 880)
        setLocationRelativeTo(null)
        layout = BorderLayout()

        redirectSystemStreams()

        val tabbedPane = JTabbedPane().apply {
            font = Font("SansSerif", Font.BOLD, 13)
            addTab("1. Korpusz elemző eszközök", createCorpusAnalyzerPanel())
            addTab("2. Layout elemző eszközök", createLayoutAnalyzerPanel())
            addTab("3. Fizikai effort modell", createEffortModelPanel())
            addTab("4. Layout generálás", createLayoutGenerationPanel())
            addTab("5. Generált layoutok böngészése", createGeneratedLayoutsBrowserPanel())
        }

        val consolePanel = JPanel(BorderLayout()).apply {
            border = TitledBorder("Konzol kimenet / Folyamatnapló")
            preferredSize = Dimension(1360, 200)
            val scroll = JScrollPane(consoleTextArea)
            add(scroll, BorderLayout.CENTER)
            val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2))
            val clearBtn = JButton("Napló törlése").apply {
                addActionListener { consoleTextArea.text = "" }
            }
            btnBar.add(clearBtn)
            add(btnBar, BorderLayout.SOUTH)
        }

        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, tabbedPane, consolePanel).apply {
            resizeWeight = 0.76
            dividerLocation = 620
        }

        val statusBar = JPanel(BorderLayout()).apply {
            border = EmptyBorder(4, 10, 4, 10)
            add(statusLabel, BorderLayout.WEST)
            add(progressBar, BorderLayout.EAST)
        }

        add(mainSplit, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun redirectSystemStreams() {
        val originalOut = System.out
        val originalErr = System.err

        val guiOut = object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(b: Int) {
                originalOut.write(b)
                synchronized(buffer) {
                    buffer.write(b)
                    if (b == '\n'.code) {
                        flushBuffer()
                    }
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                originalOut.write(b, off, len)
                synchronized(buffer) {
                    buffer.write(b, off, len)
                    if (b.sliceArray(off until off + len).contains('\n'.code.toByte())) {
                        flushBuffer()
                    }
                }
            }

            private fun flushBuffer() {
                val text = buffer.toString(StandardCharsets.UTF_8)
                buffer.reset()
                SwingUtilities.invokeLater {
                    consoleTextArea.append(text)
                    consoleTextArea.caretPosition = consoleTextArea.document.length
                }
            }
        }

        System.setOut(PrintStream(guiOut, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(guiOut, true, StandardCharsets.UTF_8))
    }

    private fun runInBackground(taskName: String, onCompleteUI: (() -> Unit)? = null, block: () -> Unit) {
        statusLabel.text = "Folyamatban: $taskName..."
        progressBar.isVisible = true
        progressBar.isIndeterminate = true

        Thread {
            try {
                block()
                SwingUtilities.invokeLater {
                    statusLabel.text = "Befejezve: $taskName"
                    progressBar.isIndeterminate = false
                    progressBar.isVisible = false
                    onCompleteUI?.invoke()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                SwingUtilities.invokeLater {
                    statusLabel.text = "Hiba történt: ${e.message}"
                    progressBar.isIndeterminate = false
                    progressBar.isVisible = false
                    JOptionPane.showMessageDialog(
                        this,
                        "Hiba a művelet során:\n${e.message}",
                        "Hiba",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }.start()
    }

    // --- SEGÉDFÜGGVÉNYEK MAPPÁK LISTÁZÁSÁHOZ ---
    private fun listProcessableCorpora(): List<String> {
        val dir = File("processable_texts")
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", true) }
            ?.map { it.name }
            ?.sorted() ?: emptyList()
    }

    private fun listAnalyzedCorpora(): List<String> {
        val dir = File("analysis_results")
        val fromResults = if (dir.exists() && dir.isDirectory) {
            dir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
        } else emptyList()
        val fromProcessable = listProcessableCorpora()
        return (fromResults + fromProcessable).distinct().sorted()
    }

    private fun listKeyboardsInFolder(folder: String): List<String> {
        val baseDir = File(folder)
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        return baseDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".yaml", true) || !it.name.contains(".")) }
            .map { it.relativeTo(baseDir).path.replace('\\', '/') }
            .sorted()
            .toList()
    }

    // =========================================================================
    // 1. MENÜ: KORPUSZ ELEMZŐ ESZKÖZÖK
    // =========================================================================
    private fun createCorpusAnalyzerPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10)).apply {
            border = EmptyBorder(10, 10, 10, 10)
        }

        val corpusCombo = JComboBox<String>()
        val resultFilesCombo = JComboBox<String>()
        val resultFileViewer = JTextArea().apply {
            isEditable = false
            font = Font("Consolas", Font.PLAIN, 13)
        }

        fun refreshResultFiles() {
            val selectedCorpus = corpusCombo.selectedItem as? String ?: return
            resultFilesCombo.removeAllItems()
            val dir = File("analysis_results/$selectedCorpus")
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", true) }
                    ?.map { it.name }
                    ?.sorted()
                    ?.forEach { resultFilesCombo.addItem(it) }
            }
        }

        fun refreshCorpora() {
            val current = corpusCombo.selectedItem as? String
            corpusCombo.removeAllItems()
            listProcessableCorpora().forEach { corpusCombo.addItem(it) }
            if (current != null) corpusCombo.selectedItem = current
            refreshResultFiles()
        }

        corpusCombo.addActionListener { refreshResultFiles() }
        resultFilesCombo.addActionListener {
            val selectedCorpus = corpusCombo.selectedItem as? String ?: return@addActionListener
            val selectedFile = resultFilesCombo.selectedItem as? String ?: return@addActionListener
            val f = File("analysis_results/$selectedCorpus/$selectedFile")
            if (f.exists() && f.isFile) {
                resultFileViewer.text = f.useLines { lines -> lines.take(1000).joinToString("\n") }
                resultFileViewer.caretPosition = 0
            }
        }

        // Bal oldali vezérlőpult
        val leftControl = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(420, 500)
        }

        val selectGroup = JPanel(GridBagLayout()).apply {
            border = TitledBorder("1. Korpusz kiválasztása (processable_texts)")
            val gbc = GridBagConstraints().apply {
                insets = Insets(4, 4, 4, 4)
                fill = GridBagConstraints.HORIZONTAL
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0
            add(corpusCombo, gbc)
            val refreshBtn = JButton("Frissítés").apply {
                addActionListener { refreshCorpora() }
            }
            gbc.gridx = 1; gbc.weightx = 0.0
            add(refreshBtn, gbc)
        }

        val analysisGroup = JPanel(GridBagLayout()).apply {
            border = TitledBorder("2. Egy vagy az összes korpusz elemzés futtatása")
            val gbc = GridBagConstraints().apply {
                insets = Insets(5, 5, 5, 5)
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridx = 0
            }

            val btnAll = JButton("ÖSSZES KORPUSZ ELEMZÉS FUTTATÁSA (Complete Corpus Analysis)").apply {
                font = font.deriveFont(Font.BOLD, 13f)
                background = Color(40, 110, 60)
                foreground = Color.BLACK
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    runInBackground("Teljes korpusz elemzés ($corpus)", { refreshResultFiles() }) {
                        completeCorpusAnalysis(corpus)
                    }
                }
            }
            gbc.gridy = 0
            add(btnAll, gbc)

            gbc.gridy = 1
            add(JSeparator(), gbc)

            val btnLetter = JButton("Karaktergyakoriság elemzés (Letter Frequency)").apply {
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    runInBackground("Karaktergyakoriság ($corpus)", { refreshResultFiles() }) {
                        letterfrequency(corpus)
                    }
                }
            }
            gbc.gridy = 2
            add(btnLetter, gbc)

            val btnBigram = JButton("Bigram elemzés (Bigram Frequency)").apply {
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    runInBackground("Bigram elemzés ($corpus)", { refreshResultFiles() }) {
                        bigramanalysis(corpus)
                    }
                }
            }
            gbc.gridy = 3
            add(btnBigram, gbc)

            val btnTrigram = JButton("Trigram elemzés (Trigram Frequency)").apply {
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    runInBackground("Trigram elemzés ($corpus)", { refreshResultFiles() }) {
                        trigramanalysis(corpus)
                    }
                }
            }
            gbc.gridy = 4
            add(btnTrigram, gbc)

            val btnSpaceTrigram = JButton("Szóközös trigram elemzés (Space Included Trigrams)").apply {
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    runInBackground("Szóközös trigram elemzés ($corpus)", { refreshResultFiles() }) {
                        spaceIncludedTrigramAnalysis(corpus)
                    }
                }
            }
            gbc.gridy = 5
            add(btnSpaceTrigram, gbc)

            val wordFreqPanel = JPanel(BorderLayout(5, 0))
            val wordLimitSpinner = JSpinner(SpinnerNumberModel(100, 10, 100000, 50))
            val btnWordFreq = JButton("Szógyakoriság (Top N szó):").apply {
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    val limit = (wordLimitSpinner.value as Number).toInt()
                    runInBackground("Szógyakoriság Top $limit ($corpus)", { refreshResultFiles() }) {
                        calculatewordfrequency(corpus, limit)
                    }
                }
            }
            wordFreqPanel.add(btnWordFreq, BorderLayout.CENTER)
            wordFreqPanel.add(wordLimitSpinner, BorderLayout.EAST)
            gbc.gridy = 6
            add(wordFreqPanel, gbc)
        }

        // Előfeldolgozó / Korpusz építő segédeszközök
        val toolsGroup = JPanel(GridBagLayout()).apply {
            border = TitledBorder("3. Korpusz előkészítő segédeszközök")
            val gbc = GridBagConstraints().apply {
                insets = Insets(4, 4, 4, 4)
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridx = 0
            }

            val btnPreprocess = JButton("Nyers fájl előfeldolgozása (raw_texts -> processable_texts)").apply {
                addActionListener {
                    val rawDir = File("raw_texts")
                    val rawFiles = rawDir.listFiles { f -> f.isFile }?.map { it.name }?.toTypedArray() ?: emptyArray()
                    if (rawFiles.isEmpty()) {
                        JOptionPane.showMessageDialog(this@MainGuiFrame, "A raw_texts mappa üres vagy nem létezik.")
                        return@addActionListener
                    }
                    val chosen = JOptionPane.showInputDialog(
                        this@MainGuiFrame,
                        "Válaszd ki az előfeldolgozandó fájlt a raw_texts mappából:",
                        "Preprocess",
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        rawFiles,
                        rawFiles.first()
                    ) as? String ?: return@addActionListener

                    runInBackground("Előfeldolgozás ($chosen)", { refreshCorpora() }) {
                        stripIndicesAndSave(File("raw_texts/$chosen"), File("processable_texts/$chosen"))
                    }
                }
            }
            gbc.gridy = 0
            add(btnPreprocess, gbc)

            val btnRandomLines = JButton("Véletlen sorok mintavételezése (Random Lines)").apply {
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    val countStr = JOptionPane.showInputDialog(this@MainGuiFrame, "Hány véletlen sort válasszunk ki?", "1000")
                        ?: return@addActionListener
                    val count = countStr.toIntOrNull() ?: return@addActionListener
                    runInBackground("Véletlen $count sor ($corpus)", { refreshCorpora() }) {
                        createRandomSubset("processable_texts/$corpus", count)
                    }
                }
            }
            gbc.gridy = 1
            add(btnRandomLines, gbc)

            val btnBuildCorpora = JButton("Korpuszok összefűzése (Build Corpora)").apply {
                addActionListener {
                    val corpora = listProcessableCorpora()
                    if (corpora.isEmpty()) return@addActionListener
                    val listComp = JList(corpora.toTypedArray()).apply {
                        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                    }
                    val outField = JTextField("merged_corpus.txt")
                    val dialogPanel = JPanel(BorderLayout(6, 6)).apply {
                        add(JLabel("Válaszd ki az összefűzendő korpuszokat (Ctrl/Shift + kattintás):"), BorderLayout.NORTH)
                        add(JScrollPane(listComp).apply { preferredSize = Dimension(350, 180) }, BorderLayout.CENTER)
                        val bottom = JPanel(BorderLayout(4, 4)).apply {
                            add(JLabel("Új korpusz neve:"), BorderLayout.WEST)
                            add(outField, BorderLayout.CENTER)
                        }
                        add(bottom, BorderLayout.SOUTH)
                    }
                    val res = JOptionPane.showConfirmDialog(
                        this@MainGuiFrame,
                        dialogPanel,
                        "Korpuszok összefűzése",
                        JOptionPane.OK_CANCEL_OPTION
                    )
                    if (res == JOptionPane.OK_OPTION) {
                        val selected = listComp.selectedValuesList
                        val outName = outField.text.trim().let { if (it.endsWith(".txt")) it else "$it.txt" }
                        if (selected.isNotEmpty() && outName.isNotBlank()) {
                            runInBackground("Korpuszok összefűzése -> $outName", { refreshCorpora() }) {
                                buildcorpora(selected, outName)
                            }
                        }
                    }
                }
            }
            gbc.gridy = 2
            add(btnBuildCorpora, gbc)
        }

        leftControl.add(selectGroup)
        leftControl.add(Box.createVerticalStrut(8))
        leftControl.add(analysisGroup)
        leftControl.add(Box.createVerticalStrut(8))
        leftControl.add(toolsGroup)
        leftControl.add(Box.createVerticalGlue())

        // Jobb oldali eredményböngésző
        val rightViewer = JPanel(BorderLayout(6, 6)).apply {
            border = TitledBorder("Elemzési eredményfájlok megtekintése (analysis_results/<korpusz>)")
            val topBar = JPanel(BorderLayout(6, 6))
            topBar.add(JLabel("Eredményfájl:"), BorderLayout.WEST)
            topBar.add(resultFilesCombo, BorderLayout.CENTER)
            val reloadBtn = JButton("Újratöltés").apply {
                addActionListener { refreshResultFiles() }
            }
            topBar.add(reloadBtn, BorderLayout.EAST)
            add(topBar, BorderLayout.NORTH)
            add(JScrollPane(resultFileViewer), BorderLayout.CENTER)
        }

        refreshCorpora()

        panel.add(leftControl, BorderLayout.WEST)
        panel.add(rightViewer, BorderLayout.CENTER)
        return panel
    }

    // =========================================================================
    // 2. MENÜ: LAYOUT ELEMZŐ ESZKÖZÖK
    // =========================================================================
    private fun createLayoutAnalyzerPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10)).apply {
            border = EmptyBorder(10, 10, 10, 10)
        }

        val folderGroup = ButtonGroup()
        val rbKeyboards = JRadioButton("keyboards mappa", true)
        val rbGenerated = JRadioButton("generatedkeyboards mappa", false)
        folderGroup.add(rbKeyboards)
        folderGroup.add(rbGenerated)

        fun currentFolder(): String = if (rbKeyboards.isSelected) "keyboards" else "generatedkeyboards"

        val layoutCombo = JComboBox<String>()
        val corpusCombo = JComboBox<String>()

        fun refreshLists() {
            val prevLayout = layoutCombo.selectedItem as? String
            layoutCombo.removeAllItems()
            listKeyboardsInFolder(currentFolder()).forEach { layoutCombo.addItem(it) }
            if (prevLayout != null) layoutCombo.selectedItem = prevLayout

            val prevCorpus = corpusCombo.selectedItem as? String
            corpusCombo.removeAllItems()
            listAnalyzedCorpora().forEach { corpusCombo.addItem(it) }
            if (prevCorpus != null) corpusCombo.selectedItem = prevCorpus
        }

        rbKeyboards.addActionListener { refreshLists() }
        rbGenerated.addActionListener { refreshLists() }

        // Eredmény táblázat és mátrix nézet
        val statsTableModel = object : DefaultTableModel(arrayOf("Metrika", "Érték"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        val statsTable = JTable(statsTableModel).apply {
            rowHeight = 22
            font = Font("SansSerif", Font.PLAIN, 13)
            tableHeader.font = Font("SansSerif", Font.BOLD, 13)
        }

        val heatmapPanel = HeatmapImagePanel("Válassz egy layoutot és futtasd az elemzést.")

        fun updateStatsTable(res: LayoutAnalysisResult) {
            statsTableModel.rowCount = 0
            statsTableModel.addRow(arrayOf("Layout neve", res.layoutName))
            statsTableModel.addRow(arrayOf("Összesített Effort (Total Effort)", String.format("%.4f", res.totalEffort)))
            statsTableModel.addRow(arrayOf("Char Reach Effort", String.format("%.4f", res.charReachEffort)))
            statsTableModel.addRow(arrayOf("Base Gravity Effort", String.format("%.4f", res.baseGravityEffort)))
            statsTableModel.addRow(arrayOf("Kéz arány (Bal / Jobb)", String.format("%.2f%% / %.2f%%", res.handBalanceLeft, res.handBalanceRight)))
            statsTableModel.addRow(arrayOf("Alapsor (Home Row) arány", String.format("%.2f%%", res.homeRowPercentage)))
            for ((idx, rPct) in res.rowPercentages.withIndex()) {
                statsTableModel.addRow(arrayOf("  - $idx. sor használata", String.format("%.2f%%", rPct)))
            }
            statsTableModel.addRow(arrayOf("SFB (Same Finger Bigram)", String.format("%.4f%%", res.sfbPercentage)))
            statsTableModel.addRow(arrayOf("Space SFS-1 büntetőpont", String.format("%.4f", res.spaceSfs1Penalty)))
            statsTableModel.addRow(arrayOf("Pinky Off (Kisujj alapgomb kívül)", String.format("%.4f%%", res.pinkyOffPercentage)))
            statsTableModel.addRow(arrayOf("Lateral Stretch Bigram (LSB)", String.format("%.4f%%", res.lateralStretchPercentage)))
            statsTableModel.addRow(arrayOf("Pinky Stretch Bigram (PSB)", String.format("%.4f%%", res.pinkystretchPercentage)))
            statsTableModel.addRow(arrayOf("Full Scissor Bigram (FSB)", String.format("%.4f%%", res.fullScissorPercentage)))
            statsTableModel.addRow(arrayOf("Half Scissor Bigram (HSB)", String.format("%.4f%%", res.halfScissorPercentage)))
            statsTableModel.addRow(arrayOf("Alternálás (1-1-1)", String.format("%.4f%%", res.alternationPercentage)))
            statsTableModel.addRow(arrayOf("2-Roll (2-1 és 1-2)", String.format("%.4f%%", res.rollPercentage)))
            statsTableModel.addRow(arrayOf("3-Roll (Egykezes gördülés)", String.format("%.4f%%", res.threerollPercentage)))
            statsTableModel.addRow(arrayOf("Redirect (Egykezes irányváltás)", String.format("%.4f%%", res.redirectPercentage)))
        }

        fun renderPreviewHeatmap(layoutFile: File) {
            try {
                val layoutData = LayoutParser.parseLayout(layoutFile)
                val tempImg = File.createTempFile("preview_heatmap_", ".png")
                tempImg.deleteOnExit()
                renderCharEffortlayout(layoutData, tempImg.absolutePath)
                val img = ImageIO.read(tempImg)
                if (img != null) {
                    heatmapPanel.setImage(img, tempImg)
                }
            } catch (e: Exception) {
                heatmapPanel.showMessage("Hőtérkép hiba: ${e.message}")
            }
        }

        // Bal oldali vezérlőpult
        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(430, 600)
        }

        val sourceBox = JPanel(GridBagLayout()).apply {
            border = TitledBorder("1. Layout és Korpusz kiválasztása")
            val gbc = GridBagConstraints().apply {
                insets = Insets(4, 4, 4, 4)
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
            }
            val radioPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(rbKeyboards)
                add(rbGenerated)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
            add(radioPanel, gbc)

            gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.0
            add(JLabel("Layout:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(layoutCombo, gbc)

            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
            add(JLabel("Korpusz:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(corpusCombo, gbc)

            val refreshBtn = JButton("Listák frissítése").apply {
                addActionListener { refreshLists() }
            }
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2
            add(refreshBtn, gbc)
        }

        val actionsBox = JPanel(GridBagLayout()).apply {
            border = TitledBorder("2. Elemzések futtatása")
            val gbc = GridBagConstraints().apply {
                insets = Insets(4, 4, 4, 4)
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridx = 0
            }

            val btnComplete = JButton("ÖSSZES ELEMZÉS FUTTATÁSA (Complete Layout Analysis)").apply {
                font = font.deriveFont(Font.BOLD, 13f)
                addActionListener {
                    val layoutName = layoutCombo.selectedItem as? String ?: return@addActionListener
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    val folder = currentFolder()
                    runInBackground("Teljes layout elemzés ($layoutName)") {
                        val res = completeLayoutAnalysis(layoutName, corpus, folder = folder, verbose = true)
                        val file = LayoutParser.resolveLayoutFile(layoutName, folder)
                        SwingUtilities.invokeLater {
                            if (res != null) updateStatsTable(res)
                            if (file != null) renderPreviewHeatmap(file)
                        }
                    }
                }
            }
            gbc.gridy = 0; gbc.gridwidth = 2
            add(btnComplete, gbc)

            gbc.gridy = 1
            add(JSeparator(), gbc)

            // Egyedi elemző gombok 2 oszlopban
            gbc.gridwidth = 1
            val singleAnalyses = listOf<Pair<String, (String, String, String) -> Unit>>(
                "SFB elemzés (kba)" to { l, c, f -> runSimpleSfbAnalysis(l, c, f) },
                "Alternálás (laa)" to { l, c, f -> runAlternationAnalysis(l, c, f) },
                "2-Roll elemzés (l2ra)" to { l, c, f -> runRollAnalysis(l, c, f) },
                "3-Roll elemzés (l3ra)" to { l, c, f -> run3RollAnalysis(l, c, f) },
                "Redirect elemzés (lra)" to { l, c, f -> runRedirectAnalysis(l, c, f) },
                "Space SFS-1 (sfs1)" to { l, c, f -> runSpaceSfs1Analysis(l, c, f) },
                "Kéz egyensúly (hb)" to { l, c, f -> runHandBalanceAnalysis(l, c, f) },
                "Sorok terhelése (ru)" to { l, c, f -> runRowUsageAnalysis(l, c, f) },
                "Pinky Off (po)" to { l, c, f -> runPinkyOffAnalysis(l, c, f) },
                "Lateral Stretch (lsb)" to { l, c, f -> runLateralStretchAnalysis(l, c, f) },
                "Pinky Stretch (psb)" to { l, c, f -> runPinkyStretchAnalysis(l, c, f) },
                "Full Scissor (fsb)" to { l, c, f -> runFullScissorAnalysis(l, c, f) },
                "Half Scissor (hsb)" to { l, c, f -> runHalfScissorAnalysis(l, c, f) }
            )

            singleAnalyses.forEachIndexed { idx, (label, runner) ->
                gbc.gridx = idx % 2
                gbc.gridy = 2 + (idx / 2)
                val btn = JButton(label).apply {
                    addActionListener {
                        val layoutName = layoutCombo.selectedItem as? String ?: return@addActionListener
                        val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                        val folder = currentFolder()
                        runInBackground("$label ($layoutName)") {
                            runner(layoutName, corpus, folder)
                            val file = LayoutParser.resolveLayoutFile(layoutName, folder)
                            if (file != null) {
                                SwingUtilities.invokeLater { renderPreviewHeatmap(file) }
                            }
                        }
                    }
                }
                add(btn, gbc)
            }

            gbc.gridx = 0; gbc.gridy = 9; gbc.gridwidth = 2
            add(JSeparator(), gbc)

            val btnCsv = JButton("CSV Összesítő táblázat generálása a korpuszhoz (gcsv)").apply {
                addActionListener {
                    val corpus = corpusCombo.selectedItem as? String ?: return@addActionListener
                    val clean = if (corpus.endsWith(".txt")) corpus else "$corpus.txt"
                    runInBackground("CSV összesítő generálása ($clean)") {
                        generateSummaryCsv(clean)
                    }
                }
            }
            gbc.gridy = 10
            add(btnCsv, gbc)
        }

        leftPanel.add(sourceBox)
        leftPanel.add(Box.createVerticalStrut(8))
        leftPanel.add(actionsBox)
        leftPanel.add(Box.createVerticalGlue())

        // Jobb oldali megjelenítő (Hőtérkép + Táblázat)
        val rightSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JPanel(BorderLayout()).apply {
                border = TitledBorder("Billentyűzetkiosztás és Hőtérkép")
                add(heatmapPanel, BorderLayout.CENTER)
            },
            JPanel(BorderLayout()).apply {
                border = TitledBorder("Elemzési statisztikák")
                add(JScrollPane(statsTable), BorderLayout.CENTER)
            }
        ).apply {
            resizeWeight = 0.58
            dividerLocation = 380
        }

        refreshLists()

        panel.add(leftPanel, BorderLayout.WEST)
        panel.add(rightSplit, BorderLayout.CENTER)
        return panel
    }

    // =========================================================================
    // 3. MENÜ: FIZIKAI EFFORT MODELL DOLGAI
    // =========================================================================
    private fun createEffortModelPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10)).apply {
            border = EmptyBorder(10, 10, 10, 10)
        }

        // 1. Ujj (Finger) paraméterek táblázata (KeyboardHalf.kt)
        val fingerTableModel = object : DefaultTableModel(
            arrayOf("Ujj (Finger)", "baseEffort (Pf)", "homeOffsetX", "homeOffsetY", "maxComfortReachY", "maxComfortReachX"),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int) = column > 0
        }

        fun loadFingerTable() {
            fingerTableModel.rowCount = 0
            for (f in Finger.entries) {
                fingerTableModel.addRow(
                    arrayOf(
                        f.name,
                        f.baseEffort.toString(),
                        f.homeOffsetX.toString(),
                        f.homeOffsetY.toString(),
                        f.maxComfortReachY.toString(),
                        f.maxComfortReachX.toString()
                    )
                )
            }
        }
        loadFingerTable()

        val fingerTable = JTable(fingerTableModel).apply {
            rowHeight = 24
            font = Font("SansSerif", Font.PLAIN, 13)
            tableHeader.font = Font("SansSerif", Font.BOLD, 12)
        }

        // 2. EffortLayoutGenerator globális paraméterei
        val spLateralPx = JSpinner(SpinnerNumberModel(EffortGeneratorSettings.lateralPenaltyPx, 0.0, 20.0, 0.1))
        val spDistancePd = JSpinner(SpinnerNumberModel(EffortGeneratorSettings.distancePenaltyPd, 0.0, 20.0, 0.05))
        val spActMargin = JSpinner(SpinnerNumberModel(EffortGeneratorSettings.activationMargin, 0.0, 2.0, 0.05))
        val spWallY = JSpinner(SpinnerNumberModel(EffortGeneratorSettings.biomechanicalWallMultiplierY, 0.0, 200.0, 1.0))
        val spWallX = JSpinner(SpinnerNumberModel(EffortGeneratorSettings.biomechanicalWallMultiplierX, 0.0, 200.0, 0.5))
        val cbBoxInKeySpace = JCheckBox("boxInKeySpace (Aktivációs doboz a gomb terében)", EffortGeneratorSettings.boxInKeySpace)
        val comboStaticOffsets = JComboBox(arrayOf("Automatikus (Row/Matrix = igen, ColStagger = nem)", "Mindig bekapcsolva (true)", "Mindig kikapcsolva (false)"))

        fun applyGlobalEffortSettings() {
            if (fingerTable.isEditing) fingerTable.cellEditor.stopCellEditing()
            for (r in 0 until fingerTableModel.rowCount) {
                val fName = fingerTableModel.getValueAt(r, 0).toString()
                val finger = Finger.valueOf(fName)
                finger.baseEffort = fingerTableModel.getValueAt(r, 1).toString().replace(',', '.').toDoubleOrNull() ?: finger.baseEffort
                finger.homeOffsetX = fingerTableModel.getValueAt(r, 2).toString().replace(',', '.').toDoubleOrNull() ?: finger.homeOffsetX
                finger.homeOffsetY = fingerTableModel.getValueAt(r, 3).toString().replace(',', '.').toDoubleOrNull() ?: finger.homeOffsetY
                finger.maxComfortReachY = fingerTableModel.getValueAt(r, 4).toString().replace(',', '.').toDoubleOrNull() ?: finger.maxComfortReachY
                finger.maxComfortReachX = fingerTableModel.getValueAt(r, 5).toString().replace(',', '.').toDoubleOrNull() ?: finger.maxComfortReachX
            }
            EffortGeneratorSettings.lateralPenaltyPx = (spLateralPx.value as Number).toDouble()
            EffortGeneratorSettings.distancePenaltyPd = (spDistancePd.value as Number).toDouble()
            EffortGeneratorSettings.activationMargin = (spActMargin.value as Number).toDouble()
            EffortGeneratorSettings.biomechanicalWallMultiplierY = (spWallY.value as Number).toDouble()
            EffortGeneratorSettings.biomechanicalWallMultiplierX = (spWallX.value as Number).toDouble()
            EffortGeneratorSettings.boxInKeySpace = cbBoxInKeySpace.isSelected
            EffortGeneratorSettings.overrideApplyStaticFingerOffsets = when (comboStaticOffsets.selectedIndex) {
                1 -> true
                2 -> false
                else -> null
            }
        }

        // 3. KeyboardHalf & Egyedi tesztelés paraméterei
        val templateCombo = JComboBox<String>().apply {
            listKeyboardsInFolder("keyboards").forEach { addItem(it) }
        }
        val staggerCombo = JComboBox(StaggerType.entries.toTypedArray()).apply {
            selectedItem = StaggerType.COLUMNAR_STAGGER
        }
        val generatorModelCombo = JComboBox(arrayOf("CustomKeyboardEffortGenerator (Biomechanikai fallal)", "ColemakDhEffortGenerator (Klasszikus)"))
        val spNumRows = JSpinner(SpinnerNumberModel(5, 2, 8, 1))
        val spHomeRow = JSpinner(SpinnerNumberModel(2, 0, 7, 1))
        val spAngle = JSpinner(SpinnerNumberModel(0.0, -45.0, 45.0, 1.0))
        val cbOptimalFinger = JCheckBox("Optimal Finger Assignment", false)

        val tfLeftOffsets = JTextField("0.50, 0.50, 0.25, 0.00, 0.25, 0.38")
        val tfLeftFingers = JTextField("P, P, R, M, I, I")
        val tfLeftAnchors = JTextField("1, 1, 2, 3, 4, 4")

        val tfRightOffsets = JTextField("0.38, 0.25, 0.00, 0.25, 0.50, 0.50")
        val tfRightFingers = JTextField("I, I, M, R, P, P")
        val tfRightAnchors = JTextField("1, 1, 2, 3, 4, 4")

        val heatmapPreviewPanel = HeatmapImagePanel("Kattints a 'Hőtérkép és Effort Mátrix Generálása' gombra az előnézethez.")
        val matrixOutputArea = JTextArea(8, 40).apply {
            isEditable = false
            font = Font("Consolas", Font.PLAIN, 12)
        }

        fun loadFromTemplateYaml() {
            val selected = templateCombo.selectedItem as? String ?: return
            val file = LayoutParser.resolveLayoutFile(selected, "keyboards") ?: return
            try {
                val data = LayoutParser.parseLayout(file)
                val hw = data.hardwareYaml
                staggerCombo.selectedItem = when (hw.staggerType.lowercase()) {
                    "columnar_stagger" -> StaggerType.COLUMNAR_STAGGER
                    "row_stagger" -> StaggerType.ROW_STAGGER
                    else -> StaggerType.MATRIX
                }
                spNumRows.value = hw.numRows
                spHomeRow.value = hw.homeRowIndex
                spAngle.value = hw.angleOfApproachDegrees
                cbOptimalFinger.isSelected = hw.optimalFingerAssignment
                tfLeftOffsets.text = hw.left.offsets.joinToString(", ")
                tfLeftFingers.text = hw.left.fingers.joinToString(", ")
                tfLeftAnchors.text = hw.left.anchors.joinToString(", ")
                tfRightOffsets.text = hw.right.offsets.joinToString(", ")
                tfRightFingers.text = hw.right.fingers.joinToString(", ")
                tfRightAnchors.text = hw.right.anchors.joinToString(", ")
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this, "Hiba a YAML sablon betöltésekor: ${e.message}")
            }
        }

        val leftConfigPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(560, 650)
        }

        val fingerBox = JPanel(BorderLayout(4, 4)).apply {
            border = TitledBorder("1. Ujj (Finger) anatómiai paraméterek (KeyboardHalf.kt)")
            preferredSize = Dimension(540, 165)
            add(JScrollPane(fingerTable), BorderLayout.CENTER)
        }

        val genParamsBox = JPanel(GridBagLayout()).apply {
            border = TitledBorder("2. EffortLayoutGenerator paraméterei (EffortLayoutGenerator.kt)")
            val gbc = GridBagConstraints().apply {
                insets = Insets(3, 5, 3, 5)
                fill = GridBagConstraints.HORIZONTAL
            }
            fun addRow(row: Int, label: String, comp: Component) {
                gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.6
                add(JLabel(label), gbc)
                gbc.gridx = 1; gbc.weightx = 0.4
                add(comp, gbc)
            }
            addRow(0, "Oldalirányú büntetés (lateralPenaltyPx):", spLateralPx)
            addRow(1, "Távolság büntetés (distancePenaltyPd):", spDistancePd)
            addRow(2, "Aktivációs margó (activationMargin):", spActMargin)
            addRow(3, "Biomechanikai fal Y szorzó (alap: 25.0):", spWallY)
            addRow(4, "Biomechanikai fal X szorzó (alap: 5.5):", spWallX)
            addRow(5, "Statikus ujj-offsetek (applyStaticFingerOffsets):", comboStaticOffsets)
            gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2
            add(cbBoxInKeySpace, gbc)

            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            val btnApplyGlobal = JButton("Alkalmazás a globális modellre").apply {
                font = font.deriveFont(Font.BOLD)
                addActionListener {
                    applyGlobalEffortSettings()
                    println("Fizikai effort modell paraméterei frissítve a memóriában.")
                    JOptionPane.showMessageDialog(this@MainGuiFrame, "Az új Finger és EffortGenerator paraméterek érvényesítve lettek!")
                }
            }
            val btnResetGlobal = JButton("Alapértékek visszaállítása").apply {
                addActionListener {
                    Finger.resetDefaults()
                    EffortGeneratorSettings.resetDefaults()
                    loadFingerTable()
                    spLateralPx.value = EffortGeneratorSettings.lateralPenaltyPx
                    spDistancePd.value = EffortGeneratorSettings.distancePenaltyPd
                    spActMargin.value = EffortGeneratorSettings.activationMargin
                    spWallY.value = EffortGeneratorSettings.biomechanicalWallMultiplierY
                    spWallX.value = EffortGeneratorSettings.biomechanicalWallMultiplierX
                    cbBoxInKeySpace.isSelected = EffortGeneratorSettings.boxInKeySpace
                    comboStaticOffsets.selectedIndex = 0
                    println("Fizikai effort modell alapértékei visszaállítva.")
                }
            }
            btnPanel.add(btnResetGlobal)
            btnPanel.add(btnApplyGlobal)
            gbc.gridy = 7
            add(btnPanel, gbc)
        }

        val hwParamsBox = JPanel(GridBagLayout()).apply {
            border = TitledBorder("3. KeyboardHalf fizikai geometria paraméterei és Hőtérkép generálás")
            val gbc = GridBagConstraints().apply {
                insets = Insets(3, 4, 3, 4)
                fill = GridBagConstraints.HORIZONTAL
            }

            val topLoader = JPanel(BorderLayout(4, 0))
            topLoader.add(templateCombo, BorderLayout.CENTER)
            val btnLoadYaml = JButton("Betöltés YAML-ből").apply {
                addActionListener { loadFromTemplateYaml() }
            }
            topLoader.add(btnLoadYaml, BorderLayout.EAST)

            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.35
            add(JLabel("Sablon YAML:"), gbc)
            gbc.gridx = 1; gbc.weightx = 0.65
            add(topLoader, gbc)

            gbc.gridx = 0; gbc.gridy = 1
            add(JLabel("Generátor típus:"), gbc)
            gbc.gridx = 1
            add(generatorModelCombo, gbc)

            gbc.gridx = 0; gbc.gridy = 2
            add(JLabel("Stagger típus (StaggerType):"), gbc)
            gbc.gridx = 1
            add(staggerCombo, gbc)

            val rowHomePanel = JPanel(GridLayout(1, 4, 4, 0)).apply {
                add(JLabel("numRows:"))
                add(spNumRows)
                add(JLabel("homeRow:"))
                add(spHomeRow)
            }
            gbc.gridx = 0; gbc.gridy = 3
            add(JLabel("Sorok és Alapsor index:"), gbc)
            gbc.gridx = 1
            add(rowHomePanel, gbc)

            val angleOptPanel = JPanel(BorderLayout(6, 0)).apply {
                add(spAngle, BorderLayout.WEST)
                add(cbOptimalFinger, BorderLayout.CENTER)
            }
            gbc.gridx = 0; gbc.gridy = 4
            add(JLabel("Csuklószög (fok):"), gbc)
            gbc.gridx = 1
            add(angleOptPanel, gbc)

            gbc.gridx = 0; gbc.gridy = 5
            add(JLabel("Bal kéz offsets:"), gbc)
            gbc.gridx = 1
            add(tfLeftOffsets, gbc)

            gbc.gridx = 0; gbc.gridy = 6
            add(JLabel("Bal kéz fingers (P,R,M,I,T):"), gbc)
            gbc.gridx = 1
            add(tfLeftFingers, gbc)

            gbc.gridx = 0; gbc.gridy = 7
            add(JLabel("Bal kéz anchors:"), gbc)
            gbc.gridx = 1
            add(tfLeftAnchors, gbc)

            gbc.gridx = 0; gbc.gridy = 8
            add(JLabel("Jobb kéz offsets:"), gbc)
            gbc.gridx = 1
            add(tfRightOffsets, gbc)

            gbc.gridx = 0; gbc.gridy = 9
            add(JLabel("Jobb kéz fingers (I,M,R,P,T):"), gbc)
            gbc.gridx = 1
            add(tfRightFingers, gbc)

            gbc.gridx = 0; gbc.gridy = 10
            add(JLabel("Jobb kéz anchors:"), gbc)
            gbc.gridx = 1
            add(tfRightAnchors, gbc)

            val btnRender = JButton("Hőtérkép és Effort Mátrix Generálása").apply {
                font = font.deriveFont(Font.BOLD, 13f)
                addActionListener {
                    try {
                        applyGlobalEffortSettings()
                        val stagger = staggerCombo.selectedItem as StaggerType
                        val numRows = (spNumRows.value as Number).toInt()
                        val homeRow = (spHomeRow.value as Number).toInt()
                        val angle = (spAngle.value as Number).toDouble()
                        val optFinger = cbOptimalFinger.isSelected

                        fun parseFingerCode(code: String): Finger = when (code.uppercase()) {
                            "P", "PINKY" -> Finger.PINKY
                            "R", "RING" -> Finger.RING
                            "M", "MIDDLE" -> Finger.MIDDLE
                            "I", "INDEX" -> Finger.INDEX
                            "T", "THUMB" -> Finger.THUMB
                            else -> throw IllegalArgumentException("Ismeretlen ujj kód: '$code' (Használj P, R, M, I, T kódokat)")
                        }

                        val leftOffsets = tfLeftOffsets.text.split(",").map { it.trim().toDouble() }
                        val leftFingers = tfLeftFingers.text.split(",").map { parseFingerCode(it.trim()) }
                        val leftAnchors = tfLeftAnchors.text.split(",").map { it.trim().toInt() }

                        val rightOffsets = tfRightOffsets.text.split(",").map { it.trim().toDouble() }
                        val rightFingers = tfRightFingers.text.split(",").map { parseFingerCode(it.trim()) }
                        val rightAnchors = tfRightAnchors.text.split(",").map { it.trim().toInt() }

                        val leftHalf = KeyboardHalf(Hand.LEFT, stagger, numRows, leftFingers.size, leftOffsets, homeRow)
                        val rightHalf = KeyboardHalf(Hand.RIGHT, stagger, numRows, rightFingers.size, rightOffsets, homeRow)

                        val leftConfigs = leftFingers.indices.map { i -> ColumnConfig(i, leftAnchors[i], leftFingers[i]) }
                        val rightConfigs = rightFingers.indices.map { i -> ColumnConfig(i, rightAnchors[i], rightFingers[i]) }

                        val useCustom = generatorModelCombo.selectedIndex == 0
                        val leftEffort = if (useCustom) {
                            CustomKeyboardEffortGenerator(
                                keyboardHalf = leftHalf,
                                columnConfigs = leftConfigs,
                                angleOfApproachDegrees = angle,
                                useOptimalFingerAssignment = optFinger
                            ).generateEffortMatrix()
                        } else {
                            ColemakDhEffortGenerator(
                                keyboardHalf = leftHalf,
                                columnConfigs = leftConfigs,
                                angleOfApproachDegrees = angle,
                                lateralPenaltyPx = EffortGeneratorSettings.lateralPenaltyPx,
                                distancePenaltyPd = EffortGeneratorSettings.distancePenaltyPd,
                                activationMargin = EffortGeneratorSettings.activationMargin,
                                useOptimalFingerAssignment = optFinger,
                                boxInKeySpace = EffortGeneratorSettings.boxInKeySpace
                            ).generateEffortMatrix()
                        }

                        val rightEffort = if (useCustom) {
                            CustomKeyboardEffortGenerator(
                                keyboardHalf = rightHalf,
                                columnConfigs = rightConfigs,
                                angleOfApproachDegrees = angle,
                                useOptimalFingerAssignment = optFinger
                            ).generateEffortMatrix()
                        } else {
                            ColemakDhEffortGenerator(
                                keyboardHalf = rightHalf,
                                columnConfigs = rightConfigs,
                                angleOfApproachDegrees = angle,
                                lateralPenaltyPx = EffortGeneratorSettings.lateralPenaltyPx,
                                distancePenaltyPd = EffortGeneratorSettings.distancePenaltyPd,
                                activationMargin = EffortGeneratorSettings.activationMargin,
                                useOptimalFingerAssignment = optFinger,
                                boxInKeySpace = EffortGeneratorSettings.boxInKeySpace
                            ).generateEffortMatrix()
                        }

                        val outFile = File("custom_effort_heatmap.png")
                        EffortHeatmapRenderer.renderFullKeyboard(
                            leftHalf = leftHalf,
                            rightHalf = rightHalf,
                            leftEffort = leftEffort,
                            rightEffort = rightEffort,
                            leftcolumnConfig = leftConfigs,
                            rightcolumnConfig = rightConfigs,
                            outputPath = outFile.absolutePath
                        )

                        val img = ImageIO.read(outFile)
                        if (img != null) {
                            heatmapPreviewPanel.setImage(img, outFile)
                        }

                        val sb = StringBuilder()
                        sb.appendLine("=== GENERÁLT FIZIKAI EFFORT MÁTRIX ($stagger, numRows=$numRows, homeRow=$homeRow) ===")
                        for (r in 0 until numRows) {
                            val lStr = leftEffort[r].joinToString(" ") { String.format("%5.2f", it) }
                            val rStr = rightEffort[r].joinToString(" ") { String.format("%5.2f", it) }
                            val tag = if (r == homeRow) " <-- HOME ROW" else if (r == numRows - 1) " <-- THUMB ROW" else ""
                            sb.appendLine("$lStr   |   $rStr$tag")
                        }
                        matrixOutputArea.text = sb.toString()
                        println("Hőtérkép elmentve: ${outFile.absolutePath}")
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(this@MainGuiFrame, "Hiba a generáláskor:\n${e.message}", "Hiba", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }
            gbc.gridx = 0; gbc.gridy = 11; gbc.gridwidth = 2
            add(btnRender, gbc)
        }

        leftConfigPanel.add(fingerBox)
        leftConfigPanel.add(Box.createVerticalStrut(6))
        leftConfigPanel.add(genParamsBox)
        leftConfigPanel.add(Box.createVerticalStrut(6))
        leftConfigPanel.add(hwParamsBox)

        val rightDisplaySplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JPanel(BorderLayout()).apply {
                border = TitledBorder("Fizikai Effort Hőtérkép előnézet (custom_effort_heatmap.png)")
                add(heatmapPreviewPanel, BorderLayout.CENTER)
            },
            JPanel(BorderLayout()).apply {
                border = TitledBorder("Számított Effort Mátrix (Bal | Jobb)")
                add(JScrollPane(matrixOutputArea), BorderLayout.CENTER)
            }
        ).apply {
            resizeWeight = 0.65
            dividerLocation = 410
        }

        panel.add(JScrollPane(leftConfigPanel).apply { border = null }, BorderLayout.WEST)
        panel.add(rightDisplaySplit, BorderLayout.CENTER)
        return panel
    }

    // =========================================================================
    // 4. MENÜ: LAYOUT GENERÁLÁS
    // =========================================================================
    private fun createLayoutGenerationPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10)).apply {
            border = EmptyBorder(10, 10, 10, 10)
        }

        val folderGroup = ButtonGroup()
        val rbKeyboards = JRadioButton("keyboards", true)
        val rbGenerated = JRadioButton("generatedkeyboards", false)
        folderGroup.add(rbKeyboards)
        folderGroup.add(rbGenerated)
        fun selectedFolder() = if (rbKeyboards.isSelected) "keyboards" else "generatedkeyboards"

        val layoutCombo = JComboBox<String>()
        val corpusCombo = JComboBox<String>()

        fun refreshCombos() {
            val prevL = layoutCombo.selectedItem as? String
            layoutCombo.removeAllItems()
            listKeyboardsInFolder(selectedFolder()).forEach { layoutCombo.addItem(it) }
            if (prevL != null) layoutCombo.selectedItem = prevL

            val prevC = corpusCombo.selectedItem as? String
            corpusCombo.removeAllItems()
            listAnalyzedCorpora().forEach { corpusCombo.addItem(it) }
            if (prevC != null) corpusCombo.selectedItem = prevC
        }

        rbKeyboards.addActionListener { refreshCombos() }
        rbGenerated.addActionListener { refreshCombos() }

        val algoCombo = JComboBox(
            arrayOf(
                "Chips-n-Salsa SA + 2-opt (SelfTuningLam / ModifiedLam)",
                "Saját Simulated Annealing + 2-opt",
                "MOEA Framework (Genetikus Algoritmus + PMX + 2-opt)"
            )
        )

        val spRuns = JSpinner(SpinnerNumberModel(32, 1, 1024, 8))
        val spEvals = JSpinner(SpinnerNumberModel(42000, 500, 1000000, 5000))
        val spPopSize = JSpinner(SpinnerNumberModel(40, 10, 500, 10))
        val cbSelfTuningLam = JCheckBox("Self-Tuning Lam hűtés (ha ki van kapcsolva: Modified Lam)", true)
        val cbLeftThumb = JCheckBox("Bal hüvelykujj (thumb_left) bevonása", false)
        val cbRightThumb = JCheckBox("Jobb hüvelykujj (thumb_right) bevonása", false)

        // Bal oldal: Algoritmus és bemenet beállítások
        val leftSetupPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(460, 600)
        }

        val inputBox = JPanel(GridBagLayout()).apply {
            border = TitledBorder("1. Kiindulási Layout, Korpusz és Algoritmus")
            val gbc = GridBagConstraints().apply {
                insets = Insets(5, 5, 5, 5)
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
            }

            val folderRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Forrás mappa:"))
                add(rbKeyboards)
                add(rbGenerated)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
            add(folderRow, gbc)

            gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.3
            add(JLabel("Javítandó Layout:"), gbc)
            gbc.gridx = 1; gbc.weightx = 0.7
            add(layoutCombo, gbc)

            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.3
            add(JLabel("Korpusz (Adathalmaz):"), gbc)
            gbc.gridx = 1; gbc.weightx = 0.7
            add(corpusCombo, gbc)

            gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.3
            add(JLabel("Generáló algoritmus:"), gbc)
            gbc.gridx = 1; gbc.weightx = 0.7
            add(algoCombo, gbc)

            val refreshBtn = JButton("Layout és Korpusz listák frissítése").apply {
                addActionListener { refreshCombos() }
            }
            gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2
            add(refreshBtn, gbc)
        }

        val algoParamsBox = JPanel(GridBagLayout()).apply {
            border = TitledBorder("2. Algoritmus futási paraméterek")
            val gbc = GridBagConstraints().apply {
                insets = Insets(5, 5, 5, 5)
                fill = GridBagConstraints.HORIZONTAL
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.6
            add(JLabel("Párhuzamos futások / szigetek száma (runs / islands):"), gbc)
            gbc.gridx = 1; gbc.weightx = 0.4
            add(spRuns, gbc)

            gbc.gridx = 0; gbc.gridy = 1
            add(JLabel("Kiértékelések / iterációk száma szálanként:"), gbc)
            gbc.gridx = 1
            add(spEvals, gbc)

            gbc.gridx = 0; gbc.gridy = 2
            add(JLabel("Populáció mérete (csak MOEA GA esetén):"), gbc)
            gbc.gridx = 1
            add(spPopSize, gbc)

            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2
            add(cbSelfTuningLam, gbc)

            gbc.gridy = 4
            add(cbLeftThumb, gbc)

            gbc.gridy = 5
            add(cbRightThumb, gbc)
        }

        // Jobb oldal: Célfüggvény súlyok (Weights) testreszabása
        val spWCharReach = JSpinner(SpinnerNumberModel(weightCharReachEffort, -500.0, 500.0, 1.0))
        val spWGravity = JSpinner(SpinnerNumberModel(weightGravity, -500.0, 500.0, 0.1))
        val spWHandImb = JSpinner(SpinnerNumberModel(weightHandImbalance, -500.0, 500.0, 0.5))
        val spWPinkyOff = JSpinner(SpinnerNumberModel(weightPinkyOff, -500.0, 500.0, 0.5))
        val spWSfb = JSpinner(SpinnerNumberModel(weightSfb, -500.0, 500.0, 1.0))
        val spWFullScissor = JSpinner(SpinnerNumberModel(weightFullScissor, -500.0, 500.0, 1.0))
        val spWHalfScissor = JSpinner(SpinnerNumberModel(weightHalfScissor, -500.0, 500.0, 0.5))
        val spWLatStretch = JSpinner(SpinnerNumberModel(weightLateralStretch, -500.0, 500.0, 0.5))
        val spWPinkyStretch = JSpinner(SpinnerNumberModel(weightPinkyStretch, -500.0, 500.0, 0.5))
        val spWRedirect = JSpinner(SpinnerNumberModel(weightRedirect, -500.0, 500.0, 0.5))
        val spWSpaceSfs1 = JSpinner(SpinnerNumberModel(weightSpaceSfs1, -500.0, 500.0, 0.5))

        val spWRow0 = JSpinner(SpinnerNumberModel(weightRow0, -500.0, 500.0, 0.5))
        val spWRow1 = JSpinner(SpinnerNumberModel(weightRow1, -500.0, 500.0, 0.5))
        val spWRow2 = JSpinner(SpinnerNumberModel(weightRow2, -500.0, 500.0, 0.5))
        val spWRow3 = JSpinner(SpinnerNumberModel(weightRow3, -500.0, 500.0, 0.5))
        val spWRow4 = JSpinner(SpinnerNumberModel(weightRow4, -500.0, 500.0, 0.5))
        val spWRow5 = JSpinner(SpinnerNumberModel(weightRow5, -500.0, 500.0, 0.5))

        val spWHomeRow = JSpinner(SpinnerNumberModel(weightHomeRow, -500.0, 500.0, 0.05))
        val spWAlternation = JSpinner(SpinnerNumberModel(weightAlternation, -500.0, 500.0, 0.1))
        val spWRoll = JSpinner(SpinnerNumberModel(weightRoll, -500.0, 500.0, 0.1))
        val spWThreeRoll = JSpinner(SpinnerNumberModel(weightThreeRoll, -500.0, 500.0, 0.1))

        fun syncWeightsFromSpinners() {
            weightCharReachEffort = (spWCharReach.value as Number).toDouble()
            weightGravity = (spWGravity.value as Number).toDouble()
            weightHandImbalance = (spWHandImb.value as Number).toDouble()
            weightPinkyOff = (spWPinkyOff.value as Number).toDouble()
            weightSfb = (spWSfb.value as Number).toDouble()
            weightFullScissor = (spWFullScissor.value as Number).toDouble()
            weightHalfScissor = (spWHalfScissor.value as Number).toDouble()
            weightLateralStretch = (spWLatStretch.value as Number).toDouble()
            weightPinkyStretch = (spWPinkyStretch.value as Number).toDouble()
            weightRedirect = (spWRedirect.value as Number).toDouble()
            weightSpaceSfs1 = (spWSpaceSfs1.value as Number).toDouble()
            weightRow0 = (spWRow0.value as Number).toDouble()
            weightRow1 = (spWRow1.value as Number).toDouble()
            weightRow2 = (spWRow2.value as Number).toDouble()
            weightRow3 = (spWRow3.value as Number).toDouble()
            weightRow4 = (spWRow4.value as Number).toDouble()
            weightRow5 = (spWRow5.value as Number).toDouble()
            weightHomeRow = (spWHomeRow.value as Number).toDouble()
            weightAlternation = (spWAlternation.value as Number).toDouble()
            weightRoll = (spWRoll.value as Number).toDouble()
            weightThreeRoll = (spWThreeRoll.value as Number).toDouble()
        }

        fun syncSpinnersFromWeights() {
            spWCharReach.value = weightCharReachEffort
            spWGravity.value = weightGravity
            spWHandImb.value = weightHandImbalance
            spWPinkyOff.value = weightPinkyOff
            spWSfb.value = weightSfb
            spWFullScissor.value = weightFullScissor
            spWHalfScissor.value = weightHalfScissor
            spWLatStretch.value = weightLateralStretch
            spWPinkyStretch.value = weightPinkyStretch
            spWRedirect.value = weightRedirect
            spWSpaceSfs1.value = weightSpaceSfs1
            spWRow0.value = weightRow0
            spWRow1.value = weightRow1
            spWRow2.value = weightRow2
            spWRow3.value = weightRow3
            spWRow4.value = weightRow4
            spWRow5.value = weightRow5
            spWHomeRow.value = weightHomeRow
            spWAlternation.value = weightAlternation
            spWRoll.value = weightRoll
            spWThreeRoll.value = weightThreeRoll
        }

        val btnStartOptimization = JButton("LAYOUT OPTIMALIZÁLÁS / GENERÁLÁS INDÍTÁSA").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            preferredSize = Dimension(400, 44)
            addActionListener {
                val layoutName = layoutCombo.selectedItem as? String ?: return@addActionListener
                val corpusName = corpusCombo.selectedItem as? String ?: return@addActionListener
                val folder = selectedFolder()
                val runs = (spRuns.value as Number).toInt()
                val evals = (spEvals.value as Number).toInt()
                val popSize = (spPopSize.value as Number).toInt()
                val useSelfTuning = cbSelfTuningLam.isSelected
                val lThumb = cbLeftThumb.isSelected
                val rThumb = cbRightThumb.isSelected

                syncWeightsFromSpinners()

                val algoIdx = algoCombo.selectedIndex
                val algoLabel = algoCombo.selectedItem.toString()

                isEnabled = false
                runInBackground(
                    "Layout optimalizálás: $layoutName ($algoLabel)",
                    onCompleteUI = {
                        isEnabled = true
                        JOptionPane.showMessageDialog(
                            this@MainGuiFrame,
                            "A layout generálás sikeresen befejeződött!\nA Top 3 kiosztás elmentve a 'generatedkeyboards' mappába.",
                            "Optimalizálás kész",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                ) {
                    when (algoIdx) {
                        0 -> parallelChipsNSalsaImprovement(
                            runs = runs,
                            evaluationsPerRun = evals,
                            useSelfTuningLam = useSelfTuning,
                            keyboardname = layoutName,
                            corpusFileName = corpusName,
                            leftThumbEnabled = lThumb,
                            rightThumbEnabled = rThumb,
                            folder = folder
                        )
                        1 -> parallelImprovement(
                            runs = runs,
                            keyboardname = layoutName,
                            corpusFileName = corpusName,
                            iterations = evals,
                            folder = folder
                        )
                        2 -> parallelMoeaImprovement(
                            islands = runs,
                            evaluationsPerIsland = evals,
                            populationSize = popSize,
                            keyboardname = layoutName,
                            corpusFileName = corpusName,
                            leftThumbEnabled = lThumb,
                            rightThumbEnabled = rThumb,
                            folder = folder
                        )
                    }
                }
            }
        }

        leftSetupPanel.add(inputBox)
        leftSetupPanel.add(Box.createVerticalStrut(10))
        leftSetupPanel.add(algoParamsBox)
        leftSetupPanel.add(Box.createVerticalStrut(14))
        leftSetupPanel.add(JPanel(BorderLayout()).apply {
            maximumSize = Dimension(500, 55)
            add(btnStartOptimization, BorderLayout.CENTER)
        })
        leftSetupPanel.add(Box.createVerticalGlue())

        val weightsPanel = JPanel(BorderLayout(8, 8)).apply {
            border = TitledBorder("3. Kiértékelő függvény súlyainak (Weights) szabad testreszabása")
        }

        val weightsGrid = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                insets = Insets(4, 8, 4, 8)
                fill = GridBagConstraints.HORIZONTAL
            }

            fun addWeightField(colOffset: Int, row: Int, label: String, spinner: JSpinner) {
                gbc.gridx = colOffset; gbc.gridy = row; gbc.weightx = 0.65
                add(JLabel(label), gbc)
                gbc.gridx = colOffset + 1; gbc.weightx = 0.35
                add(spinner, gbc)
            }

            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
            add(JLabel("BÜNTETŐ SÚLYOK (Penalties - növelik a Total Effortot):").apply { font = font.deriveFont(Font.BOLD) }, gbc)
            gbc.gridx = 2; gbc.gridy = 0; gbc.gridwidth = 2
            add(JLabel("JUTALMAZÓ ÉS SOR SÚLYOK (Rewards & Row Weights):").apply { font = font.deriveFont(Font.BOLD) }, gbc)
            gbc.gridwidth = 1

            addWeightField(0, 1, "weightCharReachEffort (Elérési terhelés):", spWCharReach)
            addWeightField(0, 2, "weightGravity (Alap gravitáció):", spWGravity)
            addWeightField(0, 3, "weightHandImbalance (Kéz aszimmetria):", spWHandImb)
            addWeightField(0, 4, "weightPinkyOff (Kisujj alapgomb kívül):", spWPinkyOff)
            addWeightField(0, 5, "weightSfb (Azonos ujjas bigram):", spWSfb)
            addWeightField(0, 6, "weightFullScissor (Full Scissor FSB):", spWFullScissor)
            addWeightField(0, 7, "weightHalfScissor (Half Scissor HSB):", spWHalfScissor)
            addWeightField(0, 8, "weightLateralStretch (Oldalirányú nyújtás):", spWLatStretch)
            addWeightField(0, 9, "weightPinkyStretch (Kisujj-gyűrűs nyújtás):", spWPinkyStretch)
            addWeightField(0, 10, "weightRedirect (Egykezes irányváltás):", spWRedirect)
            addWeightField(0, 11, "weightSpaceSfs1 (Szóközös ugrás SFS-1):", spWSpaceSfs1)

            addWeightField(2, 1, "weightHomeRow (Alapsor jutalom):", spWHomeRow)
            addWeightField(2, 2, "weightAlternation (Alternálás jutalom):", spWAlternation)
            addWeightField(2, 3, "weightRoll (2-Roll jutalom):", spWRoll)
            addWeightField(2, 4, "weightThreeRoll (3-Roll jutalom):", spWThreeRoll)

            addWeightField(2, 6, "weightRow0 (0. sor büntetés):", spWRow0)
            addWeightField(2, 7, "weightRow1 (1. sor büntetés):", spWRow1)
            addWeightField(2, 8, "weightRow2 (2. sor büntetés):", spWRow2)
            addWeightField(2, 9, "weightRow3 (3. sor büntetés):", spWRow3)
            addWeightField(2, 10, "weightRow4 (4. sor büntetés):", spWRow4)
            addWeightField(2, 11, "weightRow5 (5. sor / Thumb büntetés):", spWRow5)
        }

        val weightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4))
        val btnApplyWeights = JButton("Súlyok mentése a memóriába").apply {
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                syncWeightsFromSpinners()
                println("Célfüggvény súlyok (Weights) frissítve.")
            }
        }
        val btnResetWeights = JButton("Alapértelmezett súlyok visszaállítása").apply {
            addActionListener {
                resetDefaultWeights()
                syncSpinnersFromWeights()
                println("Alapértelmezett célfüggvény súlyok visszaállítva.")
            }
        }
        weightButtons.add(btnResetWeights)
        weightButtons.add(btnApplyWeights)

        weightsPanel.add(JScrollPane(weightsGrid).apply { border = null }, BorderLayout.CENTER)
        weightsPanel.add(weightButtons, BorderLayout.SOUTH)

        refreshCombos()

        panel.add(leftSetupPanel, BorderLayout.WEST)
        panel.add(weightsPanel, BorderLayout.CENTER)
        return panel
    }

    // =========================================================================
    // 5. MENÜ: GENERÁLT LAYOUTOK BÖNGÉSZÉSE
    // =========================================================================
    private fun createGeneratedLayoutsBrowserPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10)).apply {
            border = EmptyBorder(10, 10, 10, 10)
        }

        val rootNode = DefaultMutableTreeNode("generatedkeyboards")
        val treeModel = DefaultTreeModel(rootNode)
        val tree = JTree(treeModel).apply {
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            font = Font("SansSerif", Font.PLAIN, 13)
        }

        val corpusCombo = JComboBox<String>()

        val statsTableModel = object : DefaultTableModel(arrayOf("Statisztika / Metrika", "Érték"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        val statsTable = JTable(statsTableModel).apply {
            rowHeight = 22
            font = Font("SansSerif", Font.PLAIN, 13)
            tableHeader.font = Font("SansSerif", Font.BOLD, 13)
        }

        val heatmapPanel = HeatmapImagePanel("Válassz ki egy generált layoutot a bal oldali fából a megnyitáshoz és elemzéshez!")
        val yamlAndLogArea = JTextArea().apply {
            isEditable = false
            font = Font("Consolas", Font.PLAIN, 12)
        }

        fun refreshBrowserTree() {
            val prevCorpus = corpusCombo.selectedItem as? String
            corpusCombo.removeAllItems()
            listAnalyzedCorpora().forEach { corpusCombo.addItem(it) }
            if (prevCorpus != null) {
                corpusCombo.selectedItem = prevCorpus
            } else if (corpusCombo.itemCount > 0) {
                val defaultHu = "huszonkettoHuszonnegyeswikipediahuszonegy.txt"
                if ((0 until corpusCombo.itemCount).any { corpusCombo.getItemAt(it) == defaultHu }) {
                    corpusCombo.selectedItem = defaultHu
                }
            }

            rootNode.removeAllChildren()
            val genDir = File("generatedkeyboards")
            if (genDir.exists() && genDir.isDirectory) {
                val subDirs = genDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
                for (dir in subDirs) {
                    val dirNode = DefaultMutableTreeNode(dir.name)
                    val yamlFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".yaml", true) }
                        ?.sortedBy { it.name } ?: emptyList()
                    for (yf in yamlFiles) {
                        dirNode.add(DefaultMutableTreeNode(yf.name))
                    }
                    if (dirNode.childCount > 0) {
                        rootNode.add(dirNode)
                    }
                }
                // Esetleges közvetlen YAML fájlok a generatedkeyboards gyökerében
                val rootYamls = genDir.listFiles { f -> f.isFile && f.name.endsWith(".yaml", true) }?.sortedBy { it.name } ?: emptyList()
                for (yf in rootYamls) {
                    rootNode.add(DefaultMutableTreeNode(yf.name))
                }
            }
            treeModel.reload()
            for (i in 0 until tree.rowCount) {
                tree.expandRow(i)
            }
        }

        fun detectCorpusFromImprovementLog(runDirName: String): String? {
            val logFile = File("ImprovementLogs/$runDirName.txt")
            if (!logFile.exists()) return null
            return try {
                logFile.useLines { lines ->
                    lines.firstOrNull { it.startsWith("Korpusz neve:") }
                        ?.substringAfter("Korpusz neve:")
                        ?.trim()
                }
            } catch (_: Exception) {
                null
            }
        }

        fun analyzeSelectedGeneratedLayout(relativePath: String, autoDetectCorpus: Boolean) {
            val genDir = File("generatedkeyboards")
            val yamlFile = File(genDir, relativePath)
            if (!yamlFile.exists() || !yamlFile.isFile) return

            val parentDirName = yamlFile.parentFile?.takeIf { it != genDir }?.name
            if (autoDetectCorpus && parentDirName != null) {
                val loggedCorpus = detectCorpusFromImprovementLog(parentDirName)
                if (loggedCorpus != null && (0 until corpusCombo.itemCount).any { corpusCombo.getItemAt(it) == loggedCorpus }) {
                    corpusCombo.selectedItem = loggedCorpus
                }
            }

            val corpusName = corpusCombo.selectedItem as? String ?: return

            runInBackground("Generált layout megnyitása és elemzése: $relativePath") {
                // Meghívjuk a módosított billentyűzetelemző függvényt folder = "generatedkeyboards" paraméterrel!
                val result = completeLayoutAnalysis(
                    layoutName = relativePath,
                    datasetName = corpusName,
                    folder = "generatedkeyboards",
                    verbose = true
                )

                val yamlContent = yamlFile.readText(StandardCharsets.UTF_8)
                val logContent = if (parentDirName != null) {
                    val lf = File("ImprovementLogs/$parentDirName.txt")
                    if (lf.exists()) lf.readText(StandardCharsets.UTF_8) else ""
                } else ""

                // Heatmap kép betöltése (ha még nincs, legeneráljuk)
                val expectedHeatmap = File(yamlFile.parentFile, "${yamlFile.nameWithoutExtension}_heatmap.png")
                if (!expectedHeatmap.exists()) {
                    try {
                        val parsed = LayoutParser.parseLayout(yamlFile)
                        renderCharEffortlayout(parsed, expectedHeatmap.absolutePath)
                    } catch (_: Exception) {
                    }
                }
                val img = if (expectedHeatmap.exists()) ImageIO.read(expectedHeatmap) else null

                SwingUtilities.invokeLater {
                    if (result != null) {
                        statsTableModel.rowCount = 0
                        statsTableModel.addRow(arrayOf("Layout neve", result.layoutName))
                        statsTableModel.addRow(arrayOf("Fájl (generatedkeyboards)", relativePath))
                        statsTableModel.addRow(arrayOf("Korpusz", corpusName))
                        statsTableModel.addRow(arrayOf("Összesített Effort (Total Effort)", String.format("%.4f", result.totalEffort)))
                        statsTableModel.addRow(arrayOf("Char Reach Effort", String.format("%.4f", result.charReachEffort)))
                        statsTableModel.addRow(arrayOf("Base Gravity Effort", String.format("%.4f", result.baseGravityEffort)))
                        statsTableModel.addRow(arrayOf("Kéz arány (Bal / Jobb)", String.format("%.2f%% / %.2f%%", result.handBalanceLeft, result.handBalanceRight)))
                        statsTableModel.addRow(arrayOf("Alapsor (Home Row) arány", String.format("%.2f%%", result.homeRowPercentage)))
                        for ((idx, rPct) in result.rowPercentages.withIndex()) {
                            statsTableModel.addRow(arrayOf("  - $idx. sor használata", String.format("%.2f%%", rPct)))
                        }
                        statsTableModel.addRow(arrayOf("SFB (Same Finger Bigram)", String.format("%.4f%%", result.sfbPercentage)))
                        statsTableModel.addRow(arrayOf("Space SFS-1 büntetőpont", String.format("%.4f", result.spaceSfs1Penalty)))
                        statsTableModel.addRow(arrayOf("Pinky Off (Kisujj alapgomb kívül)", String.format("%.4f%%", result.pinkyOffPercentage)))
                        statsTableModel.addRow(arrayOf("Lateral Stretch Bigram (LSB)", String.format("%.4f%%", result.lateralStretchPercentage)))
                        statsTableModel.addRow(arrayOf("Pinky Stretch Bigram (PSB)", String.format("%.4f%%", result.pinkystretchPercentage)))
                        statsTableModel.addRow(arrayOf("Full Scissor Bigram (FSB)", String.format("%.4f%%", result.fullScissorPercentage)))
                        statsTableModel.addRow(arrayOf("Half Scissor Bigram (HSB)", String.format("%.4f%%", result.halfScissorPercentage)))
                        statsTableModel.addRow(arrayOf("Alternálás (1-1-1)", String.format("%.4f%%", result.alternationPercentage)))
                        statsTableModel.addRow(arrayOf("2-Roll (2-1 és 1-2)", String.format("%.4f%%", result.rollPercentage)))
                        statsTableModel.addRow(arrayOf("3-Roll (Egykezes gördülés)", String.format("%.4f%%", result.threerollPercentage)))
                        statsTableModel.addRow(arrayOf("Redirect (Egykezes irányváltás)", String.format("%.4f%%", result.redirectPercentage)))
                    }

                    if (img != null) {
                        heatmapPanel.setImage(img, expectedHeatmap)
                    } else {
                        heatmapPanel.showMessage("Hőtérkép nem található.")
                    }

                    val combinedText = buildString {
                        appendLine("=== YAML DEFINÍCIÓ (${yamlFile.name}) ===")
                        appendLine(yamlContent.trim())
                        if (logContent.isNotBlank()) {
                            appendLine()
                            appendLine("=== TARTOZÓ OPTIMALIZÁLÁSI LOG ($parentDirName.txt) ===")
                            appendLine(logContent.trim())
                        }
                    }
                    yamlAndLogArea.text = combinedText
                    yamlAndLogArea.caretPosition = 0
                }
            }
        }

        tree.addTreeSelectionListener {
            val path = tree.selectionPath ?: return@addTreeSelectionListener
            if (path.pathCount < 2) return@addTreeSelectionListener
            val parts = (1 until path.pathCount).map { path.getPathComponent(it).toString() }
            val relPath = parts.joinToString("/")
            if (relPath.endsWith(".yaml", true)) {
                analyzeSelectedGeneratedLayout(relPath, autoDetectCorpus = true)
            }
        }

        val leftTreePanel = JPanel(BorderLayout(6, 6)).apply {
            border = TitledBorder("Generált Layoutok (generatedkeyboards mappa)")
            preferredSize = Dimension(390, 600)

            val topControls = JPanel(GridBagLayout()).apply {
                val gbc = GridBagConstraints().apply {
                    insets = Insets(3, 3, 3, 3)
                    fill = GridBagConstraints.HORIZONTAL
                }
                gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
                add(JLabel("Elemzési korpusz:"), gbc)
                gbc.gridx = 1; gbc.weightx = 1.0
                add(corpusCombo, gbc)

                val btnRow = JPanel(GridLayout(1, 2, 4, 0))
                val btnRefresh = JButton("Mappa frissítése").apply {
                    addActionListener { refreshBrowserTree() }
                }
                val btnReanalyze = JButton("Újraelemzés").apply {
                    addActionListener {
                        val path = tree.selectionPath ?: return@addActionListener
                        if (path.pathCount >= 2) {
                            val relPath = (1 until path.pathCount).joinToString("/") { path.getPathComponent(it).toString() }
                            if (relPath.endsWith(".yaml", true)) {
                                analyzeSelectedGeneratedLayout(relPath, autoDetectCorpus = false)
                            }
                        }
                    }
                }
                btnRow.add(btnRefresh)
                btnRow.add(btnReanalyze)
                gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2
                add(btnRow, gbc)
            }

            add(topControls, BorderLayout.NORTH)
            add(JScrollPane(tree), BorderLayout.CENTER)
        }

        val bottomDetailsSplit = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JPanel(BorderLayout()).apply {
                border = TitledBorder("Elemzési statisztikák (Automatikusan számolva megnyitáskor)")
                add(JScrollPane(statsTable), BorderLayout.CENTER)
            },
            JPanel(BorderLayout()).apply {
                border = TitledBorder("YAML Fájl és Optimalizálási Log")
                add(JScrollPane(yamlAndLogArea), BorderLayout.CENTER)
            }
        ).apply {
            resizeWeight = 0.55
            dividerLocation = 460
        }

        val rightMainSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JPanel(BorderLayout()).apply {
                border = TitledBorder("Generált Kiosztás Hőtérképe")
                add(heatmapPanel, BorderLayout.CENTER)
            },
            bottomDetailsSplit
        ).apply {
            resizeWeight = 0.58
            dividerLocation = 380
        }

        refreshBrowserTree()

        panel.add(leftTreePanel, BorderLayout.WEST)
        panel.add(rightMainSplit, BorderLayout.CENTER)
        return panel
    }
}
