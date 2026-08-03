package cn.lmcw.bitwebc.core.bridge

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val BRIDGE_NAME_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
private const val MAX_BRIDGE_MESSAGE_LENGTH = 1_048_576

/**
 * Origin-scoped JS bridge backed by AndroidX WebMessage APIs.
 *
 * Only public methods marked with [JavascriptInterface] are exposed. Calls return a JavaScript
 * `Promise`, and messages from subframes are rejected even when the frame is same-origin.
 */
object BitwebcJsBridge {

    private data class Registration(
        val publicName: String,
        val transportName: String,
        val scriptHandler: ScriptHandler,
        val ownedExecutor: ExecutorService?
    )

    private data class ExposedMethod(val method: Method) {
        val name: String = method.name
    }

    private val registrations = WeakHashMap<WebView, MutableMap<String, Registration>>()

    /**
     * Injects [bridge] only into the explicitly listed HTTP(S) origins.
     *
     * The method fails closed when the installed WebView does not support document-start scripts
     * and WebMessage listeners, or when any origin is malformed.
     */
    @SuppressLint("RequiresFeature")
    @JvmStatic
    fun injectSafely(
        webView: WebView,
        bridgeName: String,
        bridge: Any,
        allowedOrigins: Set<String>
    ): Boolean = injectSafely(webView, bridgeName, bridge, allowedOrigins, null)

    /**
     * Executes native bridge calls on [executor]. When it is null, each registration gets a
     * dedicated serial background executor so bridge calls never run on the WebView UI thread.
     */
    @SuppressLint("RequiresFeature")
    @JvmStatic
    fun injectSafely(
        webView: WebView,
        bridgeName: String,
        bridge: Any,
        allowedOrigins: Set<String>,
        executor: Executor?
    ): Boolean {
        if (!BRIDGE_NAME_REGEX.matches(bridgeName)) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return false
        }

        val normalizedOrigins = allowedOrigins.mapNotNull(WebOrigin::normalizeRule).toSet()
        if (normalizedOrigins.isEmpty() || normalizedOrigins.size != allowedOrigins.size) return false

        val exposedMethods = bridge.javaClass.methods
            .asSequence()
            .filter { Modifier.isPublic(it.modifiers) }
            .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
            .map(::ExposedMethod)
            .toList()
        if (exposedMethods.isEmpty()) return false

        removeRegistration(webView, bridgeName)
        runCatching { webView.removeJavascriptInterface(bridgeName) }
        val transportName = "__bitwebc_${UUID.randomUUID().toString().replace("-", "")}"
        val methodsByName = exposedMethods.groupBy(ExposedMethod::name)
        val ownedExecutor = if (executor == null) newBridgeExecutor() else null
        val invocationExecutor = executor ?: ownedExecutor!!
        val listener = WebViewCompat.WebMessageListener { sourceView, message, sourceOrigin, isMainFrame, reply ->
            val stillRegistered = synchronized(registrations) {
                registrations[sourceView]?.get(bridgeName)?.transportName == transportName
            }
            if (!stillRegistered) return@WebMessageListener
            if (!isMainFrame || WebOrigin.fromUrl(sourceOrigin.toString()) !in normalizedOrigins) {
                replyError(reply, requestId(message), "Bridge calls are not allowed from this frame")
                return@WebMessageListener
            }
            handleMessage(
                sourceView,
                message,
                reply,
                bridge,
                methodsByName,
                invocationExecutor,
                bridgeName,
                transportName
            )
        }

