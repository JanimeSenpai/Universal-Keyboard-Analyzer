package composegui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val title: String,
    val shortTitle: String,
    val icon: ImageVector
) {
    CORPUS("1. Korpusz elemző eszközök", "Korpusz", Icons.Default.MenuBook),
    LAYOUT("2. Layout elemző eszközök", "Layout", Icons.Default.Keyboard),
    EFFORT("3. Fizikai effort modell", "Effort modell", Icons.Default.PanTool),
    GENERATION("4. Layout generálás", "Generálás", Icons.Default.AutoFixHigh),
    BROWSER("5. Generált layoutok böngészése", "Böngésző", Icons.Default.FolderSpecial)
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
    var selectedTab by remember { mutableStateOf(MainTab.CORPUS) }
    var isRunningTask by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Kész.") }
    var consoleExpanded by remember { mutableStateOf(true) }
    var consoleText by remember { mutableStateOf("=== Universal Keyboard Analyzer — Élő Konzol ===\n") }

    val coroutineScope = rememberCoroutineScope()
    val consoleScrollState = rememberScrollState()

    // System.out / System.err átirányítás a Compose konzolba és az eredeti terminálba
    DisposableEffect(Unit) {
        val origOut = System.out
        val origErr = System.err
        val lock = Any()

        fun appendChunk(chunk: String) {
            val cleanChunk = chunk.replace("\t", "    ")
            synchronized(lock) {
                val combined = consoleText + cleanChunk
                consoleText = if (combined.length > 90_000) {
                    combined.takeLast(75_000)
                } else {
                    combined
                }
            }
        }

        val teeOut = object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(b: Int) {
                origOut.write(b)
                synchronized(buffer) {
                    buffer.write(b)
                    if (b == '\n'.code) flushBuffer()
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                origOut.write(b, off, len)
                synchronized(buffer) {
                    buffer.write(b, off, len)
                    flushBuffer()
                }
            }

            private fun flushBuffer() {
                if (buffer.size() == 0) return
                val text = buffer.toString(StandardCharsets.UTF_8.name())
                buffer.reset()
                appendChunk(text)
            }
        }

        System.setOut(PrintStream(teeOut, true, StandardCharsets.UTF_8.name()))
        System.setErr(PrintStream(teeOut, true, StandardCharsets.UTF_8.name()))

        onDispose {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    LaunchedEffect(consoleText.length) {
        consoleScrollState.scrollTo(consoleScrollState.maxValue)
    }

    val runBackgroundTask: (String, suspend () -> Unit) -> Unit = { taskName, block ->
        if (isRunningTask) {
            println("[INFO] Egy művelet már fut a háttérben, kérlek várj...")
        } else {
            isRunningTask = true
            statusMessage = "Folyamatban: $taskName..."
            println("\n>>> INDÍTÁS: $taskName <<<")
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        block()
                    }
                    statusMessage = "Befejezve: $taskName"
                    println(">>> KÉSZ: $taskName <<<")
                } catch (e: Throwable) {
                    e.printStackTrace()
                    statusMessage = "Hiba történt: ${e.message}"
                } finally {
                    isRunningTask = false
                }
            }
        }
    }

    KeyboardAnalyzerTheme(darkTheme = darkTheme) {
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
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = {
                                Text(
                                    text = tab.shortTitle,
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
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = if (consoleExpanded) "Konzol elrejtése" else "Konzol megnyitása",
                            tint = if (consoleExpanded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = { darkTheme = !darkTheme },
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Téma váltása",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                            text = selectedTab.title,
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
                                                    text = statusMessage,
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
                                                text = if (consoleExpanded) "Konzol elrejtése" else "Konzol megnyitása",
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
                                                contentDescription = "Kis méret (Tálcára)",
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
                                                contentDescription = if (isMaximized) "Előző méret" else "Teljes képernyő",
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
                                                contentDescription = "Bezárás",
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

                    // Összecsukható élő konzol alul
                    AnimatedVisibility(visible = consoleExpanded) {
                        val consoleBg = if (darkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                        val consoleHeaderBg = if (darkTheme) Color(0xFF1E293B) else Color(0xFFE2E8F0)
                        val consoleTextColor = if (darkTheme) Color(0xFFE2E8F0) else Color(0xFF0F172A)
                        val consoleHeaderTitleColor = if (darkTheme) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                        val consoleIconTint = if (darkTheme) Color(0xFF38BDF8) else Color(0xFF0284C7)
                        val consoleActionTint = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF475569)

                        Surface(
                            color = consoleBg,
                            contentColor = consoleTextColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(195.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(consoleHeaderBg)
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
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
                                            text = "Élő Konzol Kimenet (System.out / System.err)",
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
                                            onClick = { consoleExpanded = false },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = consoleActionTint,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Konzol elrejtése", fontSize = 11.sp, color = consoleActionTint)
                                        }
                                        TextButton(
                                            onClick = { consoleText = "" },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteSweep,
                                                contentDescription = null,
                                                tint = consoleActionTint,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Törlés", fontSize = 11.sp, color = consoleActionTint)
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .verticalScroll(consoleScrollState)
                                ) {
                                    Text(
                                        text = consoleText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        color = consoleTextColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
