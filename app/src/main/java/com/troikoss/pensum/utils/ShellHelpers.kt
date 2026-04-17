package com.troikoss.pensum.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private data class AppCacheInfo(val label: String, val uid: Int?)
private val appCache = mutableMapOf<String, AppCacheInfo>()

private data class SysStat(val total: Long, val idle: Long)
private data class PidStat(val utimeStime: Long, val state: String, val threads: Int)
private data class SystemSnapshot(val sysStat: SysStat?, val pidStats: Map<Int, PidStat>)

fun shellExec(cmd: String): String = try {
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

suspend fun fetchProcesses(context: Context): Pair<List<ProcessItem>, Float> =
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

            val topPackage = getForegroundPackage()
            val recentPackages = getRecentPackages()

            val items = entries.mapNotNull { (pid, rssKb, processName) ->
                val pidStatBefore = snapBefore.pidStats[pid]
                val pidStatAfter = snapAfter.pidStats[pid]

                val pidDelta = (pidStatAfter?.utimeStime ?: 0L) - (pidStatBefore?.utimeStime ?: 0L)
                val cpuPct = (pidDelta.toFloat() / sysDelta.toFloat() * 100f).coerceIn(0f, 100f)


                val isRecent  = recentPackages.any { processName == it || processName.startsWith("$it:") }
                val isForeground = topPackage.isNotEmpty() && (processName == topPackage || processName.startsWith("$topPackage:"))

                // 2. State Logic
                val stateChar = pidStatAfter?.state ?: "S"
                val stateWord = when {
                    // If the app is in the foreground, we don't care what the kernel says, it's "Running" to the user
                    isForeground -> "Running"
                    stateChar == "R" -> "Running"
                    stateChar == "D" -> "Disk Sleep"
                    stateChar == "Z" -> "Zombie"
                    stateChar == "T" || stateChar == "t" -> "Stopped"
                    else -> "Sleeping"
                }

                val threads = pidStatAfter?.threads ?: 0

                val cacheInfo = appCache.getOrPut(processName) {
                    try {
                        val packageName = processName.substringBefore(":")
                        val info = pm.getApplicationInfo(packageName, 0)
                        AppCacheInfo(pm.getApplicationLabel(info).toString(), info.uid)
                    } catch (_: PackageManager.NameNotFoundException) {
                        val fallback = processName.substringAfterLast(".").replaceFirstChar { it.uppercaseChar() }.ifBlank { processName }
                        AppCacheInfo(fallback, null)
                    }
                }

                val currentUid = cacheInfo.uid ?: -1

                val isSystemProcess = (cacheInfo.uid ?: 0) < 10000
                val isKernelTask = processName.startsWith("[") && processName.endsWith("]")

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
                    isSystem = isSystemProcess,
                    isForeground = isForeground,
                    isKernelTask = isKernelTask,
                    uid = currentUid
                )
            }

            Pair(items, memPct)
        } catch (_: Exception) {
            Pair(emptyList(), 0f)
        }
    }

private fun getRecentPackages(): Set<String> {
    val out = shellExec("dumpsys activity recents")
    // Regex matches "realActivity=com.package.name" or "realActivity={com.package.name/..."
    val regex = Regex("""realActivity=\{?([a-zA-Z0-9._]+)""")
    return regex.findAll(out).map { it.groupValues[1] }.toSet()
}

private fun getForegroundPackage(): String {
    // We check both the Resumed Activity and the Focused Window for maximum compatibility
    val raw = shellExec("dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity'")

    // Look for a package name pattern followed by a slash (e.g., com.android.settings/)
    val regex = Regex("""([a-zA-Z0-9._]+)/""")
    val match = regex.find(raw)

    return match?.groupValues?.get(1) ?: ""
}