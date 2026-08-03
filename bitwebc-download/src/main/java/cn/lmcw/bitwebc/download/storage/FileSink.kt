package cn.lmcw.bitwebc.download.storage

import android.net.Uri
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transactional download destination. Call [commit] after a complete write or
 * [abort] after cancellation/failure. Both terminal operations are idempotent.
 */
data class FileSink @JvmOverloads constructor(
    val uri: Uri,
    val outputStream: OutputStream,
    val onFinish: () -> Unit,
    val onAbort: () -> Unit = {}
) {
    private val completed = AtomicBoolean(false)

    /** Retains the three-argument copy shape exposed before abort support. */
    fun copy(
        uri: Uri,
        outputStream: OutputStream,
        onFinish: () -> Unit
    ): FileSink = FileSink(uri, outputStream, onFinish)

    fun commit() {
        if (!completed.compareAndSet(false, true)) return

        try {
            outputStream.close()
            onFinish()
        } catch (error: Throwable) {
            runCatching(onAbort)
            throw error
        }
    }

    fun abort() {
        if (!completed.compareAndSet(false, true)) return

        var closeFailure: Throwable? = null
        try {
            outputStream.close()
        } catch (error: Throwable) {
            closeFailure = error
        }
        try {
            onAbort()
        } catch (error: Throwable) {
            closeFailure?.addSuppressed(error) ?: throw error
        }
        closeFailure?.let { throw it }
    }

    @Deprecated("Use commit() after a successful write", ReplaceWith("commit()"))
    fun close() {
        commit()
    }
}
