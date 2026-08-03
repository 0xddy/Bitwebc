package cn.lmcw.bitwebc.core.event

import org.junit.Assert.assertEquals
import org.junit.Test

class BitwebcEventHubTest {

    @Test
    fun `one failing listener does not block remaining listeners`() {
        val hub = BitwebcEventHub()
        var delivered = 0
        hub.addListener { error("listener failure") }
        hub.addListener { delivered += 1 }

        hub.emit(BitwebcEvent.PageFinished("https://example.test"))

        assertEquals(1, delivered)
    }

    @Test
    fun `closed hub rejects late and newly registered listeners`() {
        val hub = BitwebcEventHub()
        var delivered = 0
        hub.addListener { delivered += 1 }

        hub.close()
        hub.addListener { delivered += 10 }
        hub.emit(BitwebcEvent.PageFinished("https://example.test/late"))

        assertEquals(0, delivered)
    }
}
