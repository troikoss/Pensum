package com.troikoss.taskmanager

import android.content.Context
import android.content.pm.PackageManager
import android.view.PointerIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.collections.mapNotNull

// --- Data model ---

data class ProcessItem(
    val name: String,
    val packageName: String,
    val pid: Int,
    val state: String,
    val threads: Int,
    val cpuPercent: Float,
    val memoryMb: Float,
    val isRecentTask: Boolean,
    val isSystem: Boolean
)

enum class SortColumn { NAME, STATE, THREADS, CPU, MEMORY }

// --- App Cache ---
private data class AppCacheInfo(val label: String, val uid: Int?)
private val appCache = mutableMapOf<String, AppCacheInfo>()

// --- Shizuku shell helpers ---

private data class SysStat(val total: Long, val idle: Long)
private data class PidStat(val utimeStime: Long, val state: String, val threads: Int)
private data class SystemSnapshot(val sysStat: SysStat?, val pidStats: Map<Int, PidStat>)

private fun shellExec(cmd: String): String = try {
    val method = Shizuku::class.java.getDeclaredMethod(
        "newProcess",
        Array<String>::class.java,
        Array<String>::class.java,
        String::class.java
    )
    method.isAccessible = true
    val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    val out = process.inputStream.bufferedReader().readText()
    process.errorStream.bufferedReader().readText() // drain stderr to prevent blocking
    process.waitFor()
    out
} catch (_: Exception) { "" }

private fun readMemPercent(): Float {
    val raw = shellExec("grep -E '^(MemTotal|MemAvailable):' /proc/meminfo")
    var total = 0L
    var available = 0L
    raw.lines().forEach { line ->
        val parts = line.trim().split("\\s+".toRegex())
        when {
            parts[0] == "MemTotal:"     -> total     = parts[1].toLongOrNull() ?: 0L
            parts[0] == "MemAvailable:" -> available = parts[1].toLongOrNull() ?: 0L
        }
    }
    if (total == 0L) return 0f
    return ((total - available).toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f)
}

// Reads System CPU, PID CPU, Process State, and Thread Count instantly
private fun readSystemSnapshot(): SystemSnapshot {
    val raw = shellExec("cat /proc/stat | grep '^cpu '; cat /proc/[0-9]*/stat 2>/dev/null")
    val lines = raw.lines()

    var sysStat: SysStat? = null
    val pidStats = mutableMapOf<Int, PidStat>()

    for (line in lines) {
        if (line.isBlank()) continue

        if (line.startsWith("cpu ")) {
            val p = line.trim().split("\\s+".toRegex())
            if (p.size >= 8) {
                val user = p[1].toLongOrNull() ?: 0L
                val nice = p[2].toLongOrNull() ?: 0L
                val system = p[3].toLongOrNull() ?: 0L
                val idle = p[4].toLongOrNull() ?: 0L
                val iowait = p[5].toLongOrNull() ?: 0L
                val irq = p[6].toLongOrNull() ?: 0L
                val softirq = p[7].toLongOrNull() ?: 0L
                sysStat = SysStat(total = user + nice + system + idle + iowait + irq + softirq, idle = idle + iowait)
            }
        } else if (line[0].isDigit() && line.contains(")")) {
            val pidEnd = line.indexOf(' ')
            if (pidEnd == -1) continue
            val pid = line.substring(0, pidEnd).toIntOrNull() ?: continue

            val commEnd = line.lastIndexOf(')')
            if (commEnd == -1) continue

            val statParts = line.substring(commEnd + 2).trim().split("\\s+".toRegex())
            // State is index 0, utime is 11, stime is 12, num_threads is 17
            if (statParts.size >= 18) {
                val state = statParts[0]
                val utime = statParts[11].toLongOrNull() ?: 0L
                val stime = statParts[12].toLongOrNull() ?: 0L
                val threads = statParts[17].toIntOrNull() ?: 0
                pidStats[pid] = PidStat(utime + stime, state, threads)
            }
        }
    }
    return SystemSnapshot(sysStat, pidStats)
}

