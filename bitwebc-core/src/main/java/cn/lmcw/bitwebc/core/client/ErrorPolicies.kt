package cn.lmcw.bitwebc.core.client

import android.webkit.WebViewClient
import cn.lmcw.bitwebc.core.api.ErrorContext
import cn.lmcw.bitwebc.core.api.ErrorPolicy

object ErrorPolicies {

    /**
     * 默认策略（推荐）：参考 AgentWeb 的成熟实践。
     * - HTTP 错误：不触发错误页（服务端返回了有效响应，页面自身可能包含内容）
     * - 网络错误：三重过滤（主框架 + 非 ERROR_UNKNOWN + URL 匹配或 DNS 错误）
     */
    val standard: ErrorPolicy = ErrorPolicy { ctx ->
        when (ctx) {
            is ErrorContext.Http -> false
            is ErrorContext.Network -> {
                val req = ctx.request
                val errorCode = ctx.error.errorCode
                val failingUrl = req.url?.toString()
                if (!req.isForMainFrame) return@ErrorPolicy false
                if (errorCode == WebViewClient.ERROR_UNKNOWN) return@ErrorPolicy false
                if (errorCode != WebViewClient.ERROR_HOST_LOOKUP
                    && failingUrl != ctx.view.url
                    && failingUrl != ctx.view.originalUrl
                ) return@ErrorPolicy false
                true
            }
        }
    }

    /**
     * 严格策略：在 [standard] 基础上，主框架导航 URL 本身返回 HTTP >= 400 时也显示错误页。
     */
    val strict: ErrorPolicy = ErrorPolicy { ctx ->
        when (ctx) {
            is ErrorContext.Http -> {
                val req = ctx.request
                req.isForMainFrame
                    && ctx.response.statusCode >= 400
                    && req.url?.toString() == ctx.view.url
            }
            is ErrorContext.Network -> standard.shouldShowError(ctx)
        }
    }

    /**
     * 忽略特定 HTTP 状态码，其余行为同 [strict]。
     *
     * ```
     * errorPolicy(ErrorPolicies.strictIgnoring(403, 429))
     * ```
     */
    fun strictIgnoring(vararg codes: Int): ErrorPolicy = ErrorPolicy { ctx ->
        when (ctx) {
            is ErrorContext.Http -> {
                ctx.response.statusCode !in codes && strict.shouldShowError(ctx)
            }
            is ErrorContext.Network -> standard.shouldShowError(ctx)
        }
    }

    /**
     * 仅 5xx 服务端错误显示错误页，4xx 全部忽略。
     */
    val serverErrorOnly: ErrorPolicy = ErrorPolicy { ctx ->
        when (ctx) {
            is ErrorContext.Http -> {
                ctx.request.isForMainFrame
                    && ctx.response.statusCode >= 500
                    && ctx.request.url?.toString() == ctx.view.url
            }
            is ErrorContext.Network -> standard.shouldShowError(ctx)
        }
    }
}
