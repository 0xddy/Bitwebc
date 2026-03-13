package cn.lmcw.bitwebc.core.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

class BitwebcEventHub {
    private val listeners = CopyOnWriteArrayList<BitwebcEventListener>()
    private val _events = MutableSharedFlow<BitwebcEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<BitwebcEvent> = _events.asSharedFlow()

    fun addListener(listener: BitwebcEventListener) {
        listeners += listener
    }

    fun removeListener(listener: BitwebcEventListener) {
        listeners -= listener
    }

    fun emit(event: BitwebcEvent) {
        _events.tryEmit(event)
        listeners.forEach { it.onEvent(event) }
    }
}
