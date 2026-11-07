package org.example.project.components.terminal.window.controller.console

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap

/** What one run used, as far as the JVM can tell it apart from the rest of the app */
internal data class RunUse(
    /** CPU time on the thread the script runs on, summed over the threads it ran on; null where the platform can't say */
    val cpuNanos: Long?,
    /** Bytes allocated on that thread: what the run churned through, not what it holds */
    val allocatedBytes: Long?,
    /** The keys of the sessions it opened, as SessionInfo has them, those closed since included */
    val sessionKeys: Set<String>,
)

/**
 * The CPU time and the allocation of the threads a run's script body executes on. [track] wraps one stretch of work on
 * the calling thread; what a thread used before it ended is kept, so a thread that has gone, or a run that changed
 * threads, loses nothing. Threads the script starts itself are not in it. A read is two calls on the thread bean.
 */
internal class ThreadUsage {
    private val active = ConcurrentHashMap<Thread, LongArray>()
    private var cpuDone = 0L
    private var allocatedDone = 0L

    fun <T> track(block: () -> T): T {
        val thread = Thread.currentThread()
        active[thread] = longArrayOf(cpuOf(thread), allocatedOf(thread))
        try {
            return block()
        } finally {
            val start = active.remove(thread)
            if (start != null) synchronized(this) {
                cpuDone += (cpuOf(thread) - start[0]).coerceAtLeast(0)
                allocatedDone += (allocatedOf(thread) - start[1]).coerceAtLeast(0)
            }
        }
    }

    /** CPU time used so far, null when the platform gives none */
    val cpuNanos: Long?
        get() = if (!threads.isThreadCpuTimeSupported) null else synchronized(this) { cpuDone } + active.entries.sumOf { (t, start) -> (cpuOf(t) - start[0]).coerceAtLeast(0) }

    /** Bytes allocated so far, null when the platform gives none */
    val allocatedBytes: Long?
        get() = if (allocating == null) null else synchronized(this) { allocatedDone } + active.entries.sumOf { (t, start) -> (allocatedOf(t) - start[1]).coerceAtLeast(0) }

    private fun cpuOf(thread: Thread): Long = runCatching { threads.getThreadCpuTime(thread.id).coerceAtLeast(0) }.getOrDefault(0)

    private fun allocatedOf(thread: Thread): Long = runCatching { allocating?.getThreadAllocatedBytes(thread.id)?.coerceAtLeast(0) }.getOrNull() ?: 0

    private companion object {
        val threads = ManagementFactory.getThreadMXBean()
        val allocating = threads as? com.sun.management.ThreadMXBean
    }
}
