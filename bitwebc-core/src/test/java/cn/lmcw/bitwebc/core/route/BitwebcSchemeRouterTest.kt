package cn.lmcw.bitwebc.core.route

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwebcSchemeRouterTest {

    @Test
    fun `failed custom scheme result should still consume navigation`() {
        assertTrue(BitwebcSchemeRouter.Result.CONSUMED.consumesNavigation)
    }

    @Test
    fun `web schemes should remain eligible for the next client`() {
        assertFalse(BitwebcSchemeRouter.Result.PASS_THROUGH.consumesNavigation)
    }
}
