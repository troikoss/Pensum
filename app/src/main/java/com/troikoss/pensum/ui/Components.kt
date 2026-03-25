package com.troikoss.pensum.ui

import android.content.pm.PackageManager
import android.view.PointerIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.troikoss.pensum.utils.ProcessItem
import com.troikoss.pensum.utils.SortColumn


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
    onClick: () -> Unit,
    isHeader: Boolean = false,
    isSubProcess: Boolean = false,
    isExpanded: Boolean = false,
    onExpandToggle: () -> Unit = {},
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

            // 1. Indent sub-processes
            if (isSubProcess) Spacer(Modifier.width(24.dp))

            // 2. Show Expand/Collapse Icon
            if (isHeader) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).clickable { onExpandToggle() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!isSubProcess) {
                // Keep alignment for single processes
                Spacer(Modifier.width(16.dp))
            }


            if (appIcon != null) {
                AsyncImage(model = appIcon, contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)))
            } else {
                Icon(Icons.Default.Settings, contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                text = if (isSubProcess) process.packageName.substringAfter(":") else process.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

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