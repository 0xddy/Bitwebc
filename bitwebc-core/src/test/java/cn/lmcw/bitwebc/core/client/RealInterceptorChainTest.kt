package cn.lmcw.bitwebc.core.client

import android.webkit.WebResourceRequest
import android.webkit.WebView
import cn.lmcw.bitwebc.core.api.WebResourceInterceptor
import cn.lmcw.bitwebc.core.testutil.UnsafeAndroidAllocator
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

class RealInterceptorChainTest {

    @Test
    fun `proceed should pass modified request to next interceptor and terminal`() {
        val originalRequest = fakeRequest("original")
        val modifiedRequest = fakeRequest("modified")
        var secondSeenRequest: WebResourceRequest? = null
        var terminalSeenRequest: WebResourceRequest? = null

        val interceptors = listOf(
            WebResourceInterceptor { chain ->
                chain.proceed(modifiedRequest)
            },
            WebResourceInterceptor { chain ->
                secondSeenRequest = chain.request
                chain.proceed(chain.request)
            }
        )

        val chain = RealInterceptorChain(
            interceptors = interceptors,
            index = 0,
            view = UnsafeAndroidAllocator.allocate(WebView::class.java),
            request = originalRequest
        ) { terminalRequest ->
            terminalSeenRequest = terminalRequest
            null
        }

        chain.proceed(originalRequest)

        assertSame(modifiedRequest, secondSeenRequest)
        assertSame(modifiedRequest, terminalSeenRequest)
    }

    private fun fakeRequest(name: String): WebResourceRequest {
        return Proxy.newProxyInstance(
            WebResourceRequest::class.java.classLoader,
            arrayOf(WebResourceRequest::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> name
                "hashCode" -> name.hashCode()
                "equals" -> args?.firstOrNull() === proxy
                else -> defaultValue(method.returnType)
            }
        } as WebResourceRequest
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Double::class.javaPrimitiveType -> 0.0
            Float::class.javaPrimitiveType -> 0f
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> 0.toChar()
            else -> null
        }
    }
}