        return runCatching {
            WebViewCompat.addWebMessageListener(webView, transportName, normalizedOrigins, listener)
            val scriptHandler = try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    createBootstrapScript(bridgeName, transportName, exposedMethods.map { it.name }.distinct()),
                    normalizedOrigins
                )
            } catch (error: Throwable) {
                WebViewCompat.removeWebMessageListener(webView, transportName)
                throw error
            }
            synchronized(registrations) {
                registrations.getOrPut(webView) { mutableMapOf() }[bridgeName] = Registration(
                    publicName = bridgeName,
                    transportName = transportName,
                    scriptHandler = scriptHandler,
                    ownedExecutor = ownedExecutor
                )
            }
            true
        }.getOrElse {
            ownedExecutor?.shutdownNow()
            false
        }
    }

    @SuppressLint("RequiresFeature")
    @JvmStatic
    fun removeSafely(webView: WebView) {
        val current = synchronized(registrations) { registrations.remove(webView)?.values.orEmpty() }
        current.forEach { registration ->
            runCatching { registration.scriptHandler.remove() }
            registration.ownedExecutor?.shutdownNow()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(webView, registration.transportName) }
            }
            runCatching { webView.removeJavascriptInterface(registration.publicName) }
        }
    }

    /** Drops host-side state without invoking an unusable renderer-backed WebView. */
    @JvmStatic
    @JvmSynthetic
    internal fun discardAfterRendererGone(webView: WebView) {
        val current = synchronized(registrations) { registrations.remove(webView)?.values.orEmpty() }
        current.forEach { registration -> registration.ownedExecutor?.shutdownNow() }
    }

    @SuppressLint("RequiresFeature")
    private fun removeRegistration(webView: WebView, bridgeName: String) {
        val old = synchronized(registrations) { registrations[webView]?.remove(bridgeName) } ?: return
        runCatching { old.scriptHandler.remove() }
        old.ownedExecutor?.shutdownNow()
        runCatching { WebViewCompat.removeWebMessageListener(webView, old.transportName) }
    }

    @SuppressLint("RequiresFeature")
    private fun handleMessage(
        sourceView: WebView,
        message: WebMessageCompat,
        reply: JavaScriptReplyProxy,
        bridge: Any,
        methodsByName: Map<String, List<ExposedMethod>>,
        executor: Executor,
        bridgeName: String,
        transportName: String
    ) {
        val raw = message.data
        if (raw == null || raw.length > MAX_BRIDGE_MESSAGE_LENGTH) {
            replyError(reply, null, "Invalid bridge message")
            return
        }
        val request = runCatching { JSONObject(raw) }.getOrElse {
            replyError(reply, null, "Invalid bridge message")
            return
        }
        val id = request.optString("id").takeIf { it.isNotBlank() }
        val methodName = request.optString("method")
        val args = request.optJSONArray("args") ?: JSONArray()
        val candidates = methodsByName[methodName].orEmpty()
        val invocation = candidates.firstNotNullOfOrNull { exposed ->
            convertArguments(exposed.method.parameterTypes, args)?.let { exposed.method to it }
        }
        if (invocation == null) {
            replyError(reply, id, "No matching bridge method")
            return
        }

        try {
            executor.execute {
                val response = runCatching {
                    val result = invocation.first.invoke(bridge, *invocation.second)
                    JSONObject()
                        .put("id", id)
                        .put("ok", true)
                        .put("result", JSONObject.wrap(result))
                        .toString()
                }.getOrElse { error ->
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    errorMessage(id, "Native bridge call failed")
                }
                sourceView.post {
                    val stillRegistered = synchronized(registrations) {
                        registrations[sourceView]?.get(bridgeName)?.transportName == transportName
                    }
                    if (stillRegistered) runCatching { reply.postMessage(response) }
                }
            }
        } catch (_: RuntimeException) {
            replyError(reply, id, "Native bridge call failed")
        }
    }

    private fun requestId(message: WebMessageCompat): String? = runCatching {
        message.data?.takeIf { it.length <= MAX_BRIDGE_MESSAGE_LENGTH }
            ?.let(::JSONObject)
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    @SuppressLint("RequiresFeature")
    private fun replyError(reply: JavaScriptReplyProxy, id: String?, reason: String) {
        runCatching { reply.postMessage(errorMessage(id, reason)) }
    }

    private fun errorMessage(id: String?, reason: String): String =
        JSONObject()
            .put("id", id)
            .put("ok", false)
            .put("error", reason)
            .toString()

    private fun newBridgeExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BitwebcJsBridge-${bridgeThreadSequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private fun convertArguments(types: Array<Class<*>>, args: JSONArray): Array<Any?>? {
        if (types.size != args.length()) return null
        val converted = arrayOfNulls<Any?>(types.size)
        for (index in types.indices) {
            val value = args.opt(index).let { if (it === JSONObject.NULL) null else it }
            converted[index] = convertArgument(types[index], value) ?: if (
                value == null && !types[index].isPrimitive
            ) {
                null
            } else {
                return null
            }
        }
        return converted
    }

    private fun convertArgument(type: Class<*>, value: Any?): Any? {
        if (value == null) return null
        if (type.isInstance(value)) return value
        return when (type) {
            String::class.java -> value as? String
            Boolean::class.java, Boolean::class.javaObjectType -> value as? Boolean
            Byte::class.java, Byte::class.javaObjectType -> (value as? Number)?.toByte()
            Short::class.java, Short::class.javaObjectType -> (value as? Number)?.toShort()
            Int::class.java, Int::class.javaObjectType -> (value as? Number)?.toInt()
            Long::class.java, Long::class.javaObjectType -> (value as? Number)?.toLong()
            Float::class.java, Float::class.javaObjectType -> (value as? Number)?.toFloat()
            Double::class.java, Double::class.javaObjectType -> (value as? Number)?.toDouble()
            JSONObject::class.java -> value as? JSONObject
            JSONArray::class.java -> value as? JSONArray
            else -> null
        }
    }

    private fun createBootstrapScript(
        bridgeName: String,
        transportName: String,
        methodNames: List<String>
    ): String {
        val publicName = JSONObject.quote(bridgeName)
        val transport = JSONObject.quote(transportName)
        val methods = JSONArray(methodNames).toString()
        return """
            (() => {
              const transport = window[$transport];
              if (!transport || typeof transport.postMessage !== 'function') return;
              const pending = new Map();
              let sequence = 0;
              transport.onmessage = event => {
                try {
                  const response = JSON.parse(event.data);
                  const callbacks = pending.get(response.id);
                  if (!callbacks) return;
                  pending.delete(response.id);
                  response.ok ? callbacks.resolve(response.result) : callbacks.reject(new Error(response.error));
                } catch (_) { /* Ignore malformed native responses. */ }
              };
              const api = {};
              for (const method of $methods) {
                api[method] = (...args) => new Promise((resolve, reject) => {
                  const id = `${'$'}{Date.now().toString(36)}_${'$'}{(++sequence).toString(36)}`;
                  pending.set(id, { resolve, reject });
                  try {
                    transport.postMessage(JSON.stringify({ id, method, args }));
                  } catch (error) {
                    pending.delete(id);
                    reject(error);
                  }
                });
              }
              Object.defineProperty(window, $publicName, {
                value: Object.freeze(api), configurable: false, enumerable: true, writable: false
              });
            })();
        """.trimIndent()
    }

    private val bridgeThreadSequence = AtomicInteger()
}

/** Executes JavaScript on the WebView thread. */
fun WebView.evaluateJavascriptSafe(
    script: String,
    onResult: ((String?) -> Unit)? = null
) {
    val accepted = post {
        runCatching {
            evaluateJavascript(script) { value ->
                runCatching { onResult?.invoke(value) }
            }
        }.onFailure {
            runCatching { onResult?.invoke(null) }
        }
    }
    if (!accepted) runCatching { onResult?.invoke(null) }
}