private suspend fun fetchProcesses(context: Context): Pair<List<ProcessItem>, Float> =
    withContext(Dispatchers.IO) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return@withContext Pair(emptyList(), 0f)

        try {
            val pm = context.packageManager
            val psOut = shellExec("ps -A -o PID,RSS,NAME 2>/dev/null || ps -A")
            val lines = psOut.lines().filter { it.isNotBlank() }

            val entries = if (lines.firstOrNull()?.trimStart()?.startsWith("PID", ignoreCase = true) == true) {
                lines.drop(1).mapNotNull { line ->
                    val p = line.trim().split("\\s+".toRegex())
                    if (p.size < 3) return@mapNotNull null
                    val pid = p[0].toIntOrNull() ?: return@mapNotNull null
                    val rss = p[1].toLongOrNull() ?: 0L
                    Triple(pid, rss, p[2])
                }
            } else {
                lines.drop(1).mapNotNull { line ->
                    val p = line.trim().split("\\s+".toRegex())
                    if (p.size < 9) return@mapNotNull null
                    val pid = p[1].toIntOrNull() ?: return@mapNotNull null
                    val rss = p[4].toLongOrNull() ?: 0L
                    Triple(pid, rss, p.last())
                }
            }

            // --- MISSING SNAPSHOT VARIABLES FIXED HERE ---
            val snapBefore = readSystemSnapshot()
            delay(500) // Sample interval
            val snapAfter = readSystemSnapshot()

            val memPct = readMemPercent()
            val sysDelta = if (snapBefore.sysStat != null && snapAfter.sysStat != null)
                (snapAfter.sysStat.total - snapBefore.sysStat.total).coerceAtLeast(1L) else 1L

            val recentPackages = getRecentPackages()

            val items = entries.mapNotNull { (pid, rssKb, processName) ->
                val pidStatBefore = snapBefore.pidStats[pid]
                val pidStatAfter = snapAfter.pidStats[pid]

                val pidDelta = (pidStatAfter?.utimeStime ?: 0L) - (pidStatBefore?.utimeStime ?: 0L)
                val cpuPct = (pidDelta.toFloat() / sysDelta.toFloat() * 100f).coerceIn(0f, 100f)

                // Decode Process State
                val stateChar = pidStatAfter?.state ?: "S"
                val stateWord = when (stateChar) {
                    "R" -> "Running"
                    "S" -> "Sleeping"
                    "D" -> "Disk Sleep"
                    "Z" -> "Zombie"
                    "T", "t" -> "Stopped"
                    "I" -> "Idle"
                    else -> stateChar
                }

                val threads = pidStatAfter?.threads ?: 0

                val cacheInfo = appCache.getOrPut(processName) {
                    try {
                        val info = pm.getApplicationInfo(processName, 0)
                        AppCacheInfo(pm.getApplicationLabel(info).toString(), info.uid)
                    } catch (_: PackageManager.NameNotFoundException) {
                        val fallback = processName.substringAfterLast(".").replaceFirstChar { it.uppercaseChar() }.ifBlank { processName }
                        AppCacheInfo(fallback, null)
                    }
                }


                val isRecent  = recentPackages.any { processName == it || processName.startsWith("$it:") }

                val isSystemProcess = (cacheInfo.uid ?: 0) < 10000

                val memoryMb = rssKb / 1024f
                if (memoryMb < 0.1f && cpuPct == 0f && threads == 0) return@mapNotNull null

                ProcessItem(
                    name        = cacheInfo.label,
                    packageName = processName,
                    pid         = pid,
                    state       = stateWord,
                    threads     = threads,
                    cpuPercent  = cpuPct,
                    memoryMb    = memoryMb,
                    isRecentTask = isRecent,
                    isSystem = isSystemProcess
                )
            }

            Pair(items, memPct)
        } catch (_: Exception) {
            Pair(emptyList(), 0f)
        }
    }

private fun getRecentPackages(): Set<String> {
    val out = shellExec("dumpsys activity recents | grep 'realActivity='")
    // Output looks like: realActivity={com.android.chrome/com.google...}
    return out.lines()
        .mapNotNull { line ->
            line.substringAfter("realActivity={", "")
                .substringBefore("/", "")
                .trim()
                .ifBlank { null }
        }.toSet()
}

