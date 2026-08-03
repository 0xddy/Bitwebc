package cn.lmcw.bitwebc.core.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientExtensionsTest {

    @Test
    fun `asset route strips query and fragment`() {
        assertEquals(
            "offline/index.html",
            "https://app.example.test/pkg/index.html?theme=dark#top"
                .toAssetsPath("https://app.example.test/pkg/", "offline")
        )
    }

    @Test
    fun `asset route compares exact origin including effective port`() {
        assertEquals(
            "offline/app.js",
            "https://app.example.test:443/app.js"
                .toAssetsPath("https://app.example.test/", "offline")
        )
        assertNull(
            "https://app.example.test:8443/app.js"
                .toAssetsPath("https://app.example.test/", "offline")
        )
    }

    @Test
    fun `asset route rejects origin confusion and traversal`() {
        assertNull(
            "https://app.example.test.evil/index.html"
                .toAssetsPath("https://app.example.test/", "offline")
        )
        assertNull(
            "https://app.example.test/%2e%2e/secrets.txt"
                .toAssetsPath("https://app.example.test/", "offline")
        )
    }

    @Test
    fun `asset route scope uses normalized exact origin and path boundary`() {
        assertTrue(
            "https://APP.example.test:443/pkg/missing.js"
                .isWithinAssetsRoute("https://app.example.test/pkg/")
        )
        assertFalse(
            "https://app.example.test/pkg-evil/file.js"
                .isWithinAssetsRoute("https://app.example.test/pkg/")
        )
        assertFalse(
            "https://app.example.test.evil/pkg/file.js"
                .isWithinAssetsRoute("https://app.example.test/pkg/")
        )
    }
}
