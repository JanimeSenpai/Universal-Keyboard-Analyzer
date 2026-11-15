package composegui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

fun main() {
    launchComposeGui()
}

fun launchComposeGui() {
    application {
        val windowState = rememberWindowState(
            size = DpSize(1440.dp, 920.dp),
            position = WindowPosition.Aligned(Alignment.Center)
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            undecorated = true,
            title = "Universal Keyboard Analyzer — Compose Multiplatform GUI"
        ) {
            ComposeAppRoot(
                windowState = windowState,
                onCloseRequest = ::exitApplication
            )
        }
    }
}

private enum class MainTab(
    val titleHu: String,
    val titleEn: String,
    val shortTitleHu: String,
    val shortTitleEn: String,
    val icon: ImageVector
) {
    CORPUS("1. Korpusz elemző eszközök", "1. Corpus Analysis Tools", "Korpusz", "Corpus", Icons.Default.MenuBook),
    LAYOUT("2. Layout elemző eszközök", "2. Layout Analysis Tools", "Layout", "Layout", Icons.Default.Keyboard),
    EFFORT("3. Fizikai effort modell", "3. Physical Effort Model", "Effort modell", "Effort Model", Icons.Default.PanTool),
    GENERATION("4. Layout generálás", "4. Layout Generation", "Generálás", "Generation", Icons.Default.AutoFixHigh),
    BROWSER("5. Generált layoutok böngészése", "5. Browse Generated Layouts", "Böngésző", "Browser", Icons.Default.FolderSpecial);

    fun title(lang: AppLanguage): String = lang.pick(titleHu, titleEn)
    fun shortTitle(lang: AppLanguage): String = lang.pick(shortTitleHu, shortTitleEn)
}

/**
 * Szálbiztos, kötegelt (batched) konzol-puffer.
 * A háttérszálak (System.out / System.err) nem közvetlenül a Compose State-et módosítják minden egyes
 * println híváskor, hanem egy memóriabeli StringBuilder-be írnak, amit a UI 40 ms-onként egyszerre olvas ki,
 * és soronként LazyColumn-ban jelenít meg.
 */
private class ConsoleLogBuffer {
    private val lock = Any()
    private val pending = StringBuilder()

    @Volatile
    private var hasPending: Boolean = false

    val lines = mutableStateListOf("=== Universal Keyboard Analyzer — Live Console ===")
    var incompleteTail by mutableStateOf("")
        private set
    var flushTick by mutableStateOf(0)
        private set

    fun appendFromStream(text: String) {
        val cleaned = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\t", "    ")
        synchronized(lock) {
            pending.append(cleaned)
            hasPending = true
        }
    }

    fun flushPendingToUi(maxLines: Int = 1500) {
        if (!hasPending) return
        val chunk: String
        synchronized(lock) {
            if (pending.isEmpty()) {
                hasPending = false
                return
            }
            chunk = pending.toString()
            pending.setLength(0)
            hasPending = false
        }

        val combined = incompleteTail + chunk
        val parts = combined.split('\n')
        if (combined.endsWith('\n')) {
            incompleteTail = ""
            val completed = parts.dropLast(1)
            if (completed.isNotEmpty()) {
                lines.addAll(completed)
            }
        } else {
            incompleteTail = parts.last()
            val completed = parts.dropLast(1)
            if (completed.isNotEmpty()) {
                lines.addAll(completed)
            }
        }

        val excess = lines.size - maxLines
        if (excess > 0) {
            lines.removeRange(0, excess)
        }
        flushTick++
    }

    fun clear() {
        synchronized(lock) {
            pending.setLength(0)
            hasPending = false
        }
        incompleteTail = ""
        lines.clear()
        flushTick++
    }
}

