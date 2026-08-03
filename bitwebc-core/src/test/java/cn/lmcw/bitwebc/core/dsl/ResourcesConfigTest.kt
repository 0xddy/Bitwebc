package cn.lmcw.bitwebc.core.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResourcesConfigTest {

    @Test
    fun `asset routes live outside WebSettings and freeze to a copy`() {
        val config = ResourcesConfig()
        config.assets {
            route("https://app.example.test/", "offline")
        }
        val frozen = config.snapshot()

        config.assets {
            route("https://second.example.test/", "second")
        }

        assertEquals(
            listOf("https://app.example.test/" to "offline"),
            frozen.assetRoutes
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `asset route rejects traversal immediately`() {
        ResourcesConfig().assets {
            route("https://app.example.test/", "../private")
        }
    }

    @Test
    fun `more specific asset routes are matched first`() {
        val config = ResourcesConfig().apply {
            assets {
                route("https://app.example.test/app/", "general")
                route("https://app.example.test/app/admin/", "admin")
            }
        }

        assertEquals(
            listOf(
                "https://app.example.test/app/admin/" to "admin",
                "https://app.example.test/app/" to "general"
            ),
            config.snapshot().assetRoutes
        )
    }

    @Test
    fun `equivalent asset route prefixes are rejected`() {
        val config = ResourcesConfig()
        config.assets { route("https://APP.example.test/path/", "first") }

        assertThrows(IllegalArgumentException::class.java) {
            config.assets { route("https://app.example.test:443/path", "second") }
        }
    }
}
