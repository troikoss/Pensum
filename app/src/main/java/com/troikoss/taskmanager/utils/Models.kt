package com.troikoss.taskmanager.utils

data class ProcessItem(
    val name: String,
    val packageName: String,
    val pid: Int,
    val state: String,
    val threads: Int,
    val cpuPercent: Float,
    val memoryMb: Float,
    val isRecentTask: Boolean,
    val isSystem: Boolean,
    val isForeground: Boolean,
    val uid: Int
)

enum class SortColumn { NAME, STATE, THREADS, CPU, MEMORY }
