package cn.lmcw.bitwebc.download.handler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Tracks the latest generation so a cancelled attempt cannot clean up its replacement. */
internal class DownloadAttemptRegistry {
    private val sequence = AtomicLong()
    private val active = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    fun begin(taskId: String): Long = synchronized(lock) {
        sequence.incrementAndGet().also { attemptId -> active[taskId] = attemptId }
    }

    fun beginIf(taskId: String, condition: () -> Boolean): Long? = synchronized(lock) {
        if (!condition()) return@synchronized null
        sequence.incrementAndGet().also { attemptId -> active[taskId] = attemptId }
    }

    fun isCurrent(taskId: String, attemptId: Long): Boolean = synchronized(lock) {
        active[taskId] == attemptId
    }

    fun finish(taskId: String, attemptId: Long): Boolean = synchronized(lock) {
        active.remove(taskId, attemptId)
    }

    fun <T> invalidateAndRun(taskId: String, action: () -> T): T = synchronized(lock) {
        active.remove(taskId)
        action()
    }

    fun <T> invalidateIfAndRun(
        taskId: String,
        condition: () -> Boolean,
        action: () -> T
    ): T? = synchronized(lock) {
        if (!condition()) return@synchronized null
        active.remove(taskId)
        action()
    }

    fun runIfCurrent(taskId: String, attemptId: Long, action: () -> Unit): Boolean =
        synchronized(lock) {
            if (active[taskId] != attemptId) return@synchronized false
            action()
            true
        }
}
