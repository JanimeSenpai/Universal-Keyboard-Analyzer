package composegui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF134E4A),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFA78BFA),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF475569)
)

@Composable
fun KeyboardAnalyzerTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}

// =========================================================================
// FÁJLRENDSZER SEGÉDFÜGGVÉNYEK
// =========================================================================

fun listRawTexts(): List<String> {
    val dir = File("raw_texts")
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isFile }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()
}

fun listProcessableCorpora(): List<String> {
    val dir = File("processable_texts")
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", true) }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()
}

fun listAnalyzedCorpora(): List<String> {
    val dir = File("analysis_results")
    val fromResults = if (dir.exists() && dir.isDirectory) {
        dir.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?: emptyList()
    } else emptyList()
    return (fromResults + listProcessableCorpora()).distinct().sorted()
}

fun listKeyboardLayouts(folder: String): List<String> {
    val baseDir = File(folder)
    if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
    return baseDir.walkTopDown()
        .filter { it.isFile && (it.name.endsWith(".yaml", true) || !it.name.contains(".")) }
        .map { it.relativeTo(baseDir).path.replace('\\', '/') }
        .sorted()
        .toList()
}

// =========================================================================
// ÚJRAHASZNOSÍTHATÓ MATERIAL 3 KOMPONENSEK
// =========================================================================

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    headerTrailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (headerTrailing != null) {
                    headerTrailing()
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownSelector(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemLabel: (T) -> String = { it.toString() }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let(itemLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel(item),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun DoubleSettingField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    formatPattern: String = "%.2f"
) {
    var textState by remember(value) { mutableStateOf(String.format(java.util.Locale.US, formatPattern, value)) }

    OutlinedTextField(
        value = textState,
        onValueChange = { newText ->
            textState = newText
            newText.replace(',', '.').toDoubleOrNull()?.let { parsed ->
                onValueChange(parsed)
            }
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    )
}

@Composable
fun IntSettingField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var textState by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = textState,
        onValueChange = { newText ->
            textState = newText
            newText.trim().toIntOrNull()?.let { parsed ->
                onValueChange(parsed)
            }
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    )
}

@Composable
fun WeightSliderRow(
    label: String,
    description: String,
    value: Double,
    minVal: Float,
    maxVal: Float,
    onValueChange: (Double) -> Unit
) {
    var textVal by remember(value) { mutableStateOf(String.format(java.util.Locale.US, "%.2f", value)) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = textVal,
                    onValueChange = { str ->
                        textVal = str
                        str.replace(',', '.').toDoubleOrNull()?.let { onValueChange(it) }
                    },
                    singleLine = true,
                    modifier = Modifier.width(105.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            Slider(
                value = value.toFloat().coerceIn(minVal, maxVal),
                onValueChange = { newFloat ->
                    val rounded = (newFloat * 100.0).roundToInt() / 100.0
                    onValueChange(rounded)
                },
                valueRange = minVal..maxVal,
                modifier = Modifier.fillMaxWidth().height(26.dp)
            )
        }
    }
}

@Composable
fun MetricBadge(
    title: String,
    value: String,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = accentColor
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =========================================================================
// SKIA ALAPÚ, NAGYÍTHATÓ HŐTÉRKÉP KÁRTYA (FilterQuality.High)
// =========================================================================

@Composable
fun HeatmapViewerCard(
    title: String,
    imageBitmap: ImageBitmap?,
    imageFile: File?,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 280.dp
) {
    var fitMode by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(1.0f) }

    SectionCard(
        title = title,
        modifier = modifier,
        headerTrailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (imageBitmap != null) {
                    Text(
                        text = if (fitMode) {
                            "Illesztve (${imageBitmap.width}×${imageBitmap.height} px)"
                        } else {
                            "Zoom: ${(zoom * 100).roundToInt()}% (${imageBitmap.width}×${imageBitmap.height} px)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = { fitMode = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("Illesztés", fontSize = 11.sp)
                }
                FilledTonalButton(
                    onClick = {
                        fitMode = false
                        zoom = 1.0f
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("100%", fontSize = 11.sp)
                }
                IconButton(
                    onClick = {
                        fitMode = false
                        zoom = (zoom * 0.85f).coerceIn(0.3f, 3.0f)
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Kicsinyítés", modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = {
                        fitMode = false
                        zoom = (zoom * 1.18f).coerceIn(0.3f, 3.0f)
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Nagyítás", modifier = Modifier.size(16.dp))
                }
                if (imageFile != null && imageFile.exists()) {
                    OutlinedButton(
                        onClick = {
                            if (Desktop.isDesktopSupported()) {
                                runCatching { Desktop.getDesktop().open(imageFile) }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Külső megnyitás", fontSize = 11.sp)
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF1F5F9))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (imageBitmap == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF475569)
                    )
                }
            } else {
                if (fitMode) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                } else {
                    val hScroll = rememberScrollState()
                    val vScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(hScroll)
                            .verticalScroll(vScroll)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = title,
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.size(
                                width = (imageBitmap.width * zoom).dp,
                                height = (imageBitmap.height * zoom).dp
                            )
                        )
                    }
                }
            }
        }
    }
}

fun loadComposeBitmap(file: File): ImageBitmap? {
    return runCatching {
        val buffered: BufferedImage? = ImageIO.read(file)
        buffered?.toComposeImageBitmap()
    }.getOrNull()
}
