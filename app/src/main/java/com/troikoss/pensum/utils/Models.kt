package com.troikoss.pensum.utils

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
    val isKernelTask: Boolean,
    val uid: Int
)

enum class SortColumn { NAME, STATE, THREADS, CPU, MEMORY }