private fun isWindowsAppsDarkTheme(): Boolean? {
    val osName = System.getProperty("os.name")?.lowercase() ?: return null
    if (!osName.contains("win")) return null
    return runCatching {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme"
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching null
        }
        val match = Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x([0-9a-fA-F]+)""").find(output)
        val value = match?.groupValues?.get(1)?.toIntOrNull(16)
        when (value) {
            0 -> true
            1 -> false
            else -> null
        }
    }.getOrNull()
}

@Composable
fun FrameWindowScope.ComposeAppRoot(
    windowState: WindowState,
    onCloseRequest: () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    var darkTheme by remember { mutableStateOf(isWindowsAppsDarkTheme() ?: systemInDark) }
    var language by remember { mutableStateOf(AppLanguage.HU) }
    var selectedTab by remember { mutableStateOf(MainTab.CORPUS) }
    var isRunningTask by remember { mutableStateOf(false) }
    var statusMessageHu by remember { mutableStateOf("Kész.") }
    var statusMessageEn by remember { mutableStateOf("Ready.") }
    var consoleExpanded by remember { mutableStateOf(true) }
    var consoleHeight by remember { mutableStateOf(195.dp) }

    val consoleBuffer = remember { ConsoleLogBuffer() }
    val coroutineScope = rememberCoroutineScope()

    // System.out / System.err átirányítás a kötegelt konzol-pufferbe és az eredeti terminálba
    DisposableEffect(consoleBuffer) {
        val origOut = System.out
        val origErr = System.err

        val teeOut = object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(b: Int) {
                origOut.write(b)
                synchronized(buffer) {
                    buffer.write(b)
                    if (b == '\n'.code || buffer.size() >= 4096) flushBuffer()
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                origOut.write(b, off, len)
                synchronized(buffer) {
                    buffer.write(b, off, len)
                    flushBuffer()
                }
            }

            override fun flush() {
                origOut.flush()
                synchronized(buffer) {
                    flushBuffer()
                }
            }

            private fun flushBuffer() {
                if (buffer.size() == 0) return
                val text = buffer.toString(StandardCharsets.UTF_8.name())
                buffer.reset()
                consoleBuffer.appendFromStream(text)
            }
        }

        System.setOut(PrintStream(teeOut, true, StandardCharsets.UTF_8.name()))
        System.setErr(PrintStream(teeOut, true, StandardCharsets.UTF_8.name()))

        onDispose {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    // 40 ms-onként kötegelve frissítjük a konzol sorait (nem vált ki újrarajzolást a ComposeAppRoot-ban)
    LaunchedEffect(consoleBuffer) {
        while (true) {
            delay(40L)
            consoleBuffer.flushPendingToUi()
        }
    }

    val runBackgroundTask: (String, suspend () -> Unit) -> Unit = { taskName, block ->
        if (isRunningTask) {
            println(
                language.pick(
                    "[INFO] Egy művelet már fut a háttérben, kérlek várj...",
                    "[INFO] A task is already running in the background, please wait..."
                )
            )
        } else {
            isRunningTask = true
            statusMessageHu = "Folyamatban: $taskName..."
            statusMessageEn = "Running: $taskName..."
            println("\n>>> ${language.pick("INDÍTÁS", "START")}: $taskName <<<")
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        block()
                    }
                    statusMessageHu = "Befejezve: $taskName"
                    statusMessageEn = "Completed: $taskName"
                    println(">>> ${language.pick("KÉSZ", "DONE")}: $taskName <<<")
                } catch (e: Throwable) {
                    e.printStackTrace()
                    statusMessageHu = "Hiba történt: ${e.message}"
                    statusMessageEn = "Error: ${e.message}"
                } finally {
                    isRunningTask = false
                }
            }
        }
    }

    KeyboardAnalyzerTheme(darkTheme = darkTheme, language = language) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Bal oldali modern NavigationRail
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "UKA",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                ) {
                    Spacer(Modifier.height(8.dp))
                    MainTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.title(language)) },
                            label = {
                                Text(
                                    text = tab.shortTitle(language),
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            alwaysShowLabel = true,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { consoleExpanded = !consoleExpanded },
                        modifier = Modifier.padding(bottom = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = if (consoleExpanded) tr("Konzol elrejtése", "Hide Console") else tr("Konzol megnyitása", "Show Console"),
                            tint = if (consoleExpanded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = { darkTheme = !darkTheme },
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = tr("Téma váltása", "Toggle Theme"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Bal alsó sarok: Nyelv kapcsoló (HU / EN)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .clickable {
                                language = if (language == AppLanguage.HU) AppLanguage.EN else AppLanguage.HU
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "HU",
                                fontSize = 11.sp,
                                fontWeight = if (language == AppLanguage.HU) FontWeight.ExtraBold else FontWeight.Normal,
                                color = if (language == AppLanguage.HU) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                }
                            )
                            Text(
                                text = "/",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "EN",
                                fontSize = 11.sp,
                                fontWeight = if (language == AppLanguage.EN) FontWeight.ExtraBold else FontWeight.Normal,
                                color = if (language == AppLanguage.EN) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                }
                            )
                        }
                    }
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                // Jobb oldali fő tartalom + felső fejléc + alsó konzol sáv
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Felső fejléc sáv (ablakmozgatással és ablakvezérlő gombokkal)
                    WindowDraggableArea {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = selectedTab.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = selectedTab.title(language),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (isRunningTask) {
                                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                            } else {
                                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isRunningTask) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(
                                                    text = language.pick(statusMessageHu, statusMessageEn),
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        }

                                        FilledTonalButton(
                                            onClick = { consoleExpanded = !consoleExpanded },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (consoleExpanded) Icons.Default.ExpandMore else Icons.Default.Terminal,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (consoleExpanded) tr("Konzol elrejtése", "Hide Console") else tr("Konzol megnyitása", "Show Console"),
                                                fontSize = 12.sp
                                            )
                                        }

                                        VerticalDivider(
                                            modifier = Modifier.height(22.dp).padding(horizontal = 2.dp),
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        )

                                        // Tálcára tétel (Minimize)
                                        IconButton(
                                            onClick = { windowState.isMinimized = true },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Remove,
                                                contentDescription = tr("Kis méret (Tálcára)", "Minimize"),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        // Ablakméretezés (Maximize / Restore)
                                        val isMaximized = windowState.placement == WindowPlacement.Maximized
                                        IconButton(
                                            onClick = {
                                                windowState.placement = if (isMaximized) {
                                                    WindowPlacement.Floating
                                                } else {
                                                    WindowPlacement.Maximized
                                                }
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isMaximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                                                contentDescription = if (isMaximized) tr("Előző méret", "Restore") else tr("Teljes képernyő", "Maximize"),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }

                                        // Bezárás (Close)
                                        IconButton(
                                            onClick = onCloseRequest,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = tr("Bezárás", "Close"),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                }
                                if (isRunningTask) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                                } else {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                }
                            }
                        }
                    }

                    // Fő képernyő terület
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Crossfade(targetState = selectedTab) { tab ->
                            when (tab) {
                                MainTab.CORPUS -> CorpusAnalyzerScreen(runBackgroundTask)
                                MainTab.LAYOUT -> LayoutAnalyzerScreen(runBackgroundTask)
                                MainTab.EFFORT -> EffortModelScreen(runBackgroundTask)
                                MainTab.GENERATION -> LayoutGenerationScreen(runBackgroundTask)
                                MainTab.BROWSER -> GeneratedLayoutsBrowserScreen(runBackgroundTask)
                            }
                        }
                    }

                    // Összecsukható és függőlegesen méretezhető élő konzol alul
                    AnimatedVisibility(visible = consoleExpanded) {
                        LiveConsolePanel(
                            consoleBuffer = consoleBuffer,
                            darkTheme = darkTheme,
                            consoleHeight = consoleHeight,
                            onHeightChange = { consoleHeight = it },
                            onHide = { consoleExpanded = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveConsolePanel(
    consoleBuffer: ConsoleLogBuffer,
    darkTheme: Boolean,
    consoleHeight: Dp,
    onHeightChange: (Dp) -> Unit,
    onHide: () -> Unit
) {
    val density = LocalDensity.current
    val currentHeight by rememberUpdatedState(consoleHeight)
    val currentOnHeightChange by rememberUpdatedState(onHeightChange)
    var headerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragStartWindowY by remember { mutableStateOf(0f) }
    var dragStartHeight by remember { mutableStateOf(consoleHeight) }
    val consoleListState = rememberLazyListState()

    val consoleBg = if (darkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val consoleHeaderBg = if (darkTheme) Color(0xFF1E293B) else Color(0xFFE2E8F0)
    val consoleTextColor = if (darkTheme) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val consoleHeaderTitleColor = if (darkTheme) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val consoleIconTint = if (darkTheme) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val consoleActionTint = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF475569)

    val lines = consoleBuffer.lines
    val tail = consoleBuffer.incompleteTail
    val hasTail = tail.isNotEmpty()
    val totalItems = lines.size + if (hasTail) 1 else 0

    LaunchedEffect(consoleBuffer.flushTick) {
        if (totalItems > 0) {
            consoleListState.scrollToItem(totalItems - 1)
        }
    }

    Surface(
        color = consoleBg,
        contentColor = consoleTextColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(consoleHeight)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Felső átméretező sáv + fejléc (ablak-koordinátában követi az egeret 1:1 arányban)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(consoleHeaderBg)
                    .onGloballyPositioned { headerCoords = it }
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
                    .pointerInput(density) {
                        detectVerticalDragGestures(
                            onDragStart = { startOffset ->
                                dragStartWindowY = headerCoords?.localToWindow(startOffset)?.y ?: startOffset.y
                                dragStartHeight = currentHeight
                            },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val currentWindowY = headerCoords?.localToWindow(change.position)?.y
                                    ?: return@detectVerticalDragGestures
                                val deltaDp = ((dragStartWindowY - currentWindowY) / density.density).dp
                                currentOnHeightChange((dragStartHeight + deltaDp).coerceIn(100.dp, 650.dp))
                            }
                        )
                    }
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                // Vizuális fogantyú (pill) a felső szélen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = consoleActionTint.copy(alpha = 0.45f),
                        modifier = Modifier.size(width = 42.dp, height = 3.dp)
                    ) {}
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = consoleIconTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = tr("Élő Konzol Kimenet (System.out / System.err)", "Live Console Output (System.out / System.err)"),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = consoleHeaderTitleColor
                            )
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = onHide,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(26.dp)
                                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = consoleActionTint,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Konzol elrejtése", "Hide Console"), fontSize = 11.sp, color = consoleActionTint)
                        }
                        TextButton(
                            onClick = { consoleBuffer.clear() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(26.dp)
                                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = consoleActionTint,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Törlés", "Clear"), fontSize = 11.sp, color = consoleActionTint)
                        }
                    }
                }
            }

            // Konzol szövegterület LazyColumn-nal (csak a látható sorokat rendereli) + jobb oldali scrollbar
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = consoleListState,
                    contentPadding = PaddingValues(start = 14.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = line.ifEmpty { " " },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = consoleTextColor
                        )
                    }
                    if (hasTail) {
                        item(key = "incomplete_tail") {
                            Text(
                                text = tail,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = consoleTextColor
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(consoleListState),
                    style = LocalScrollbarStyle.current.copy(
                        unhoverColor = consoleTextColor.copy(alpha = 0.28f),
                        hoverColor = consoleIconTint.copy(alpha = 0.75f)
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 4.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }
    }
}
