package cn.lmcw.bitwebc.core.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class BitwebcEventHub {
    private val closed = AtomicBoolean(false)
    private val emissionLock = Any()
    private val listeners = CopyOnWriteArrayList<BitwebcEventListener>()
    private val _events = MutableSharedFlow<BitwebcEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<BitwebcEvent> = _events.asSharedFlow()

    fun addListener(listener: BitwebcEventListener) {
        if (closed.get()) return
        listeners += listener
        if (closed.get()) listeners -= listener
    }

    fun removeListener(listener: BitwebcEventListener) {
        listeners -= listener
    }

    fun clearListeners() {
        listeners.clear()
    }

    fun close() {
        val didClose = synchronized(emissionLock) {
            closed.compareAndSet(false, true)
        }
        if (didClose) listeners.clear()
    }

    fun emit(event: BitwebcEvent) {
        val accepted = synchronized(emissionLock) {
            if (closed.get()) {
                false
            } else {
                _events.tryEmit(event)
                true
            }
        }
        if (!accepted) return
        listeners.forEach { listener ->
            if (closed.get()) return
            runCatching { listener.onEvent(event) }
        }
    }
}
