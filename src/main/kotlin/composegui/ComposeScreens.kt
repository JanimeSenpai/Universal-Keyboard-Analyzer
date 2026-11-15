package composegui

import CorpusAnalyzer.*
import EffortModel.*
import KeyboardLayoutGeneration.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import keyboard_analysis.*
import kotlinx.coroutines.delay
import java.io.File
import java.nio.charset.StandardCharsets

// =========================================================================
// 1. KÉPERNYŐ: KORPUSZ ELEMZŐ ESZKÖZÖK
// =========================================================================

private sealed interface CorpusTableEntry {
    data class SectionHeader(val textHu: String, val textEn: String = textHu) : CorpusTableEntry
    data class DataRow(
        val rank: Int,
        val item: String,
        val percentage: String,
        val count: String,
        val category: String? = null
    ) : CorpusTableEntry
}

@Composable
fun CorpusAnalyzerScreen(
    runTask: (String, suspend () -> Unit) -> Unit
) {
    val lang = LocalAppLanguage.current
    var processableCorpora by remember { mutableStateOf(listProcessableCorpora()) }
    var selectedCorpus by remember {
        mutableStateOf(
            processableCorpora.firstOrNull { it == "huszonkettoHuszonnegyeswikipediahuszonegy.txt" }
                ?: processableCorpora.firstOrNull()
        )
    }

    var wordFreqLimit by remember { mutableStateOf(100) }
    var randomSubsetLines by remember { mutableStateOf(1000) }
    var rawTexts by remember { mutableStateOf(listRawTexts()) }
    var selectedRawFile by remember { mutableStateOf(rawTexts.firstOrNull()) }

    var resultFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedResultFile by remember { mutableStateOf<String?>(null) }
    var tableViewMode by remember { mutableStateOf(true) }
    var parsedTableEntries by remember { mutableStateOf<List<CorpusTableEntry>>(emptyList()) }
    var previewContent by remember {
        mutableStateOf("")
    }

    fun formatTabSeparatedLines(rawLines: List<String>): String {
        val splitLines = rawLines.map { it.split('\t') }
        val maxCols = splitLines.maxOfOrNull { it.size } ?: 1
        if (maxCols <= 1) {
            return rawLines.joinToString("\n") { it.replace("\t", "    ") }
        }
        val colWidths = IntArray(maxCols) { col ->
            splitLines.maxOfOrNull { row -> row.getOrNull(col)?.length ?: 0 }?.coerceIn(4, 36) ?: 8
        }
        return splitLines.joinToString("\n") { row ->
            if (row.size <= 1) {
                row.firstOrNull()?.replace("\t", "    ") ?: ""
            } else {
                row.mapIndexed { idx, cell ->
                    if (idx == row.lastIndex) cell else cell.padEnd(colWidths[idx] + 4, ' ')
                }.joinToString("")
            }
        }
    }

    fun parseCorpusEntries(rawLines: List<String>): List<CorpusTableEntry> {
        val entries = mutableListOf<CorpusTableEntry>()
        var currentRank = 0
        rawLines.forEachIndexed { lineIdx, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val cols = rawLine.split('\t').map { it.trim() }
            when {
                cols.size >= 4 -> {
                    currentRank++
                    entries.add(
                        CorpusTableEntry.DataRow(
                            rank = currentRank,
                            item = cols[0],
                            percentage = cols[1],
                            category = cols[2],
                            count = cols[3]
                        )
                    )
                }
                cols.size == 3 -> {
                    currentRank++
                    entries.add(
                        CorpusTableEntry.DataRow(
                            rank = currentRank,
                            item = cols[0],
                            percentage = cols[1],
                            count = cols[2],
                            category = null
                        )
                    )
                }
                cols.size == 2 -> {
                    currentRank++
                    entries.add(
                        CorpusTableEntry.DataRow(
                            rank = currentRank,
                            item = cols[0],
                            percentage = cols[1],
                            count = "",
                            category = null
                        )
                    )
                }
                else -> {
                    val single = cols[0]
                    if (lineIdx == 0 && single.all { it.isDigit() }) {
                        entries.add(
                            CorpusTableEntry.SectionHeader(
                                textHu = "Összes elem / N-gram a korpuszban: $single db",
                                textEn = "Total items / N-grams in corpus: $single"
                            )
                        )
                    } else {
                        if (single.endsWith(":")) {
                            currentRank = 0
                        }
                        entries.add(CorpusTableEntry.SectionHeader(single, single))
                    }
                }
            }
        }
        return entries
    }

    fun loadResultFileText(corpus: String, fileName: String) {
        val f = File("analysis_results/$corpus/$fileName")
        if (f.exists() && f.isFile) {
            runCatching {
                val rawLines = f.useLines(StandardCharsets.UTF_8) { lines ->
                    lines.take(3000).toList()
                }
                parsedTableEntries = parseCorpusEntries(rawLines)
                previewContent = formatTabSeparatedLines(rawLines)
            }.onFailure {
                parsedTableEntries = emptyList()
                previewContent = lang.pick(
                    "Hiba a fájl olvasásakor: ${it.message}",
                    "Error reading file: ${it.message}"
                )
            }
        }
    }

    fun refreshResultFilesFor(corpus: String?) {
        if (corpus == null) {
            resultFiles = emptyList()
            selectedResultFile = null
            parsedTableEntries = emptyList()
            return
        }
        val dir = File("analysis_results/$corpus")
        val list = if (dir.exists() && dir.isDirectory) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", true) }
                ?.map { it.name }
                ?.sorted() ?: emptyList()
        } else emptyList()

        resultFiles = list
        val chosen = if (selectedResultFile != null && list.contains(selectedResultFile)) {
            selectedResultFile
        } else {
            list.firstOrNull()
        }
        selectedResultFile = chosen
        if (chosen != null) {
            loadResultFileText(corpus, chosen)
        } else {
            parsedTableEntries = emptyList()
            previewContent = ""
        }
    }

    LaunchedEffect(selectedCorpus) {
        refreshResultFilesFor(selectedCorpus)
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bal oldali vezérlő oszlop
        Column(
            modifier = Modifier
                .width(470.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard(
                title = tr("1. Korpusz kiválasztása", "1. Select Corpus"),
                subtitle = tr("Forrásmappa: processable_texts", "Source folder: processable_texts"),
                headerTrailing = {
                    IconButton(onClick = {
                        processableCorpora = listProcessableCorpora()
                        rawTexts = listRawTexts()
                        refreshResultFilesFor(selectedCorpus)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = tr("Frissítés", "Refresh"))
                    }
                }
            ) {
                DropdownSelector(
                    label = tr("Feldolgozható korpuszok (processable_texts)", "Processable corpora (processable_texts)"),
                    options = processableCorpora,
                    selected = selectedCorpus,
                    onSelect = { selectedCorpus = it }
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val cName = selectedCorpus ?: return@Button
                        runTask(lang.pick("Teljes korpusz elemzés ($cName)", "Complete corpus analysis ($cName)")) {
                            completeCorpusAnalysis(cName)
                            refreshResultFilesFor(cName)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tr("ÖSSZES KORPUSZ ELEMZÉS FUTTATÁSA (cca)", "RUN ALL CORPUS ANALYSES (cca)"),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            SectionCard(
                title = tr("2. Egyedi korpusz-elemző modulok", "2. Individual Corpus Analysis Modules"),
                subtitle = tr("Külön-külön is lefuttatható statisztikai generátorok", "Statistical generators that can be executed individually")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val cName = selectedCorpus ?: return@FilledTonalButton
                            runTask(lang.pick("Karaktergyakoriság ($cName)", "Letter frequency ($cName)")) {
                                letterfrequency(cName)
                                refreshResultFilesFor(cName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text(tr("Karaktergyakoriság elemzés", "Letter Frequency Analysis"))
                    }

                    FilledTonalButton(
                        onClick = {
                            val cName = selectedCorpus ?: return@FilledTonalButton
                            runTask(lang.pick("Bigram elemzés ($cName)", "Bigram analysis ($cName)")) {
                                bigramanalysis(cName)
                                refreshResultFilesFor(cName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text(tr("Bigram elemzés", "Bigram Frequency Analysis"))
                    }

                    FilledTonalButton(
                        onClick = {
                            val cName = selectedCorpus ?: return@FilledTonalButton
                            runTask(lang.pick("Trigram elemzés ($cName)", "Trigram analysis ($cName)")) {
                                trigramanalysis(cName)
                                refreshResultFilesFor(cName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text(tr("Trigram elemzés", "Trigram Frequency Analysis"))
                    }

                    FilledTonalButton(
                        onClick = {
                            val cName = selectedCorpus ?: return@FilledTonalButton
                            runTask(lang.pick("Szóközös trigram elemzés ($cName)", "Space-included trigram analysis ($cName)")) {
                                spaceIncludedTrigramAnalysis(cName)
                                refreshResultFilesFor(cName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text(tr("Szóközös trigram elemzés", "Space-Included Trigram Analysis"))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IntSettingField(
                            label = tr("Top N szó", "Top N words"),
                            value = wordFreqLimit,
                            onValueChange = { wordFreqLimit = it.coerceAtLeast(1) },
                            modifier = Modifier.width(120.dp)
                        )
                        FilledTonalButton(
                            onClick = {
                                val cName = selectedCorpus ?: return@FilledTonalButton
                                runTask(lang.pick("Szógyakoriság Top $wordFreqLimit ($cName)", "Word frequency Top $wordFreqLimit ($cName)")) {
                                    calculatewordfrequency(cName, wordFreqLimit)
                                    refreshResultFilesFor(cName)
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text(tr("Szógyakoriság számítása", "Word Frequency Calculation"))
                        }
                    }
                }
            }

            SectionCard(
                title = tr("3. Korpusz előkészítő segédeszközök", "3. Corpus Preparation Utilities"),
                subtitle = tr("Előfeldolgozás (raw_texts) és véletlen mintavételezés", "Preprocessing (raw_texts) and random line sampling")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DropdownSelector(
                        label = tr("Nyers fájl (raw_texts)", "Raw file (raw_texts)"),
                        options = rawTexts,
                        selected = selectedRawFile,
                        onSelect = { selectedRawFile = it },
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            val raw = selectedRawFile ?: return@FilledTonalButton
                            runTask(lang.pick("Előfeldolgozás ($raw)", "Preprocess ($raw)")) {
                                stripIndicesAndSave(File("raw_texts/$raw"), File("processable_texts/$raw"))
                                processableCorpora = listProcessableCorpora()
                            }
                        },
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text("Preprocess")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IntSettingField(
                        label = tr("Sorok száma", "Line count"),
                        value = randomSubsetLines,
                        onValueChange = { randomSubsetLines = it.coerceAtLeast(1) },
                        modifier = Modifier.width(130.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val cName = selectedCorpus ?: return@OutlinedButton
                            runTask(lang.pick("Véletlen $randomSubsetLines sor ($cName)", "Random $randomSubsetLines lines ($cName)")) {
                                createRandomSubset("processable_texts/$cName", randomSubsetLines)
                                processableCorpora = listProcessableCorpora()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text(tr("Véletlen sorok mintavételezése", "Sample Random Lines"))
                    }
                }
            }
        }

        // Jobb oldali előnézeti kártya
        SectionCard(
            title = tr("Generált Korpusz Statisztikák Böngészése", "Browse Generated Corpus Statistics"),
            subtitle = selectedCorpus?.let {
                tr("Mappa: analysis_results/$it", "Folder: analysis_results/$it")
            } ?: tr("Válassz egy korpuszt", "Select a corpus"),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            headerTrailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tableViewMode,
                        onClick = { tableViewMode = !tableViewMode },
                        label = {
                            Text(
                                text = if (tableViewMode) tr("Táblázat nézet", "Table View") else tr("Szöveges nézet", "Text View"),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (tableViewMode) Icons.Default.TableRows else Icons.Default.Notes,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.height(32.dp)
                    )
                    OutlinedButton(
                        onClick = { refreshResultFilesFor(selectedCorpus) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Frissítés", "Refresh"), fontSize = 12.sp)
                    }
                }
            }
        ) {
            if (resultFiles.isNotEmpty()) {
                DropdownSelector(
                    label = tr("Eredményfájl kiválasztása", "Select result file"),
                    options = resultFiles,
                    selected = selectedResultFile,
                    onSelect = { file ->
                        selectedResultFile = file
                        selectedCorpus?.let { loadResultFileText(it, file) }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            val dataRows = remember(parsedTableEntries) {
                parsedTableEntries.filterIsInstance<CorpusTableEntry.DataRow>()
            }
            val isMultiColTable = tableViewMode && dataRows.isNotEmpty()
            val hasCategoryCol = remember(dataRows) { dataRows.any { it.category != null } }
            val hasCountCol = remember(dataRows) { dataRows.any { it.count.isNotEmpty() } }

            if (isMultiColTable) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Táblázat fejléc
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.width(64.dp)
                            )
                            Text(
                                text = tr("Elem / N-gram / Szó", "Item / N-gram / Word"),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1.3f)
                            )
                            Text(
                                text = tr("Gyakoriság (%)", "Frequency (%)"),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            if (hasCategoryCol) {
                                Text(
                                    text = tr("Kategória", "Category"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(0.9f)
                                )
                            }
                            if (hasCountCol) {
                                Text(
                                    text = tr("Előfordulás (db)", "Count"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                        val tableListState = rememberLazyListState()
                        LaunchedEffect(selectedResultFile, selectedCorpus) {
                            tableListState.scrollToItem(0)
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = tableListState,
                                modifier = Modifier.fillMaxSize().padding(end = 14.dp)
                            ) {
                                items(parsedTableEntries.size) { idx ->
                                    when (val entry = parsedTableEntries[idx]) {
                                        is CorpusTableEntry.SectionHeader -> {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = tr(entry.textHu, entry.textEn),
                                                    style = MaterialTheme.typography.labelLarge.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                        is CorpusTableEntry.DataRow -> {
                                            val bgColor = if (entry.rank % 2 == 1) {
                                                Color.Transparent
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(bgColor)
                                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${entry.rank}.",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    ),
                                                    modifier = Modifier.width(64.dp)
                                                )
                                                Text(
                                                    text = entry.item,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    ),
                                                    modifier = Modifier.weight(1.3f)
                                                )
                                                Text(
                                                    text = entry.percentage,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = FontFamily.Monospace
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (hasCategoryCol) {
                                                    Text(
                                                        text = entry.category ?: "-",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.tertiary
                                                        ),
                                                        modifier = Modifier.weight(0.9f)
                                                    )
                                                }
                                                if (hasCountCol) {
                                                    Text(
                                                        text = entry.count,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        ),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(tableListState),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(vertical = 4.dp, horizontal = 2.dp)
                            )
                        }
                    }
                }
            } else {
                val displayedPreview = if (previewContent.isNotEmpty()) {
                    previewContent
                } else {
                    tr(
                        "Az 'analysis_results/${selectedCorpus ?: ""}' mappa még nem tartalmaz elemzési fájlokat.\nFuttasd az Összes Korpusz Elemzést vagy valamelyik egyedi modult!",
                        "The 'analysis_results/${selectedCorpus ?: ""}' folder does not contain any analysis files yet.\nRun All Corpus Analyses or one of the individual modules!"
                    )
                }
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 12.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll)
                        ) {
                            Text(
                                text = displayedPreview,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(vScroll),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        )
                        HorizontalScrollbar(
                            adapter = rememberScrollbarAdapter(hScroll),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 16.dp, bottom = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// 2. KÉPERNYŐ: LAYOUT ELEMZŐ ESZKÖZÖK
// =========================================================================

@Composable
fun LayoutAnalyzerScreen(
    runTask: (String, suspend () -> Unit) -> Unit
) {
    val lang = LocalAppLanguage.current
    var selectedFolder by remember { mutableStateOf("keyboards") }
    var layouts by remember(selectedFolder) { mutableStateOf(listKeyboardLayouts(selectedFolder)) }
    var selectedLayout by remember(layouts) { mutableStateOf(layouts.firstOrNull()) }

    var corpora by remember { mutableStateOf(listAnalyzedCorpora()) }
    var selectedCorpus by remember {
        mutableStateOf(
            corpora.firstOrNull { it == "huszonkettoHuszonnegyeswikipediahuszonegy.txt" }
                ?: corpora.firstOrNull()
        )
    }

    var analysisResult by remember { mutableStateOf<LayoutAnalysisResult?>(null) }
    var heatmapBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var heatmapFile by remember { mutableStateOf<File?>(null) }

    fun refreshPreviewHeatmap(layoutName: String, folder: String) {
        val file = LayoutParser.resolveLayoutFile(layoutName, folder) ?: return
        runCatching {
            val layoutData = LayoutParser.parseLayout(file)
            val tempImg = File.createTempFile("compose_preview_heatmap_", ".png")
            tempImg.deleteOnExit()
            renderCharEffortlayout(layoutData, tempImg.absolutePath)
            heatmapFile = tempImg
            heatmapBitmap = loadComposeBitmap(tempImg)
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bal oldali vezérlőpult
        Column(
            modifier = Modifier
                .width(460.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard(
                title = tr("1. Forrásmappa, Layout és Korpusz", "1. Source Folder, Layout and Corpus"),
                subtitle = tr(
                    "A billentyűzetelemző a kiválasztott folder paraméterben keres",
                    "The layout analyzer searches within the selected folder parameter"
                ),
                headerTrailing = {
                    IconButton(onClick = {
                        layouts = listKeyboardLayouts(selectedFolder)
                        corpora = listAnalyzedCorpora()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = tr("Listák frissítése", "Refresh lists"))
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFolder == "keyboards",
                        onClick = { selectedFolder = "keyboards" },
                        label = { Text(tr("Alap (keyboards)", "Base (keyboards)")) },
                        leadingIcon = if (selectedFolder == "keyboards") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedFolder == "generatedkeyboards",
                        onClick = { selectedFolder = "generatedkeyboards" },
                        label = { Text(tr("Generált (generatedkeyboards)", "Generated (generatedkeyboards)")) },
                        leadingIcon = if (selectedFolder == "generatedkeyboards") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                DropdownSelector(
                    label = tr("Billentyűzetkiosztás ($selectedFolder)", "Keyboard Layout ($selectedFolder)"),
                    options = layouts,
                    selected = selectedLayout,
                    onSelect = { selectedLayout = it }
                )

                Spacer(Modifier.height(8.dp))
                DropdownSelector(
                    label = tr("Korpusz", "Corpus"),
                    options = corpora,
                    selected = selectedCorpus,
                    onSelect = { selectedCorpus = it }
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val lName = selectedLayout ?: return@Button
                        val cName = selectedCorpus ?: return@Button
                        val folder = selectedFolder
                        runTask(lang.pick("Teljes layout elemzés ($lName)", "Complete layout analysis ($lName)")) {
                            val res = completeLayoutAnalysis(lName, cName, folder = folder, verbose = true)
                            analysisResult = res
                            refreshPreviewHeatmap(lName, folder)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tr("ÖSSZES ELEMZÉS FUTTATÁSA (cla)", "RUN ALL ANALYSES (cla)"),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            SectionCard(
                title = tr("2. Egyedi Layout Elemzések", "2. Individual Layout Analyses"),
                subtitle = tr(
                    "Részletes statisztikák az alsó konzolra és a hőtérképre",
                    "Detailed statistics output to the live console and heatmap"
                )
            ) {
                val singleAnalyses = listOf<Pair<String, (String, String, String) -> Unit>>(
                    tr("SFB elemzés", "SFB Analysis") to { l, c, f -> runSimpleSfbAnalysis(l, c, f) },
                    tr("Alternálás", "Alternation") to { l, c, f -> runAlternationAnalysis(l, c, f) },
                    tr("2-Roll elemzés", "2-Roll Analysis") to { l, c, f -> runRollAnalysis(l, c, f) },
                    tr("3-Roll elemzés", "3-Roll Analysis") to { l, c, f -> run3RollAnalysis(l, c, f) },
                    tr("Redirect elemzés", "Redirect Analysis") to { l, c, f -> runRedirectAnalysis(l, c, f) },
                    tr("Space SFS-1", "Space SFS-1") to { l, c, f -> runSpaceSfs1Analysis(l, c, f) },
                    tr("Kéz egyensúly", "Hand Balance") to { l, c, f -> runHandBalanceAnalysis(l, c, f) },
                    tr("Sorok terhelése", "Row Usage") to { l, c, f -> runRowUsageAnalysis(l, c, f) },
                    tr("Pinky Off", "Pinky Off") to { l, c, f -> runPinkyOffAnalysis(l, c, f) },
                    tr("Lateral Stretch", "Lateral Stretch") to { l, c, f -> runLateralStretchAnalysis(l, c, f) },
                    tr("Pinky Stretch", "Pinky Stretch") to { l, c, f -> runPinkyStretchAnalysis(l, c, f) },
                    tr("Full Scissor", "Full Scissor") to { l, c, f -> runFullScissorAnalysis(l, c, f) },
                    tr("Half Scissor", "Half Scissor") to { l, c, f -> runHalfScissorAnalysis(l, c, f) }
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pair in singleAnalyses.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for ((label, runner) in pair) {
                                FilledTonalButton(
                                    onClick = {
                                        val lName = selectedLayout ?: return@FilledTonalButton
                                        val cName = selectedCorpus ?: return@FilledTonalButton
                                        val folder = selectedFolder
                                        runTask("$label ($lName)") {
                                            runner(lName, cName, folder)
                                            refreshPreviewHeatmap(lName, folder)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        val cName = selectedCorpus ?: return@OutlinedButton
                        val clean = if (cName.endsWith(".txt")) cName else "$cName.txt"
                        runTask(lang.pick("CSV összesítő generálása ($clean)", "Generate Summary CSV ($clean)")) {
                            generateSummaryCsv(clean)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("CSV Összesítő táblázat generálása", "Generate Summary CSV Table"))
                }
            }
        }

        // Jobb oldali megjelenítő (Hőtérkép + Kártyás Statisztikák)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeatmapViewerCard(
                title = tr("Billentyűzetkiosztás és Karakter-elérési Hőtérkép", "Keyboard Layout and Character Reach Heatmap"),
                imageBitmap = heatmapBitmap,
                imageFile = heatmapFile,
                emptyMessage = tr(
                    "Válassz ki egy layoutot és futtasd az elemzést a hőtérkép megjelenítéséhez.",
                    "Select a layout and run the analysis to display the heatmap."
                ),
                minHeight = 390.dp
            )

            LayoutStatsSummaryCard(result = analysisResult)
        }
    }
}

@Composable
fun LayoutStatsSummaryCard(
    result: LayoutAnalysisResult?,
    extraSubtitle: String? = null
) {
    val lang = LocalAppLanguage.current
    SectionCard(
        title = if (result != null) {
            tr("Elemzési statisztikák — ${result.layoutName}", "Analysis Statistics — ${result.layoutName}")
        } else {
            tr("Elemzési statisztikák", "Analysis Statistics")
        },
        subtitle = extraSubtitle ?: tr(
            "Futtasd a teljes elemzést a metrikák megjelenítéséhez",
            "Run the complete analysis to display the metrics"
        )
    ) {
        if (result == null) {
            Text(
                text = tr("Még nincs megjeleníthető statisztika.", "No statistics to display yet."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Kiemelt fő mutatók (Badge sor)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricBadge(
                    title = tr("Total Effort (Összpontszám)", "Total Effort (Overall Score)"),
                    value = String.format("%.4f", result.totalEffort),
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricBadge(
                    title = tr("SFB (Egyujjas Bigram)", "SFB (Same-Finger Bigram)"),
                    value = String.format("%.3f%%", result.sfbPercentage),
                    accentColor = Color(0xFFDC2626),
                    modifier = Modifier.weight(1f)
                )
                MetricBadge(
                    title = tr("Alapsor (Home Row)", "Home Row Usage"),
                    value = String.format("%.2f%%", result.homeRowPercentage),
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                MetricBadge(
                    title = tr("Kéz arány (Bal / Jobb)", "Hand Balance (Left / Right)"),
                    value = String.format("%.1f%% / %.1f%%", result.handBalanceLeft, result.handBalanceRight),
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Részletes táblázatos felsorolás 2 oszlopban
            val rows = buildList {
                add("Char Reach Effort" to String.format("%.4f", result.charReachEffort))
                add("Base Gravity Effort" to String.format("%.4f", result.baseGravityEffort))
                add(lang.pick("Space SFS-1 büntetés", "Space SFS-1 Penalty") to String.format("%.4f", result.spaceSfs1Penalty))
                add(lang.pick("Pinky Off (Kisujj alapgomb kívül)", "Pinky Off (Outside Home Key)") to String.format("%.4f%%", result.pinkyOffPercentage))
                add("Lateral Stretch Bigram (LSB)" to String.format("%.4f%%", result.lateralStretchPercentage))
                add("Pinky Stretch Bigram (PSB)" to String.format("%.4f%%", result.pinkystretchPercentage))
                add("Full Scissor Bigram (FSB)" to String.format("%.4f%%", result.fullScissorPercentage))
                add("Half Scissor Bigram (HSB)" to String.format("%.4f%%", result.halfScissorPercentage))
                add(lang.pick("Alternálás (1-1-1)", "Alternation (1-1-1)") to String.format("%.4f%%", result.alternationPercentage))
                add(lang.pick("2-Roll (2-1 és 1-2)", "2-Roll (2-1 and 1-2)") to String.format("%.4f%%", result.rollPercentage))
                add(lang.pick("3-Roll (Egykezes gördülés)", "3-Roll (One-Handed Roll)") to String.format("%.4f%%", result.threerollPercentage))
                add(lang.pick("Redirect (Irányváltás)", "Redirect (Direction Change)") to String.format("%.4f%%", result.redirectPercentage))
                result.rowPercentages.forEachIndexed { idx, pct ->
                    add(lang.pick("$idx. sor terhelése", "Row $idx Usage") to String.format("%.2f%%", pct))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pair in rows.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for ((k, v) in pair) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = k,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = v,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 3. KÉPERNYŐ: FIZIKAI EFFORT MODELL BEÁLLÍTÁSOK
// =========================================================================

private data class FingerUiState(
    val finger: Finger,
    val baseEffort: Double,
    val homeOffsetX: Double,
    val homeOffsetY: Double,
    val maxComfortReachY: Double,
    val maxComfortReachX: Double
)

@Composable
fun EffortModelScreen(
    runTask: (String, suspend () -> Unit) -> Unit
) {
    val lang = LocalAppLanguage.current
    fun snapshotFingers(): List<FingerUiState> = Finger.entries.map {
        FingerUiState(it, it.baseEffort, it.homeOffsetX, it.homeOffsetY, it.maxComfortReachY, it.maxComfortReachX)
    }

    var fingerStates by remember { mutableStateOf(snapshotFingers()) }

    var lateralPx by remember { mutableStateOf(EffortGeneratorSettings.lateralPenaltyPx) }
    var distancePd by remember { mutableStateOf(EffortGeneratorSettings.distancePenaltyPd) }
    var actMargin by remember { mutableStateOf(EffortGeneratorSettings.activationMargin) }
    var wallY by remember { mutableStateOf(EffortGeneratorSettings.biomechanicalWallMultiplierY) }
    var wallX by remember { mutableStateOf(EffortGeneratorSettings.biomechanicalWallMultiplierX) }
    var boxInKeySpace by remember { mutableStateOf(EffortGeneratorSettings.boxInKeySpace) }
    var staticOffsetIndex by remember {
        mutableStateOf(
            when (EffortGeneratorSettings.overrideApplyStaticFingerOffsets) {
                true -> 1
                false -> 2
                else -> 0
            }
        )
    }

    // KeyboardHalf geometria állapotok
    val templates = remember { listKeyboardLayouts("keyboards") }
    var selectedTemplate by remember { mutableStateOf(templates.firstOrNull()) }
    var generatorModelIndex by remember { mutableStateOf(0) }
    var staggerType by remember { mutableStateOf(StaggerType.COLUMNAR_STAGGER) }
    var numRows by remember { mutableStateOf(5) }
    var homeRow by remember { mutableStateOf(2) }
    var angleDegrees by remember { mutableStateOf(0.0) }
    var optimalFinger by remember { mutableStateOf(false) }

    var leftOffsetsStr by remember { mutableStateOf("0.50, 0.50, 0.25, 0.00, 0.25, 0.38") }
    var leftFingersStr by remember { mutableStateOf("P, P, R, M, I, I") }
    var leftAnchorsStr by remember { mutableStateOf("1, 1, 2, 3, 4, 4") }

    var rightOffsetsStr by remember { mutableStateOf("0.38, 0.25, 0.00, 0.25, 0.50, 0.50") }
    var rightFingersStr by remember { mutableStateOf("I, I, M, R, P, P") }
    var rightAnchorsStr by remember { mutableStateOf("1, 1, 2, 3, 4, 4") }

    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var matrixText by remember { mutableStateOf("") }
    var statusBannerHu by remember { mutableStateOf<String?>(null) }
    var statusBannerEn by remember { mutableStateOf<String?>(null) }
    var statusBannerTick by remember { mutableStateOf(0) }

    LaunchedEffect(statusBannerTick) {
        if (statusBannerHu != null) {
            delay(6000L)
            statusBannerHu = null
            statusBannerEn = null
        }
    }

    fun applyGlobalSettingsToMemory() {
        for (fs in fingerStates) {
            fs.finger.baseEffort = fs.baseEffort
            fs.finger.homeOffsetX = fs.homeOffsetX
            fs.finger.homeOffsetY = fs.homeOffsetY
            fs.finger.maxComfortReachY = fs.maxComfortReachY
            fs.finger.maxComfortReachX = fs.maxComfortReachX
        }
        EffortGeneratorSettings.lateralPenaltyPx = lateralPx
        EffortGeneratorSettings.distancePenaltyPd = distancePd
        EffortGeneratorSettings.activationMargin = actMargin
        EffortGeneratorSettings.biomechanicalWallMultiplierY = wallY
        EffortGeneratorSettings.biomechanicalWallMultiplierX = wallX
        EffortGeneratorSettings.boxInKeySpace = boxInKeySpace
        EffortGeneratorSettings.overrideApplyStaticFingerOffsets = when (staticOffsetIndex) {
            1 -> true
            2 -> false
            else -> null
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bal oldali beállítások
        Column(
            modifier = Modifier
                .width(560.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard(
                title = tr("1. Ujj (Finger) anatómiai paraméterek", "1. Finger Anatomical Parameters"),
                subtitle = tr(
                    "KeyboardHalf.kt — Alap terhelés (Pf), ujj-offsetek és komfortzóna határok",
                    "KeyboardHalf.kt — Base effort (Pf), finger offsets and comfort zone limits"
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fingerStates.forEachIndexed { idx, fs ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = fs.finger.name,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DoubleSettingField(
                                        label = "baseEffort",
                                        value = fs.baseEffort,
                                        onValueChange = { v ->
                                            fingerStates = fingerStates.toMutableList().also { it[idx] = fs.copy(baseEffort = v) }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DoubleSettingField(
                                        label = "homeOffX",
                                        value = fs.homeOffsetX,
                                        onValueChange = { v ->
                                            fingerStates = fingerStates.toMutableList().also { it[idx] = fs.copy(homeOffsetX = v) }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DoubleSettingField(
                                        label = "homeOffY",
                                        value = fs.homeOffsetY,
                                        onValueChange = { v ->
                                            fingerStates = fingerStates.toMutableList().also { it[idx] = fs.copy(homeOffsetY = v) }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DoubleSettingField(
                                        label = "maxReachY",
                                        value = fs.maxComfortReachY,
                                        onValueChange = { v ->
                                            fingerStates = fingerStates.toMutableList().also { it[idx] = fs.copy(maxComfortReachY = v) }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DoubleSettingField(
                                        label = "maxReachX",
                                        value = fs.maxComfortReachX,
                                        onValueChange = { v ->
                                            fingerStates = fingerStates.toMutableList().also { it[idx] = fs.copy(maxComfortReachX = v) }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SectionCard(
                title = tr("2. EffortLayoutGenerator paraméterei", "2. EffortLayoutGenerator Parameters"),
                subtitle = tr(
                    "EffortLayoutGenerator.kt — Globális effort számítási szorzók",
                    "EffortLayoutGenerator.kt — Global effort calculation multipliers"
                )
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DoubleSettingField(tr("Oldalirányú büntetés (Px)", "Lateral Penalty (Px)"), lateralPx, { lateralPx = it }, Modifier.weight(1f))
                    DoubleSettingField(tr("Távolság büntetés (Pd)", "Distance Penalty (Pd)"), distancePd, { distancePd = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DoubleSettingField(tr("Aktivációs margó", "Activation Margin"), actMargin, { actMargin = it }, Modifier.weight(1f))
                    DoubleSettingField(tr("Biomech. fal Y (25.0)", "Biomech. Wall Y (25.0)"), wallY, { wallY = it }, Modifier.weight(1f))
                    DoubleSettingField(tr("Biomech. fal X (5.5)", "Biomech. Wall X (5.5)"), wallX, { wallX = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                val staticOffsetOptions = listOf(0, 1, 2)
                DropdownSelector(
                    label = tr(
                        "Statikus ujj-hossz kompenzáció (applyStaticFingerOffsets)",
                        "Static Finger Length Compensation (applyStaticFingerOffsets)"
                    ),
                    options = staticOffsetOptions,
                    selected = staticOffsetIndex,
                    onSelect = { staticOffsetIndex = it },
                    itemLabel = { idx ->
                        when (idx) {
                            1 -> lang.pick("Mindig IGEN (true)", "Always YES (true)")
                            2 -> lang.pick("Mindig NEM (false)", "Always NO (false)")
                            else -> lang.pick("Automatikus (Stagger alapján)", "Automatic (Based on Stagger)")
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = boxInKeySpace, onCheckedChange = { boxInKeySpace = it })
                    Text(
                        text = tr(
                            "Box in Key Space (Colemak-DH kompatibilis tengelyforgás)",
                            "Box in Key Space (Colemak-DH compatible axis rotation)"
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (statusBannerHu != null && statusBannerEn != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = tr(statusBannerHu!!, statusBannerEn!!),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            Finger.resetDefaults()
                            EffortGeneratorSettings.resetDefaults()
                            fingerStates = snapshotFingers()
                            lateralPx = EffortGeneratorSettings.lateralPenaltyPx
                            distancePd = EffortGeneratorSettings.distancePenaltyPd
                            actMargin = EffortGeneratorSettings.activationMargin
                            wallY = EffortGeneratorSettings.biomechanicalWallMultiplierY
                            wallX = EffortGeneratorSettings.biomechanicalWallMultiplierX
                            boxInKeySpace = EffortGeneratorSettings.boxInKeySpace
                            staticOffsetIndex = 0
                            statusBannerHu = "Alapértelmezett fizikai modell értékek visszaállítva."
                            statusBannerEn = "Default physical model parameters restored."
                            statusBannerTick++
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Alapértékek", "Restore Defaults"))
                    }
                    Button(
                        onClick = {
                            applyGlobalSettingsToMemory()
                            statusBannerHu = "Globális fizikai effort modell frissítve a memóriában!"
                            statusBannerEn = "Global physical effort model updated in memory!"
                            statusBannerTick++
                            println(lang.pick("Fizikai effort modell paraméterei frissítve a memóriában.", "Physical effort model parameters updated in memory."))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Alkalmazás globálisan", "Apply Globally"))
                    }
                }
            }

            SectionCard(
                title = tr("3. KeyboardHalf fizikai geometria & Hőtérkép teszt", "3. KeyboardHalf Physical Geometry & Heatmap Test"),
                subtitle = tr(
                    "Sablon betöltése vagy egyedi oszlop-eltolások megadása",
                    "Load a hardware template or configure custom column offsets"
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DropdownSelector(
                        label = tr("Sablon YAML (keyboards)", "Template YAML (keyboards)"),
                        options = templates,
                        selected = selectedTemplate,
                        onSelect = { selectedTemplate = it },
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            val sel = selectedTemplate ?: return@FilledTonalButton
                            val f = LayoutParser.resolveLayoutFile(sel, "keyboards") ?: return@FilledTonalButton
                            runCatching {
                                val data = LayoutParser.parseLayout(f)
                                val hw = data.hardwareYaml
                                staggerType = when (hw.staggerType.lowercase()) {
                                    "columnar_stagger" -> StaggerType.COLUMNAR_STAGGER
                                    "row_stagger" -> StaggerType.ROW_STAGGER
                                    else -> StaggerType.MATRIX
                                }
                                numRows = hw.numRows
                                homeRow = hw.homeRowIndex
                                angleDegrees = hw.angleOfApproachDegrees
                                optimalFinger = hw.optimalFingerAssignment
                                leftOffsetsStr = hw.left.offsets.joinToString(", ")
                                leftFingersStr = hw.left.fingers.joinToString(", ")
                                leftAnchorsStr = hw.left.anchors.joinToString(", ")
                                rightOffsetsStr = hw.right.offsets.joinToString(", ")
                                rightFingersStr = hw.right.fingers.joinToString(", ")
                                rightAnchorsStr = hw.right.anchors.joinToString(", ")
                            }
                        },
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text(tr("Betöltés", "Load"))
                    }
                }

                Spacer(Modifier.height(8.dp))
                DropdownSelector(
                    label = tr("Generátor típus", "Generator Type"),
                    options = listOf(0, 1),
                    selected = generatorModelIndex,
                    onSelect = { generatorModelIndex = it },
                    itemLabel = { idx ->
                        if (idx == 0) {
                            lang.pick(
                                "CustomKeyboardEffortGenerator (Biomechanikai fallal)",
                                "CustomKeyboardEffortGenerator (With Biomechanical Wall)"
                            )
                        } else {
                            lang.pick(
                                "ColemakDhEffortGenerator (Klasszikus)",
                                "ColemakDhEffortGenerator (Classic)"
                            )
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownSelector(
                        label = "StaggerType",
                        options = StaggerType.entries,
                        selected = staggerType,
                        onSelect = { staggerType = it },
                        modifier = Modifier.weight(2f)
                    )
                    DoubleSettingField(tr("Szög (°)", "Angle (°)"), angleDegrees, { angleDegrees = it }, Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntSettingField(tr("Sorok (numRows)", "Rows (numRows)"), numRows, { numRows = it.coerceIn(2, 8) }, Modifier.weight(1f))
                    IntSettingField("HomeRow", homeRow, { homeRow = it.coerceIn(0, 7) }, Modifier.weight(1f))
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = optimalFinger, onCheckedChange = { optimalFinger = it })
                    Text("Optimal Finger Assignment", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(leftOffsetsStr, { leftOffsetsStr = it }, label = { Text(tr("Bal kéz offsets", "Left Hand Offsets")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(leftFingersStr, { leftFingersStr = it }, label = { Text(tr("Bal kéz fingers (P,R,M,I,T)", "Left Hand Fingers (P,R,M,I,T)")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(leftAnchorsStr, { leftAnchorsStr = it }, label = { Text(tr("Bal kéz anchors", "Left Hand Anchors")) }, singleLine = true, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(rightOffsetsStr, { rightOffsetsStr = it }, label = { Text(tr("Jobb kéz offsets", "Right Hand Offsets")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(rightFingersStr, { rightFingersStr = it }, label = { Text(tr("Jobb kéz fingers (I,M,R,P,T)", "Right Hand Fingers (I,M,R,P,T)")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(rightAnchorsStr, { rightAnchorsStr = it }, label = { Text(tr("Jobb kéz anchors", "Right Hand Anchors")) }, singleLine = true, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        applyGlobalSettingsToMemory()
                        runTask(lang.pick("Fizikai Effort Hőtérkép generálása", "Generate Physical Effort Heatmap")) {
                            fun parseFingerCode(code: String): Finger = when (code.uppercase()) {
                                "P", "PINKY" -> Finger.PINKY
                                "R", "RING" -> Finger.RING
                                "M", "MIDDLE" -> Finger.MIDDLE
                                "I", "INDEX" -> Finger.INDEX
                                "T", "THUMB" -> Finger.THUMB
                                else -> throw IllegalArgumentException("Unknown finger code: '$code'")
                            }

                            val lOff = leftOffsetsStr.split(",").map { it.trim().toDouble() }
                            val lFin = leftFingersStr.split(",").map { parseFingerCode(it.trim()) }
                            val lAnc = leftAnchorsStr.split(",").map { it.trim().toInt() }

                            val rOff = rightOffsetsStr.split(",").map { it.trim().toDouble() }
                            val rFin = rightFingersStr.split(",").map { parseFingerCode(it.trim()) }
                            val rAnc = rightAnchorsStr.split(",").map { it.trim().toInt() }

                            val leftHalf = KeyboardHalf(Hand.LEFT, staggerType, numRows, lFin.size, lOff, homeRow)
                            val rightHalf = KeyboardHalf(Hand.RIGHT, staggerType, numRows, rFin.size, rOff, homeRow)

                            val leftConfigs = lFin.indices.map { i -> ColumnConfig(i, lAnc[i], lFin[i]) }
                            val rightConfigs = rFin.indices.map { i -> ColumnConfig(i, rAnc[i], rFin[i]) }

                            val useCustom = generatorModelIndex == 0
                            val leftEffort = if (useCustom) {
                                CustomKeyboardEffortGenerator(
                                    keyboardHalf = leftHalf,
                                    columnConfigs = leftConfigs,
                                    angleOfApproachDegrees = angleDegrees,
                                    useOptimalFingerAssignment = optimalFinger
                                ).generateEffortMatrix()
                            } else {
                                ColemakDhEffortGenerator(
                                    keyboardHalf = leftHalf,
                                    columnConfigs = leftConfigs,
                                    angleOfApproachDegrees = angleDegrees,
                                    lateralPenaltyPx = EffortGeneratorSettings.lateralPenaltyPx,
                                    distancePenaltyPd = EffortGeneratorSettings.distancePenaltyPd,
                                    activationMargin = EffortGeneratorSettings.activationMargin,
                                    useOptimalFingerAssignment = optimalFinger,
                                    boxInKeySpace = EffortGeneratorSettings.boxInKeySpace
                                ).generateEffortMatrix()
                            }

                            val rightEffort = if (useCustom) {
                                CustomKeyboardEffortGenerator(
                                    keyboardHalf = rightHalf,
                                    columnConfigs = rightConfigs,
                                    angleOfApproachDegrees = angleDegrees,
                                    useOptimalFingerAssignment = optimalFinger
                                ).generateEffortMatrix()
                            } else {
                                ColemakDhEffortGenerator(
                                    keyboardHalf = rightHalf,
                                    columnConfigs = rightConfigs,
                                    angleOfApproachDegrees = angleDegrees,
                                    lateralPenaltyPx = EffortGeneratorSettings.lateralPenaltyPx,
                                    distancePenaltyPd = EffortGeneratorSettings.distancePenaltyPd,
                                    activationMargin = EffortGeneratorSettings.activationMargin,
                                    useOptimalFingerAssignment = optimalFinger,
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

                            previewFile = outFile
                            previewBitmap = loadComposeBitmap(outFile)

                            val sb = StringBuilder()
                            sb.appendLine(
                                lang.pick(
                                    "=== GENERÁLT FIZIKAI EFFORT MÁTRIX ($staggerType, numRows=$numRows, homeRow=$homeRow) ===\n",
                                    "=== GENERATED PHYSICAL EFFORT MATRIX ($staggerType, numRows=$numRows, homeRow=$homeRow) ===\n"
                                )
                            )
                            for (r in 0 until numRows) {
                                val lStr = leftEffort[r].joinToString(" ") { String.format("%5.2f", it) }
                                val rStr = rightEffort[r].joinToString(" ") { String.format("%5.2f", it) }
                                val tag = if (r == homeRow) "  <-- HOME ROW" else if (r == numRows - 1) "  <-- THUMB ROW" else ""
                                sb.appendLine("$lStr   |   $rStr$tag")
                            }
                            matrixText = sb.toString()
                            println(
                                lang.pick(
                                    "Fizikai effort hőtérkép elmentve: ${outFile.absolutePath}",
                                    "Physical effort heatmap saved: ${outFile.absolutePath}"
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tr("Hőtérkép és Effort Mátrix Generálása", "Generate Heatmap and Effort Matrix"),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Jobb oldal: Hőtérkép + Számított mátrix
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeatmapViewerCard(
                title = tr(
                    "Fizikai Effort Hőtérkép előnézet (custom_effort_heatmap.png)",
                    "Physical Effort Heatmap Preview (custom_effort_heatmap.png)"
                ),
                imageBitmap = previewBitmap,
                imageFile = previewFile,
                emptyMessage = tr(
                    "Kattints a 'Hőtérkép és Effort Mátrix Generálása' gombra az előnézethez.",
                    "Click 'Generate Heatmap and Effort Matrix' to preview the physical effort layout."
                ),
                minHeight = 380.dp
            )

            SectionCard(
                title = tr("Számított Effort Mátrix (Bal kéz | Jobb kéz)", "Calculated Effort Matrix (Left Hand | Right Hand)"),
                subtitle = tr(
                    "A gombok fizikai elérési nehézsége billentyűnként",
                    "Physical reach effort values per key"
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val displayedMatrix = if (matrixText.isNotEmpty()) {
                        matrixText
                    } else {
                        tr(
                            "Kattints a 'Hőtérkép és Effort Mátrix Generálása' gombra az előnézethez.",
                            "Click 'Generate Heatmap and Effort Matrix' to preview the matrix."
                        )
                    }
                    Text(
                        text = displayedMatrix,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================
// 4. KÉPERNYŐ: LAYOUT GENERÁLÁS & SÚLYOK TESTRESZABÁSA
// =========================================================================

@Composable
fun LayoutGenerationScreen(
    runTask: (String, suspend () -> Unit) -> Unit
) {
    val lang = LocalAppLanguage.current
    var selectedFolder by remember { mutableStateOf("keyboards") }
    var layouts by remember(selectedFolder) { mutableStateOf(listKeyboardLayouts(selectedFolder)) }
    var selectedLayout by remember(layouts) { mutableStateOf(layouts.firstOrNull()) }

    var corpora by remember { mutableStateOf(listAnalyzedCorpora()) }
    var selectedCorpus by remember {
        mutableStateOf(
            corpora.firstOrNull { it == "huszonkettoHuszonnegyeswikipediahuszonegy.txt" }
                ?: corpora.firstOrNull()
        )
    }

    var selectedAlgoIndex by remember { mutableStateOf(0) } // 0 = Chips-n-Salsa, 1 = Saját SA + 2-opt, 2 = MOEA

    // Algoritmus futási paraméterek
    var runsOrIslands by remember { mutableStateOf(32) }
    var evaluationsPerRun by remember { mutableStateOf(42000) }
    var moeaPopSize by remember { mutableStateOf(40) }
    var useSelfTuningLam by remember { mutableStateOf(true) }
    var leftThumbEnabled by remember { mutableStateOf(false) }
    var rightThumbEnabled by remember { mutableStateOf(false) }

    // Kiértékelő súlyok állapotai (calculateTotalEffort.kt alapján)
    var wCharReach by remember { mutableStateOf(weightCharReachEffort) }
    var wGravity by remember { mutableStateOf(weightGravity) }
    var wHandImb by remember { mutableStateOf(weightHandImbalance) }
    var wPinkyOff by remember { mutableStateOf(weightPinkyOff) }
    var wSfb by remember { mutableStateOf(weightSfb) }
    var wFullScissor by remember { mutableStateOf(weightFullScissor) }
    var wHalfScissor by remember { mutableStateOf(weightHalfScissor) }
    var wLatStretch by remember { mutableStateOf(weightLateralStretch) }
    var wPinkyStretch by remember { mutableStateOf(weightPinkyStretch) }
    var wRedirect by remember { mutableStateOf(weightRedirect) }
    var wSpaceSfs1 by remember { mutableStateOf(weightSpaceSfs1) }

    var wRow0 by remember { mutableStateOf(weightRow0) }
    var wRow1 by remember { mutableStateOf(weightRow1) }
    var wRow2 by remember { mutableStateOf(weightRow2) }
    var wRow3 by remember { mutableStateOf(weightRow3) }
    var wRow4 by remember { mutableStateOf(weightRow4) }
    var wRow5 by remember { mutableStateOf(weightRow5) }

    var wHomeRow by remember { mutableStateOf(weightHomeRow) }
    var wAlternation by remember { mutableStateOf(weightAlternation) }
    var wRoll by remember { mutableStateOf(weightRoll) }
    var wThreeRoll by remember { mutableStateOf(weightThreeRoll) }

    var weightsStatusHu by remember { mutableStateOf<String?>(null) }
    var weightsStatusEn by remember { mutableStateOf<String?>(null) }
    var weightsStatusTick by remember { mutableStateOf(0) }

    LaunchedEffect(weightsStatusTick) {
        if (weightsStatusHu != null) {
            delay(6000L)
            weightsStatusHu = null
            weightsStatusEn = null
        }
    }

    fun syncWeightsToGlobal() {
        weightCharReachEffort = wCharReach
        weightGravity = wGravity
        weightHandImbalance = wHandImb
        weightPinkyOff = wPinkyOff
        weightSfb = wSfb
        weightFullScissor = wFullScissor
        weightHalfScissor = wHalfScissor
        weightLateralStretch = wLatStretch
        weightPinkyStretch = wPinkyStretch
        weightRedirect = wRedirect
        weightSpaceSfs1 = wSpaceSfs1
        weightRow0 = wRow0
        weightRow1 = wRow1
        weightRow2 = wRow2
        weightRow3 = wRow3
        weightRow4 = wRow4
        weightRow5 = wRow5
        weightHomeRow = wHomeRow
        weightAlternation = wAlternation
        weightRoll = wRoll
        weightThreeRoll = wThreeRoll
    }

    fun reloadWeightsFromGlobal() {
        wCharReach = weightCharReachEffort
        wGravity = weightGravity
        wHandImb = weightHandImbalance
        wPinkyOff = weightPinkyOff
        wSfb = weightSfb
        wFullScissor = weightFullScissor
        wHalfScissor = weightHalfScissor
        wLatStretch = weightLateralStretch
        wPinkyStretch = weightPinkyStretch
        wRedirect = weightRedirect
        wSpaceSfs1 = weightSpaceSfs1
        wRow0 = weightRow0
        wRow1 = weightRow1
        wRow2 = weightRow2
        wRow3 = weightRow3
        wRow4 = weightRow4
        wRow5 = weightRow5
        wHomeRow = weightHomeRow
        wAlternation = weightAlternation
        wRoll = weightRoll
        wThreeRoll = weightThreeRoll
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bal oldal: Layout, Korpusz és Algoritmus konfiguráció
        Column(
            modifier = Modifier
                .width(500.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard(
                title = tr("1. Kiindulási Layout, Korpusz és Algoritmus", "1. Starting Layout, Corpus and Algorithm"),
                subtitle = tr(
                    "Válaszd ki a javítandó billentyűzetkiosztást és a generáló algoritmust",
                    "Select the keyboard layout to optimize and the generation algorithm"
                ),
                headerTrailing = {
                    IconButton(onClick = {
                        layouts = listKeyboardLayouts(selectedFolder)
                        corpora = listAnalyzedCorpora()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = tr("Frissítés", "Refresh"))
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFolder == "keyboards",
                        onClick = { selectedFolder = "keyboards" },
                        label = { Text(tr("Alap (keyboards)", "Base (keyboards)")) },
                        leadingIcon = if (selectedFolder == "keyboards") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedFolder == "generatedkeyboards",
                        onClick = { selectedFolder = "generatedkeyboards" },
                        label = { Text(tr("Generált (generatedkeyboards)", "Generated (generatedkeyboards)")) },
                        leadingIcon = if (selectedFolder == "generatedkeyboards") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                DropdownSelector(
                    label = tr("Javítandó Layout ($selectedFolder)", "Layout to Optimize ($selectedFolder)"),
                    options = layouts,
                    selected = selectedLayout,
                    onSelect = { selectedLayout = it }
                )
                Spacer(Modifier.height(8.dp))
                DropdownSelector(
                    label = tr("Cél korpusz (Adathalmaz)", "Target Corpus (Dataset)"),
                    options = corpora,
                    selected = selectedCorpus,
                    onSelect = { selectedCorpus = it }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = tr("Generáló algoritmus:", "Optimization Algorithm:"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(6.dp))
                val algoTitles = listOf(
                    tr(
                        "Chips-n-Salsa SA + 2-opt (SelfTuningLam / ModifiedLam)",
                        "Chips-n-Salsa SA + 2-opt (SelfTuningLam / ModifiedLam)"
                    ),
                    tr(
                        "Saját Simulated Annealing + 2-opt (ImproveLayout)",
                        "Custom Simulated Annealing + 2-opt (ImproveLayout)"
                    ),
                    tr(
                        "MOEA Framework (Genetikus Algoritmus + PMX + 2-opt)",
                        "MOEA Framework (Genetic Algorithm + PMX + 2-opt)"
                    )
                )
                algoTitles.forEachIndexed { idx, title ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedAlgoIndex == idx) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                        border = if (selectedAlgoIndex == idx) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { selectedAlgoIndex = idx }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            RadioButton(selected = selectedAlgoIndex == idx, onClick = { selectedAlgoIndex = idx })
                            Spacer(Modifier.width(6.dp))
                            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }

            SectionCard(
                title = tr("2. Algoritmus futási paraméterek", "2. Algorithm Runtime Parameters"),
                subtitle = tr(
                    "A párhuzamos szálak / szigetek és iterációk beállításai",
                    "Configure parallel runs / islands and evaluation counts"
                )
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntSettingField(
                        label = tr("Párhuzamos futások / szigetek", "Parallel Runs / Islands"),
                        value = runsOrIslands,
                        onValueChange = { runsOrIslands = it.coerceIn(1, 1024) },
                        modifier = Modifier.weight(1f)
                    )
                    IntSettingField(
                        label = tr("Kiértékelések / szál", "Evaluations / Thread"),
                        value = evaluationsPerRun,
                        onValueChange = { evaluationsPerRun = it.coerceAtLeast(100) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedAlgoIndex == 2) {
                    Spacer(Modifier.height(8.dp))
                    IntSettingField(
                        label = tr("Populáció mérete (MOEA GA esetén)", "Population Size (for MOEA GA)"),
                        value = moeaPopSize,
                        onValueChange = { moeaPopSize = it.coerceIn(10, 1000) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (selectedAlgoIndex == 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useSelfTuningLam, onCheckedChange = { useSelfTuningLam = it })
                        Text(
                            text = tr(
                                "Self-Tuning Lam hűtés (ha ki van kapcsolva: Modified Lam)",
                                "Self-Tuning Lam annealing (if unchecked: Modified Lam)"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (selectedAlgoIndex != 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = leftThumbEnabled, onCheckedChange = { leftThumbEnabled = it })
                        Text(
                            tr("Bal hüvelykujj (thumb_left) bevonása", "Include Left Thumb (thumb_left)"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rightThumbEnabled, onCheckedChange = { rightThumbEnabled = it })
                        Text(
                            tr("Jobb hüvelykujj (thumb_right) bevonása", "Include Right Thumb (thumb_right)"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val lName = selectedLayout ?: return@Button
                        val cName = selectedCorpus ?: return@Button
                        val folder = selectedFolder
                        syncWeightsToGlobal()
                        when (selectedAlgoIndex) {
                            0 -> {
                                runTask(lang.pick("Chips-n-Salsa optimalizáló ($lName)", "Chips-n-Salsa Optimizer ($lName)")) {
                                    parallelChipsNSalsaImprovement(
                                        runs = runsOrIslands,
                                        evaluationsPerRun = evaluationsPerRun,
                                        useSelfTuningLam = useSelfTuningLam,
                                        keyboardname = lName,
                                        corpusFileName = cName,
                                        leftThumbEnabled = leftThumbEnabled,
                                        rightThumbEnabled = rightThumbEnabled,
                                        folder = folder
                                    )
                                }
                            }
                            1 -> {
                                runTask(lang.pick("Saját SA + 2-opt optimalizáló ($lName)", "Custom SA + 2-opt Optimizer ($lName)")) {
                                    parallelImprovement(
                                        runs = runsOrIslands,
                                        keyboardname = lName,
                                        corpusFileName = cName,
                                        iterations = evaluationsPerRun,
                                        folder = folder
                                    )
                                }
                            }
                            else -> {
                                runTask(lang.pick("MOEA GA + PMX + 2-opt optimalizáló ($lName)", "MOEA GA + PMX + 2-opt Optimizer ($lName)")) {
                                    parallelMoeaImprovement(
                                        islands = runsOrIslands,
                                        evaluationsPerIsland = evaluationsPerRun,
                                        populationSize = moeaPopSize,
                                        keyboardname = lName,
                                        corpusFileName = cName,
                                        leftThumbEnabled = leftThumbEnabled,
                                        rightThumbEnabled = rightThumbEnabled,
                                        folder = folder
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.RocketLaunch, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tr("LAYOUT OPTIMALIZÁLÁS / GENERÁLÁS INDÍTÁSA", "START LAYOUT OPTIMIZATION / GENERATION"),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Jobb oldal: Kiértékelő függvény súlyai csúszkákkal és számmezőkkel
        SectionCard(
            title = tr(
                "3. Kiértékelő függvény súlyai (calculateTotalEffort.kt)",
                "3. Objective Function Weights (calculateTotalEffort.kt)"
            ),
            subtitle = tr(
                "Szabadon testreszabható büntető és jutalmazó súlyok",
                "Freely customizable penalty and reward weights"
            ),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            headerTrailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            resetDefaultWeights()
                            reloadWeightsFromGlobal()
                            weightsStatusHu = "Alapértelmezett súlyok visszaállítva."
                            weightsStatusEn = "Default weights restored."
                            weightsStatusTick++
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Alapértékek", "Reset Defaults"), fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            syncWeightsToGlobal()
                            weightsStatusHu = "Célfüggvény súlyok elmentve a memóriába!"
                            weightsStatusEn = "Objective function weights saved in memory!"
                            weightsStatusTick++
                            println(
                                lang.pick(
                                    "Célfüggvény súlyok (Weights) frissítve a memóriában.",
                                    "Objective function weights updated in memory."
                                )
                            )
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Súlyok alkalmazása", "Apply Weights"), fontSize = 12.sp)
                    }
                }
            }
        ) {
            val activeWeightsStatus = lang.pick(weightsStatusHu ?: "", weightsStatusEn ?: "")
            if (activeWeightsStatus.isNotBlank()) {
                Text(
                    text = activeWeightsStatus,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = tr(
                        "BÜNTETŐ SÚLYOK (Penalties — növelik a Total Effortot)",
                        "PENALTY WEIGHTS (Increase Total Effort)"
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                WeightSliderRow("weightCharReachEffort", tr("Karakter elérési terhelés súlya", "Character Reach Effort Weight"), wCharReach, 0f, 50f) { wCharReach = it }
                WeightSliderRow("weightGravity", tr("Alap gravitációs / statikus terhelés súlya", "Base Gravity / Static Effort Weight"), wGravity, 0f, 10f) { wGravity = it }
                WeightSliderRow("weightHandImbalance", tr("Kéz aszimmetria büntetés (50-50%-tól eltérés)", "Hand Imbalance Penalty (Deviation from 50-50%)"), wHandImb, 0f, 50f) { wHandImb = it }
                WeightSliderRow("weightPinkyOff", tr("Kisujj alapgomb kívüli használat büntetése", "Pinky Off-Home Key Usage Penalty"), wPinkyOff, 0f, 50f) { wPinkyOff = it }
                WeightSliderRow("weightSfb", tr("Azonos ujjas bigram (SFB) büntetés", "Same-Finger Bigram (SFB) Penalty"), wSfb, 0f, 150f) { wSfb = it }
                WeightSliderRow("weightFullScissor", tr("Full Scissor Bigram (FSB) büntetése", "Full Scissor Bigram (FSB) Penalty"), wFullScissor, 0f, 150f) { wFullScissor = it }
                WeightSliderRow("weightHalfScissor", tr("Half Scissor Bigram (HSB) büntetése", "Half Scissor Bigram (HSB) Penalty"), wHalfScissor, 0f, 100f) { wHalfScissor = it }
                WeightSliderRow("weightLateralStretch", tr("Lateral Stretch Bigram (LSB) büntetése", "Lateral Stretch Bigram (LSB) Penalty"), wLatStretch, 0f, 100f) { wLatStretch = it }
                WeightSliderRow("weightPinkyStretch", tr("Pinky Stretch Bigram (PSB) büntetése", "Pinky Stretch Bigram (PSB) Penalty"), wPinkyStretch, 0f, 100f) { wPinkyStretch = it }
                WeightSliderRow("weightRedirect", tr("Egykezes irányváltás (Redirect) büntetése", "One-Hand Redirect Penalty"), wRedirect, 0f, 100f) { wRedirect = it }
                WeightSliderRow("weightSpaceSfs1", tr("Szóközös ugrás (Space SFS-1) büntetése", "Space Skipgram (Space SFS-1) Penalty"), wSpaceSfs1, 0f, 100f) { wSpaceSfs1 = it }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = tr(
                        "JUTALMAZÓ ÉS SOR SÚLYOK (Rewards & Row Weights)",
                        "REWARD & ROW WEIGHTS"
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                WeightSliderRow("weightHomeRow", tr("Alapsor (Home Row) jutalom", "Home Row Reward"), wHomeRow, 0f, 10f) { wHomeRow = it }
                WeightSliderRow("weightAlternation", tr("Alternálás (1-1-1) jutalom", "Hand Alternation (1-1-1) Reward"), wAlternation, 0f, 20f) { wAlternation = it }
                WeightSliderRow("weightRoll", tr("2-Roll gördülés jutalom", "2-Roll Bigram Reward"), wRoll, 0f, 20f) { wRoll = it }
                WeightSliderRow("weightThreeRoll", tr("3-Roll gördülés jutalom", "3-Roll Trigram Reward"), wThreeRoll, 0f, 30f) { wThreeRoll = it }

                WeightSliderRow("weightRow0", tr("0. sor (számsor) büntetés", "Row 0 (Number Row) Penalty"), wRow0, 0f, 50f) { wRow0 = it }
                WeightSliderRow("weightRow1", tr("1. sor (felső betűsor) büntetés", "Row 1 (Top Letter Row) Penalty"), wRow1, 0f, 50f) { wRow1 = it }
                WeightSliderRow("weightRow2", tr("2. sor (alapsor) büntetés", "Row 2 (Home Row) Penalty"), wRow2, 0f, 50f) { wRow2 = it }
                WeightSliderRow("weightRow3", tr("3. sor (alsó betűsor) büntetés", "Row 3 (Bottom Letter Row) Penalty"), wRow3, 0f, 50f) { wRow3 = it }
                WeightSliderRow("weightRow4", tr("4. sor büntetés", "Row 4 Penalty"), wRow4, 0f, 50f) { wRow4 = it }
                WeightSliderRow("weightRow5", tr("5. sor (Thumb / hüvelykujj sor) büntetés", "Row 5 (Thumb Row) Penalty"), wRow5, 0f, 50f) { wRow5 = it }
            }
        }
    }
}

// =========================================================================
// 5. KÉPERNYŐ: GENERÁLT LAYOUTOK BÖNGÉSZÉSE
// =========================================================================

private data class GeneratedRunGroup(
    val runName: String,
    val yamlFiles: List<File>
)

private fun scanGeneratedKeyboards(): List<GeneratedRunGroup> {
    val genDir = File("generatedkeyboards")
    if (!genDir.exists() || !genDir.isDirectory) return emptyList()
    val groups = mutableListOf<GeneratedRunGroup>()

    val subDirs = genDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    for (dir in subDirs) {
        val yamls = dir.listFiles { f -> f.isFile && f.name.endsWith(".yaml", true) }
            ?.sortedBy { it.name } ?: emptyList()
        if (yamls.isNotEmpty()) {
            groups.add(GeneratedRunGroup(dir.name, yamls))
        }
    }

    val rootYamls = genDir.listFiles { f -> f.isFile && f.name.endsWith(".yaml", true) }
        ?.sortedBy { it.name } ?: emptyList()
    if (rootYamls.isNotEmpty()) {
        groups.add(GeneratedRunGroup("(Gyökérmappa)", rootYamls))
    }
    return groups
}

private fun detectCorpusFromLog(runDirName: String): String? {
    val logFile = File("ImprovementLogs/$runDirName.txt")
    if (!logFile.exists()) return null
    return runCatching {
        logFile.useLines { lines ->
            lines.firstOrNull { it.startsWith("Korpusz neve:") }
                ?.substringAfter("Korpusz neve:")
                ?.trim()
        }
    }.getOrNull()
}

@Composable
fun GeneratedLayoutsBrowserScreen(
    runTask: (String, suspend () -> Unit) -> Unit
) {
    val lang = LocalAppLanguage.current
    var groups by remember { mutableStateOf(scanGeneratedKeyboards()) }
    var expandedGroups by remember(groups) {
        mutableStateOf(groups.map { it.runName }.toSet())
    }
    var selectedRelativePath by remember { mutableStateOf<String?>(null) }

    var corpora by remember { mutableStateOf(listAnalyzedCorpora()) }
    var selectedCorpus by remember {
        mutableStateOf(
            corpora.firstOrNull { it == "huszonkettoHuszonnegyeswikipediahuszonegy.txt" }
                ?: corpora.firstOrNull()
        )
    }

    var analysisResult by remember { mutableStateOf<LayoutAnalysisResult?>(null) }
    var heatmapBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var heatmapFile by remember { mutableStateOf<File?>(null) }
    var yamlAndLogTextHu by remember { mutableStateOf<String?>(null) }
    var yamlAndLogTextEn by remember { mutableStateOf<String?>(null) }

    fun openAndAnalyzeGeneratedLayout(relPath: String, autoDetectCorpus: Boolean) {
        val genDir = File("generatedkeyboards")
        val yamlFile = File(genDir, relPath)
        if (!yamlFile.exists()) return

        val parentDirName = yamlFile.parentFile?.takeIf { it != genDir }?.name
        var corpusToUse = selectedCorpus
        if (autoDetectCorpus && parentDirName != null) {
            val detected = detectCorpusFromLog(parentDirName)
            if (detected != null && corpora.contains(detected)) {
                selectedCorpus = detected
                corpusToUse = detected
            }
        }
        val finalCorpus = corpusToUse ?: return

        runTask(lang.pick("Generált layout elemzése: $relPath", "Analyzing generated layout: $relPath")) {
            // Meghívjuk a módosított elemzőt folder = "generatedkeyboards" paraméterrel
            val res = completeLayoutAnalysis(
                layoutName = relPath,
                datasetName = finalCorpus,
                folder = "generatedkeyboards",
                verbose = true
            )

            val expectedHeatmap = File(yamlFile.parentFile, "${yamlFile.nameWithoutExtension}_heatmap.png")
            if (!expectedHeatmap.exists()) {
                runCatching {
                    val parsed = LayoutParser.parseLayout(yamlFile)
                    renderCharEffortlayout(parsed, expectedHeatmap.absolutePath)
                }
            }

            val bmp = if (expectedHeatmap.exists()) loadComposeBitmap(expectedHeatmap) else null
            val yText = yamlFile.readText(StandardCharsets.UTF_8)
            val lText = if (parentDirName != null) {
                val lf = File("ImprovementLogs/$parentDirName.txt")
                if (lf.exists()) lf.readText(StandardCharsets.UTF_8) else ""
            } else ""

            analysisResult = res
            heatmapFile = if (expectedHeatmap.exists()) expectedHeatmap else null
            heatmapBitmap = bmp
            yamlAndLogTextHu = buildString {
                appendLine("=== YAML DEFINÍCIÓ (${yamlFile.name}) ===")
                appendLine(yText.trim().replace("\t", "    "))
                if (lText.isNotBlank()) {
                    appendLine("\n=== BILLENTYŰZETKIOSZTÁSHOZ TARTOZÓ OPTIMALIZÁLÁSI LOG ($parentDirName.txt) ===")
                    appendLine(lText.trim().replace("\t", "    "))
                }
            }
            yamlAndLogTextEn = buildString {
                appendLine("=== YAML DEFINITION (${yamlFile.name}) ===")
                appendLine(yText.trim().replace("\t", "    "))
                if (lText.isNotBlank()) {
                    appendLine("\n=== OPTIMIZATION LOG FOR THIS KEYBOARD LAYOUT ($parentDirName.txt) ===")
                    appendLine(lText.trim().replace("\t", "    "))
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bal oldali fa / lista a generált layoutokhoz
        SectionCard(
            title = tr("Generált Layoutok", "Generated Layouts"),
            subtitle = tr("generatedkeyboards mappa", "generatedkeyboards directory"),
            modifier = Modifier.width(400.dp).fillMaxHeight(),
            headerTrailing = {
                Row {
                    IconButton(onClick = {
                        groups = scanGeneratedKeyboards()
                        corpora = listAnalyzedCorpora()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = tr("Mappa frissítése", "Refresh Folder"))
                    }
                }
            }
        ) {
            DropdownSelector(
                label = tr("Elemzési korpusz", "Analysis Corpus"),
                options = corpora,
                selected = selectedCorpus,
                onSelect = { newCorpus ->
                    selectedCorpus = newCorpus
                    selectedRelativePath?.let { openAndAnalyzeGeneratedLayout(it, autoDetectCorpus = false) }
                }
            )
            Spacer(Modifier.height(10.dp))

            if (groups.isEmpty()) {
                Text(
                    text = tr("A 'generatedkeyboards' mappa jelenleg üres.", "The 'generatedkeyboards' directory is currently empty."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val genRoot = File("generatedkeyboards")
                val browserListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = browserListState,
                        modifier = Modifier.fillMaxSize().padding(end = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        groups.forEach { group ->
                            item(key = "group_${group.runName}") {
                                val isExpanded = expandedGroups.contains(group.runName)
                                val displayGroupName = if (group.runName == "(Gyökérmappa)") {
                                    tr("(Gyökérmappa)", "(Root Folder)")
                                } else {
                                    group.runName
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedGroups = if (isExpanded) {
                                                expandedGroups - group.runName
                                            } else {
                                                expandedGroups + group.runName
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = displayGroupName,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${group.yamlFiles.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

                            if (expandedGroups.contains(group.runName)) {
                                items(group.yamlFiles, key = { it.absolutePath }) { yf ->
                                    val relPath = yf.relativeTo(genRoot).path.replace('\\', '/')
                                    val isSelected = selectedRelativePath == relPath
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp)
                                            .clickable {
                                                selectedRelativePath = relPath
                                                openAndAnalyzeGeneratedLayout(relPath, autoDetectCorpus = true)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = yf.name,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(browserListState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }

        // Jobb oldal: Hőtérkép + Statisztikák + YAML/Log
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeatmapViewerCard(
                title = selectedRelativePath?.let {
                    tr("Generált Kiosztás Hőtérképe — $it", "Generated Layout Heatmap — $it")
                } ?: tr("Generált Kiosztás Hőtérképe", "Generated Layout Heatmap"),
                imageBitmap = heatmapBitmap,
                imageFile = heatmapFile,
                emptyMessage = tr(
                    "Kattints egy generált .yaml fájlra a bal oldali listában az automatikus elemzéshez és a hőtérkép megjelenítéséhez!",
                    "Click on a generated .yaml file in the left list to automatically analyze it and display its heatmap!"
                ),
                minHeight = 380.dp
            )

            LayoutStatsSummaryCard(
                result = analysisResult,
                extraSubtitle = selectedRelativePath?.let {
                    tr(
                        "Forrás: generatedkeyboards/$it | Korpusz: ${selectedCorpus ?: "-"}",
                        "Source: generatedkeyboards/$it | Corpus: ${selectedCorpus ?: "-"}"
                    )
                }
            )

            SectionCard(
                title = tr(
                    "YAML definíció és billentyűzetkiosztáshoz tartozó optimalizálási Log",
                    "YAML Definition and Optimization Log"
                ),
                subtitle = tr(
                    "A kiválasztott generált fájl és az ImprovementLogs napló tartalma",
                    "Contents of the selected generated layout file and its ImprovementLogs entry"
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp)
                ) {
                    val vScroll = rememberScrollState()
                    val hScroll = rememberScrollState()
                    val shownYamlLog = lang.pick(
                        yamlAndLogTextHu ?: "Válassz ki egy generált layoutot a bal oldali listából a megnyitáshoz és az automatikus elemzéshez!",
                        yamlAndLogTextEn ?: "Select a generated layout from the left list to open and automatically analyze it!"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll)
                    ) {
                        Text(
                            text = shownYamlLog,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
