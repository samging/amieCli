package org.example.project.components.terminal.window.controller.util

import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.runtime.ModelServer
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What the app process is using right now, for the models page */
data class SystemStats(
    val uptime: Duration,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val nonHeapUsedBytes: Long,
    // 0..1, negative when the platform doesn't report it
    val processCpu: Double,
    val systemCpu: Double,
    val processors: Int,
    val threads: Int,
    val ortSessions: Int,
    val ortRunning: Int,
    val ortRuns: Int,
    val ortRunTimeMs: Long,
    val listening: Int,
    // What the whole process holds (resident set, heap and native together), 0 when the platform can't say
    val residentBytes: Long = 0,
    // The machine's RAM and what is still free of it, 0 when the platform can't say
    val physicalBytes: Long = 0,
    val physicalFreeBytes: Long = 0,
    // The CPU time the process has used since it started, negative when the platform doesn't report it
    val processCpuNanos: Long = -1,
) {
    /** The share of the machine's RAM this process holds, 0..1, or -1 when unknown */
    val residentShare: Double get() = if (physicalBytes <= 0 || residentBytes <= 0) -1.0 else residentBytes.toDouble() / physicalBytes
    val heapShare: Double get() = if (heapMaxBytes <= 0) 0.0 else heapUsedBytes.toDouble() / heapMaxBytes

    companion object {
        private val runtimeBean = ManagementFactory.getRuntimeMXBean()
        private val memoryBean = ManagementFactory.getMemoryMXBean()
        private val threadBean = ManagementFactory.getThreadMXBean()
        private val osBean = ManagementFactory.getOperatingSystemMXBean()

        /** How many times [read] was called since the app started: the perf tests check the Models page's poller stops with the page */
        internal val reads = java.util.concurrent.atomic.AtomicLong()

        fun read(): SystemStats {
            reads.incrementAndGet()
            val heap = memoryBean.heapMemoryUsage
            val sessions = OrtSessions.all
            val os = osBean as? com.sun.management.OperatingSystemMXBean
            return SystemStats(
                uptime = Duration.ofMillis(runtimeBean.uptime),
                heapUsedBytes = heap.used,
                heapMaxBytes = heap.max.takeIf { it > 0 } ?: heap.committed,
                nonHeapUsedBytes = memoryBean.nonHeapMemoryUsage.used,
                processCpu = os?.processCpuLoad ?: -1.0,
                systemCpu = os?.cpuLoad ?: -1.0,
                processors = osBean.availableProcessors,
                threads = threadBean.threadCount,
                ortSessions = sessions.size,
                ortRunning = sessions.sumOf { it.running },
                ortRuns = sessions.sumOf { it.runs },
                ortRunTimeMs = sessions.sumOf { it.runTimeMs },
                listening = ModelServer.listening.size,
                residentBytes = residentBytes(),
                physicalBytes = os?.totalMemorySize ?: 0,
                physicalFreeBytes = os?.freeMemorySize ?: 0,
                processCpuNanos = os?.processCpuTime ?: -1,
            ).also { lastRead = System.nanoTime() to it }
        }

        @Volatile private var lastRead: Pair<Long, SystemStats>? = null

        /**
         * The last reading when it is younger than [maxAgeMs], else a new one: for a reader on a clock of its own beside
         * the footer's, such as the Models page's poll, which then costs no reading of its own while the footer is up
         */
        fun recent(maxAgeMs: Long = 3_000): SystemStats =
            lastRead?.takeIf { System.nanoTime() - it.first < TimeUnit.MILLISECONDS.toNanos(maxAgeMs) }?.second ?: read()

        // The JVM has no API for the resident set. On Linux the kernel says it in /proc/self/status, a read of a few
        // hundred bytes; elsewhere ps reports it in KB, a process forked for every reading, which costs real CPU on a
        // slow machine. The reading is kept [RESIDENT_CACHE_MS], so the memory footer and a run's meter, both asking
        // every second or two, share one. The wait for ps is bounded because a run's meter reads this on a thread
        // that has to end, and the few bytes of output fit the pipe until it's read
        private val procStatus = java.io.File("/proc/self/status")
        @Volatile private var residentRead: Pair<Long, Long>? = null

        private fun residentBytes(): Long {
            val now = System.nanoTime()
            residentRead?.let { (at, value) -> if (now - at < TimeUnit.MILLISECONDS.toNanos(RESIDENT_CACHE_MS)) return value }
            val value = (if (procStatus.isFile) runCatching { parseVmRss(procStatus.readText()) }.getOrNull() else null) ?: residentBytesFromPs()
            residentRead = now to value
            return value
        }

        private fun residentBytesFromPs(): Long = runCatching {
            val process = ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).redirectErrorStream(true).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching 0L
            }
            process.inputStream.bufferedReader().readText().trim().toLongOrNull()?.times(1024) ?: 0L
        }.getOrDefault(0L)

        /** The resident set a Linux `/proc/<pid>/status` says (`VmRSS:\t  123456 kB`), in bytes; null when it has no such line */
        internal fun parseVmRss(status: String): Long? = status.lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)

        /** The machine's free RAM alone, which costs one call where [read] may fork a process */
        fun machineFreeBytes(): Long = (osBean as? com.sun.management.OperatingSystemMXBean)?.freeMemorySize ?: 0

        /**
         * What the machine could give a new load, more than [physicalFreeBytes] says: that leaves out the file cache the
         * system gives back on demand, which on a Mac is most of it (425 MB "free" with 8 GB to be had was measured).
         * Linux says it in MemAvailable, macOS as the percentage kern.memorystatus_level, a process forked for the
         * reading, so this is for a decision and not for a footer
         */
        fun availableBytes(stats: SystemStats): Long {
            val meminfo = java.io.File("/proc/meminfo")
            val known = (if (meminfo.isFile) runCatching { parseMemAvailable(meminfo.readText()) }.getOrNull() else null) ?: macAvailableBytes(stats.physicalBytes)
            return maxOf(stats.physicalFreeBytes, known ?: 0)
        }

        private fun macAvailableBytes(physicalBytes: Long): Long? = runCatching {
            if ("mac" !in System.getProperty("os.name").lowercase()) return null
            val process = ProcessBuilder("sysctl", "-n", "kern.memorystatus_level").redirectErrorStream(true).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().readText().trim().toLongOrNull()?.let { physicalBytes / 100 * it }
        }.getOrNull()

        /** What a Linux `/proc/meminfo` says can be had (`MemAvailable:  123456 kB`), in bytes; null when it has no such line */
        internal fun parseMemAvailable(meminfo: String): Long? = meminfo.lineSequence()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)

        /** How long one reading of the resident set stands in for the next */
        const val RESIDENT_CACHE_MS = 1_000L
    }
}

fun bytes(value: Long): String = when {
    value >= 1L shl 30 -> "%.2f GB".format(Locale.US, value / 1073741824.0)
    value >= 1L shl 20 -> "%.1f MB".format(Locale.US, value / 1048576.0)
    value >= 1024 -> "${value / 1024} KB"
    else -> "$value B"
}

fun percent(load: Double): String = if (load < 0) "n/a" else "${(load * 100).toInt()}%"

/** "41s", "12m 03s", "3h 07m" or "2d 05h" */
fun uptime(duration: Duration): String {
    val s = duration.seconds
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${"%02d".format(s % 60)}s"
        s < 86400 -> "${s / 3600}h ${"%02d".format((s % 3600) / 60)}m"
        else -> "${s / 86400}d ${"%02d".format((s % 86400) / 3600)}h"
    }
}

fun ms(value: Double): String = if (value >= 1000) "%.2f s".format(Locale.US, value / 1000) else "%.0f ms".format(Locale.US, value)
