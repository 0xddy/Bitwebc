package cn.lmcw.bitwebc.download

import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.download.handler.BitwebcDownloadHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadControllerApiTest {
    @Test
    fun handlerImplementsControllerContract() {
        assertTrue(DownloadController::class.java.isAssignableFrom(BitwebcDownloadHandler::class.java))
    }

    @Test
    fun tasksGetterExposesReadOnlyStateFlowType() {
        val contractReturnType = DownloadController::class.java.getMethod("getTasks").returnType
        val implementationReturnType = BitwebcDownloadHandler::class.java.getMethod("getTasks").returnType

        assertEquals(StateFlow::class.java, contractReturnType)
        assertEquals(StateFlow::class.java, implementationReturnType)
        assertFalse(MutableStateFlow::class.java.isAssignableFrom(implementationReturnType))
    }

    @Test
    fun factoryKeepsLegacyReturnsAndAddsControllerReturns() {
        assertEquals(DownloadController::class.java, factoryReturnType("createDefaultController", 2))
        assertEquals(DownloadController::class.java, factoryReturnType("createController", 3))
        assertEquals(DownloadHandler::class.java, factoryReturnType("createDefault", 2))
        assertEquals(DownloadHandler::class.java, factoryReturnType("create", 3))
    }

    private fun factoryReturnType(name: String, parameterCount: Int): Class<*> =
        BitwebcDownloadFactory::class.java.methods
            .single { method -> method.name == name && method.parameterCount == parameterCount }
            .returnType
}