// --- Main composable ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskManager() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var searchQuery    by remember { mutableStateOf("") }
    var sortColumn     by remember { mutableStateOf(SortColumn.NAME) }
    var sortAscending  by remember { mutableStateOf(true) }
    var selectedPid by remember { mutableStateOf<Int?>(null) }
    val focusRequester = remember { FocusRequester() }
    var processes      by remember { mutableStateOf<List<ProcessItem>>(emptyList()) }
    var isLoading      by remember { mutableStateOf(true) }
    var shizukuReady   by remember { mutableStateOf(
        Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    )}

    var colName    by remember { mutableStateOf(220.dp) }
    var colState   by remember { mutableStateOf(90.dp) }
    var colThreads by remember { mutableStateOf(80.dp) }
    var colCpu     by remember { mutableStateOf(80.dp) }
    var colMemory  by remember { mutableStateOf(100.dp) }

    var mousePosition by remember { mutableStateOf<Offset?>(null) }
    val itemPositions = remember { mutableStateMapOf<Int, Rect>() }
    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val lazyListState = rememberLazyListState()

    // Re-check whenever Shizuku binder state changes
    DisposableEffect(Unit) {
        val listener = Shizuku.OnBinderReceivedListener {
            shizukuReady = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
        Shizuku.addBinderReceivedListenerSticky(listener)
        onDispose { Shizuku.removeBinderReceivedListener(listener) }
    }

    var memPercent by remember { mutableStateOf(0f) }

    LaunchedEffect(shizukuReady) {
        if (!shizukuReady) return@LaunchedEffect
        while (true) {
            val (procs, mem) = fetchProcesses(context)
            processes  = procs
            memPercent = mem
            isLoading  = false
            delay(1000)
        }
    }

    val endSelectedTask = {
        val processToKill = processes.find { it.pid == selectedPid }
        if (processToKill != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val basePackage = processToKill.packageName.substringBefore(":")
                shellExec("am force-stop $basePackage")
                shellExec("kill -9 ${processToKill.pid}")
            }
        }
        selectedPid = null // Clear selection after killing
    }

    val filtered = processes
        .filter { it.name.contains(searchQuery, ignoreCase = true) }
        .sortedWith { a, b ->
            if (sortColumn == SortColumn.NAME) {
                // 1. Always keep Category order consistent (Apps -> Background -> Android)
                // We usually don't want "Android processes" at the very top of the screen
                fun getWeight(i: ProcessItem) = when {
                    i.isRecentTask -> 0
                    !i.isSystem -> 1
                    else -> 2
                }

                val weightCompare = getWeight(a).compareTo(getWeight(b))

                if (weightCompare != 0) {
                    weightCompare // Categories always stay in 0, 1, 2 order
                } else {
                    // 2. Sort by name WITHIN the category, respecting the toggle
                    val nameCompare = a.name.compareTo(b.name, ignoreCase = true)
                    if (sortAscending) nameCompare else -nameCompare
                }
            } else {
                // 3. Sorting for other columns (CPU, Memory, etc.)
                val r = when (sortColumn) {
                    SortColumn.STATE   -> a.state.compareTo(b.state, ignoreCase = true)
                    SortColumn.THREADS -> a.threads.compareTo(b.threads)
                    SortColumn.CPU     -> a.cpuPercent.compareTo(b.cpuPercent)
                    SortColumn.MEMORY  -> a.memoryMb.compareTo(b.memoryMb)
                    else -> 0
                }
                if (sortAscending) r else -r
            }
        }

    val totalCpu   = processes.sumOf { it.cpuPercent.toDouble() }.toFloat().coerceAtMost(100f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task Manager", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                actions = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search...", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, null, Modifier.size(20.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .width(200.dp)
                            .height(48.dp) // Keep it compact
                            .padding(end = 16.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalDivider()
            when {
                !shizukuReady -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Waiting for Shizuku permission…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Reading processes…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    val scrollState = rememberScrollState()

                    val configuration = LocalConfiguration.current
                    val screenWidth = configuration.screenWidthDp.dp

                    val dividerWidth = maxOf(screenWidth, 574.dp)

                    Column(
                        modifier = Modifier
                            .horizontalScroll(scrollState)
                            .weight(1f)
                            .fillMaxWidth()
                            .processListGestures(
                                filtered = filtered,
                                selectedPid = selectedPid,
                                onSelectionChange = { selectedPid = it },
                                lazyListState = lazyListState,
                                coroutineScope = coroutineScope,
                                focusRequester = focusRequester,
                                onEndTask = endSelectedTask
                            )
                            // 1. Get container coordinates
                            .onGloballyPositioned { containerCoordinates = it }
                            // 2. Track global mouse position
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val firstChange = event.changes.firstOrNull() ?: continue
                                        if (firstChange.type == PointerType.Mouse) {
                                            if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                                                mousePosition = firstChange.position
                                            } else if (event.type == PointerEventType.Exit) {
                                                mousePosition = null
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        ProcessTableHeader(
                            sortColumn    = sortColumn,
                            sortAscending = sortAscending,
                            totalCpuPct   = totalCpu,
                            totalMemPct   = memPercent,
                            colName       = colName,
                            colState      = colState,
                            colThreads    = colThreads,
                            colCpu        = colCpu,
                            colMemory     = colMemory,
                            onResizeName    = { colName    = (colName    + it).coerceAtLeast(60.dp) },
                            onResizeState   = { colState   = (colState   + it).coerceAtLeast(60.dp) },
                            onResizeThreads = { colThreads = (colThreads + it).coerceAtLeast(40.dp) },
                            onResizeCpu     = { colCpu     = (colCpu     + it).coerceAtLeast(50.dp) },
                            onResizeMemory  = { colMemory  = (colMemory  + it).coerceAtLeast(60.dp) },
                            onSort = { col ->
                                if (sortColumn == col) sortAscending = !sortAscending
                                else { sortColumn = col; sortAscending = false }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.width(dividerWidth))

                        LazyColumn(state = lazyListState) {
                            // 1. Calculate the groups ONCE at the top of the LazyColumn
                            val grouped = if (sortColumn == SortColumn.NAME) {
                                filtered.groupBy { item ->
                                    when {
                                        item.isRecentTask -> "Apps"
                                        !item.isSystem -> "Background processes"
                                        else -> "Android processes"
                                    }
                                }
                            } else {
                                mapOf("" to filtered)
                            }

                            // 2. Iterate through the groups
                            grouped.forEach { (category, itemsInGroup) ->

                                // 3. Add the Category Header (Apps / Background processes)
                                if (category.isNotEmpty()) {
                                    this@LazyColumn.item(key = category) {
                                        CategoryHeader(
                                            text = category,
                                            count = itemsInGroup.size,
                                            colName = colName,
                                            colState = colState,
                                            colThreads = colThreads,
                                            colCpu = colCpu,
                                            colMemory = colMemory
                                        )
                                    }
                                }

                                // 4. Add the actual Process Rows for this specific group
                                this@LazyColumn.items(itemsInGroup, key = { it.pid }) { process ->

                                    // --- Hover Logic (Placed correctly inside the final items block) ---
                                    var itemRect by remember { mutableStateOf<Rect?>(null) }
                                    val isHovered by remember(processes) {
                                        derivedStateOf {
                                            // Force refresh on scroll
                                            lazyListState.firstVisibleItemScrollOffset

                                            val currentRect = itemRect
                                            val mouse = mousePosition
                                            if (mouse != null && currentRect != null) {
                                                currentRect.contains(mouse)
                                            } else {
                                                false
                                            }
                                        }
                                    }

                                    ProcessRow(
                                        process = process,
                                        isSelected = selectedPid == process.pid,
                                        isHovered = isHovered,
                                        modifier = Modifier.onGloballyPositioned { coords ->
                                            containerCoordinates?.let { parent ->
                                                if (coords.isAttached && parent.isAttached) {
                                                    itemRect = parent.localBoundingBoxOf(coords)
                                                }
                                            }
                                        },
                                        colName = colName,
                                        colState = colState,
                                        colThreads = colThreads,
                                        colCpu = colCpu,
                                        colMemory = colMemory,
                                        onClick = {
                                            selectedPid = if (selectedPid == process.pid) null else process.pid
                                            focusRequester.requestFocus()
                                        }
                                    )

                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            ActionBar(
                hasSelection = selectedPid != null,
                onEndTask = endSelectedTask
            )
        }
    }
}

// --- Action bar ---

@Composable
fun ActionBar(hasSelection: Boolean, onEndTask: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick  = onEndTask,
            enabled  = hasSelection,
            colors   = ButtonDefaults.textButtonColors(
                contentColor        = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        ) {
            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("End task")
        }
    }
}

// --- Table header ---

@Composable
fun ProcessTableHeader(
    sortColumn: SortColumn, sortAscending: Boolean,
    totalCpuPct: Float, totalMemPct: Float,
    colName: Dp, colState: Dp, colThreads: Dp, colCpu: Dp, colMemory: Dp,
    onResizeName: (Dp) -> Unit, onResizeState: (Dp) -> Unit,
    onResizeThreads: (Dp) -> Unit, onResizeCpu: (Dp) -> Unit,
    onResizeMemory: (Dp) -> Unit,
    onSort: (SortColumn) -> Unit
) {
    Row(Modifier.height(50.dp)) {
        HeaderCell("Name",    null,                             Modifier.width(colName),    SortColumn.NAME,    sortColumn, sortAscending, onSort)
        VerticalResizeHandle(onResize = onResizeName)
        HeaderCell("State",   null,                             Modifier.width(colState),   SortColumn.STATE,   sortColumn, sortAscending, onSort)
        VerticalResizeHandle(onResize = onResizeState)
        HeaderCell("Threads", null,                             Modifier.width(colThreads), SortColumn.THREADS, sortColumn, sortAscending, onSort)
        VerticalResizeHandle(onResize = onResizeThreads)
        HeaderCell("CPU",     "%.0f%%".format(totalCpuPct),    Modifier.width(colCpu),     SortColumn.CPU,     sortColumn, sortAscending, onSort)
        VerticalResizeHandle(onResize = onResizeCpu)
        HeaderCell("Memory",  "%.0f%%".format(totalMemPct),    Modifier.width(colMemory),  SortColumn.MEMORY,  sortColumn, sortAscending, onSort)
        VerticalResizeHandle(onResize = onResizeMemory)
    }
}

@Composable
fun HeaderCell(
    label: String, subtitle: String?, modifier: Modifier,
    column: SortColumn, sortColumn: SortColumn, sortAscending: Boolean, onSort: (SortColumn) -> Unit
) {
    val isActive = sortColumn == column
    // TRUE for both Name and State columns!
    val isLeftAligned = column == SortColumn.NAME || column == SortColumn.STATE

    Column(
        modifier = modifier
            .clickable { onSort(column) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = if (isLeftAligned) Alignment.Start else Alignment.End
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 20.dp),
            horizontalArrangement = if (isLeftAligned) Arrangement.Center else Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLeftAligned) {
                if (isActive) {
                    Icon(
                        imageVector = if (sortAscending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Sort Direction", modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else { Spacer(modifier = Modifier.size(16.dp)) }
            } else {
                if (isActive) {
                    Icon(
                        imageVector = if (sortAscending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Sort Direction", modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else { Spacer(modifier = Modifier.size(16.dp)) }

                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
        }
        Text(
            text = label, style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// --- Process row ---

@Composable
fun ProcessRow(
    process: ProcessItem, isSelected: Boolean,
    isHovered: Boolean,
    modifier: Modifier = Modifier,
    colName: Dp, colState: Dp, colThreads: Dp, colCpu: Dp, colMemory: Dp,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(process.packageName) {
        try { context.packageManager.getApplicationIcon(process.packageName.substringBefore(":")) }
        catch (_: PackageManager.NameNotFoundException) { null }
    }

    Row(
        modifier = modifier
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) // Your hover effect!
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(colName).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (appIcon != null) {
                AsyncImage(model = appIcon, contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)))
            } else {
                Icon(Icons.Default.Settings, contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(process.name, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        VerticalDivider()

        Box(modifier = Modifier.width(colState).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart) {
            Text(process.state, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        VerticalDivider()

        DataCell(process.threads.toString(), Modifier.width(colThreads))
        VerticalDivider()
        HeatCell(process.cpuPercent, 100f, "%.1f%%".format(process.cpuPercent), Modifier.width(colCpu))
        VerticalDivider()
        HeatCell(process.memoryMb, 512f, "%.0f MB".format(process.memoryMb), Modifier.width(colMemory))
        VerticalDivider()
    }
}

@Composable
fun HeatCell(value: Float, max: Float, text: String, modifier: Modifier) {
    val ratio = (value / max).coerceIn(0f, 1f)

    // 1. Define base colors (using Material colors for better UI fit)
    // Applying 0.4f alpha so the text on top of the background remains readable
    val transparentYellow = Color(0xFFFFEB3B).copy(alpha = 0f) // Prevents muddy/gray transitions
    val yellow = Color(0xFFFFEB3B).copy(alpha = 0.4f)
    val orange = Color(0xFFFF9800).copy(alpha = 0.4f)
    val red = Color(0xFFF44336).copy(alpha = 0.4f)

    // 2. Multi-stop interpolation based on the ratio (0.0 to 1.0)
    val tint = when {
        ratio <= 0.33f -> {
            // 0% to 33% -> Transparent to Yellow
            val segmentRatio = ratio / 0.33f
            lerp(transparentYellow, yellow, segmentRatio)
        }
        ratio <= 0.66f -> {
            // 33% to 66% -> Yellow to Orange
            val segmentRatio = (ratio - 0.33f) / 0.33f
            lerp(yellow, orange, segmentRatio)
        }
        else -> {
            // 66% to 100% -> Orange to Red
            val segmentRatio = (ratio - 0.66f) / 0.34f
            lerp(orange, red, segmentRatio)
        }
    }

    Box(
        modifier = modifier
            .background(tint)
            .fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun DataCell(text: String, modifier: Modifier) {
    Box(modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterEnd) {
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VerticalResizeHandle(
    onResize: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val resizeIcon = remember(context) {
        androidx.compose.ui.input.pointer.PointerIcon(
            PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
        )
    }

    Box(
        modifier = modifier
            // 1. Tell the parent Row that this handle only takes up 1.dp of space
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val layoutWidth = 1.dp.roundToPx() // Matches standard VerticalDivider width

                layout(layoutWidth, placeable.height) {
                    // Center the 16dp touch area over the 1dp layout line
                    val xOffset = -(placeable.width - layoutWidth) / 2
                    placeable.place(xOffset, 0)
                }
            }
            // 2. But keep the actual touch target 16.dp wide so it's easy to grab
            .width(16.dp)
            .pointerHoverIcon(resizeIcon)
            .pointerInput(onResize) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val dragChange = event.changes.firstOrNull() ?: break
                        if (!dragChange.pressed) break
                        val deltaPx = dragChange.positionChange().x
                        if (deltaPx != 1f) {
                            val deltaDp = with(density) { deltaPx.toDp() }
                            onResize(deltaDp)
                            dragChange.consume()
                        }
                    }
                }
            },
        contentAlignment = contentAlignment
    ) {
        // The visible 1.dp line
        VerticalDivider(
            modifier = Modifier.width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
fun CategoryHeader(
    text: String,
    count: Int,
    colName: Dp,
    colState: Dp,
    colThreads: Dp,
    colCpu: Dp,
    colMemory: Dp
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .height(32.dp), // Match the height of ProcessRow for consistency
        verticalAlignment = Alignment.CenterVertically
    ) {
        // First Column (Name)
        Row(
            modifier = Modifier.width(colName).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$text ($count)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
        }

        // Match the dividers used in ProcessRow
        VerticalDivider()

        // Empty State Column
        Box(modifier = Modifier.width(colState))
        VerticalDivider()

        // Empty Threads Column
        Box(modifier = Modifier.width(colThreads))
        VerticalDivider()

        // Empty CPU Column
        Box(modifier = Modifier.width(colCpu))
        VerticalDivider()

        // Empty Memory Column
        Box(modifier = Modifier.width(colMemory))
        VerticalDivider()
    }
}