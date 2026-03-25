package com.troikoss.taskmanager

import android.content.pm.PackageManager
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.troikoss.taskmanager.utils.ProcessItem
import com.troikoss.taskmanager.utils.SortColumn
import com.troikoss.taskmanager.utils.fetchProcesses
import com.troikoss.taskmanager.utils.processListGestures
import com.troikoss.taskmanager.utils.shellExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

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

    val expandedGroups = remember { mutableStateOf(setOf<Int>()) }

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
                            // 1. Group the filtered list by Category (Apps/Background/Android)
                            val categoryGroups = if (sortColumn == SortColumn.NAME) {
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

                            categoryGroups.forEach { (category, itemsInCategory) ->

                                // 2. Add Category Header
                                if (category.isNotEmpty()) {
                                    this@LazyColumn.item(key = category) {
                                        CategoryHeader(
                                            text = category,
                                            count = itemsInCategory.size,
                                            colName = colName,
                                            colState = colState,
                                            colThreads = colThreads,
                                            colCpu = colCpu,
                                            colMemory = colMemory
                                        )
                                    }
                                }

                                // 3. Sub-group items in this category by UID (User ID)
                                val subGroups = itemsInCategory.groupBy { it.uid }

                                subGroups.forEach { (uid, processesInUid) ->
                                    val isMultiProcess = processesInUid.size > 1
                                    val isExpanded = expandedGroups.value.contains(uid)

                                    // The "Main" process for the header (usually the one with most memory)
                                    val mainProcess = processesInUid.maxByOrNull { it.memoryMb } ?: processesInUid.first()

                                    // 4. THE GROUP HEADER (OR SINGLE PROCESS)
                                    this@LazyColumn.item(key = "group_header_${mainProcess.pid}") {
                                        // Calculate totals for the whole app
                                        val totalCpu = processesInUid.sumOf { it.cpuPercent.toDouble() }.toFloat()
                                        val totalMem = processesInUid.sumOf { it.memoryMb.toDouble() }.toFloat()
                                        val totalThreads = processesInUid.sumOf { it.threads }

                                        var itemRect by remember { mutableStateOf<Rect?>(null) }
                                        val isHovered by remember(processes) {
                                            derivedStateOf {
                                                lazyListState.firstVisibleItemScrollOffset
                                                val currentRect = itemRect
                                                val mouse = mousePosition
                                                if (mouse != null && currentRect != null) currentRect.contains(mouse) else false
                                            }
                                        }

                                        ProcessRow(
                                            process = mainProcess.copy(
                                                cpuPercent = totalCpu,
                                                memoryMb = totalMem,
                                                threads = totalThreads
                                            ),
                                            isSelected = selectedPid == mainProcess.pid,
                                            isHovered = isHovered,
                                            isHeader = isMultiProcess,         // New param for Chevron
                                            isExpanded = isExpanded,           // New param for Chevron state
                                            onExpandToggle = {                 // Logic to open/close
                                                expandedGroups.value = if (isExpanded) expandedGroups.value - uid else expandedGroups.value + uid
                                            },
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
                                                selectedPid = if (selectedPid == mainProcess.pid) null else mainProcess.pid
                                                focusRequester.requestFocus()
                                            }
                                        )

                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                    }

                                    // 5. THE SUB-PROCESSES (Only shown if expanded)
                                    if (isMultiProcess && isExpanded) {
                                        this@LazyColumn.items(processesInUid, key = { "sub_${it.pid}" }) { subProcess ->
                                            var itemRect by remember { mutableStateOf<Rect?>(null) }
                                            val isHovered by remember(processes) {
                                                derivedStateOf {
                                                    lazyListState.firstVisibleItemScrollOffset
                                                    val currentRect = itemRect
                                                    val mouse = mousePosition
                                                    if (mouse != null && currentRect != null) currentRect.contains(mouse) else false
                                                }
                                            }

                                            ProcessRow(
                                                process = subProcess,
                                                isSelected = selectedPid == subProcess.pid,
                                                isHovered = isHovered,
                                                isSubProcess = true, // New param for Indentation
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
                                                    selectedPid = if (selectedPid == subProcess.pid) null else subProcess.pid
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