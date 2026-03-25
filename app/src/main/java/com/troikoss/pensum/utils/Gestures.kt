package com.troikoss.pensum.utils

import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.collections.indexOfFirst

fun Modifier.processListGestures(
    filtered: List<ProcessItem>,
    selectedPid: Int?,
    onSelectionChange: (Int?) -> Unit,
    lazyListState: LazyListState,
    coroutineScope: CoroutineScope,
    focusRequester: FocusRequester,
    onEndTask: () -> Unit
): Modifier = this
    .focusRequester(focusRequester)
    .focusable()
    .onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
            if (filtered.isEmpty()) return@onKeyEvent false

            // 1. Handle Delete/Backspace gesture
            if (keyEvent.key == Key.Delete || keyEvent.key == Key.Backspace) {
                if (selectedPid != null) {
                    onEndTask()
                }
                return@onKeyEvent true // Consume event
            }

            // 2. Handle Navigation gestures
            val currentIndex = filtered.indexOfFirst { it.pid == selectedPid }
            val newIndex = when (keyEvent.key) {
                Key.DirectionUp -> if (currentIndex > 0) currentIndex - 1 else 0
                Key.DirectionDown -> if (currentIndex != -1) currentIndex + 1 else 0
                Key.PageUp -> {
                    val pageSize = lazyListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                    if (currentIndex != -1) currentIndex - pageSize else 0
                }
                Key.PageDown -> {
                    val pageSize = lazyListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                    if (currentIndex != -1) currentIndex + pageSize else 0
                }
                Key.MoveHome -> 0
                Key.MoveEnd -> filtered.size - 1
                else -> null
            }

            if (newIndex != null) {
                val clampedIndex = newIndex.coerceIn(0, filtered.size - 1)
                onSelectionChange(filtered[clampedIndex].pid)

                // Scroll the list to keep the selected item in view
                coroutineScope.launch {
                    val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                    if (visibleItems.isNotEmpty()) {
                        val firstVisible = visibleItems.first().index
                        val lastVisible = visibleItems.last().index

                        if (clampedIndex <= firstVisible) {
                            // Scroll up to show the top item
                            lazyListState.scrollToItem(clampedIndex)
                        } else if (clampedIndex >= lastVisible) {
                            // Scroll down just enough to show the bottom item
                            val offset = maxOf(0, clampedIndex - visibleItems.size + 2)
                            lazyListState.scrollToItem(offset)
                        }
                    }
                }
                return@onKeyEvent true // Consume event
            }
        }
        false
    }