# Bitwebc

[![](https://jitpack.io/v/0xddy/Bitwebc.svg)](https://jitpack.io/#0xddy/Bitwebc)

Bitwebc 是面向 Android WebView 的 Kotlin 库，提供 View 与 Jetpack Compose API、可观察页面状态、安全 Bridge、网页权限、文件选择、下载和错误恢复。

当前环境要求：minSdk 24、compileSdk 36+、JDK 17+。

## 模块

| Artifact | 用途 |
| --- | --- |
| `bitwebc-core` | View API、Session、状态和基础能力 |
| `bitwebc-compose` | `Bitwebc` Composable；已传递依赖 core |
| `bitwebc-filechooser` | 可选的 HTML 文件选择器 |
| `bitwebc-download` | 可选的下载任务与控制器 |

## 安装

在 `settings.gradle.kts` 中加入 JitPack：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Compose 项目只需要：

```kotlin
dependencies {
    implementation("com.github.0xddy.Bitwebc:bitwebc-compose:v0.2.0-alpha01")
}
```

View 项目使用：

```kotlin
dependencies {
    implementation("com.github.0xddy.Bitwebc:bitwebc-core:v0.2.0-alpha01")
}
```

文件选择和下载按需加入：

```kotlin
dependencies {
    implementation("com.github.0xddy.Bitwebc:bitwebc-filechooser:v0.2.0-alpha01")
    implementation("com.github.0xddy.Bitwebc:bitwebc-download:v0.2.0-alpha01")
}
```

Bitwebc Compose 本身不要求 Material 3 或 Foundation；布局和按钮由宿主选择。

## 30 秒 Compose 接入

需要导航、进度或错误状态时，只使用 state 作为 URL 的唯一来源：

```kotlin
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    val browser = rememberBitwebcState("https://example.com")

    Bitwebc(
        state = browser,
        modifier = modifier,
        onEvent = { event -> /* 一次性事件 */ }
    )
}
```

不需要持有状态时可用简写：

```kotlin
Bitwebc(
    url = "https://example.com",
    modifier = modifier
)
```

不要同时传 URL 和 state。`BitwebcState` 提供：

- `pageState.url/title/isLoading/progress/error`
- `pageState.canGoBack/canGoForward/isFullscreen/isReleased`
- `loadUrl/reload/retry/stopLoading/goBack/goForward`
- `withSession { session -> ... }`，用于短暂调用 `evaluateJavaScript` 等 Session 命令

普通重组不会重建 WebView。改变 `sessionKey` 会明确创建新 Session，适合账号隔离或结构性配置切换。离开 Composition 时会保存导航状态并释放 Session；敏感页面可使用 `BitwebcSavePolicy.None`。

## WebView 设置

公开 API 只保留分组配置，不暴露 Android `WebSettings` 的 raw Int 或一整排同级字段：

```kotlin
Bitwebc(state = browser) {
    webSettings {
        scripting {
            enabled = true
            canOpenWindows = false
        }
        storage {
            domEnabled = true
        }
        cache {
            policy = WebResourceCachePolicy.Default
        }
        security {
            fileAccessEnabled = false
            contentAccessEnabled = false
            mixedContent = MixedContentPolicy.Block
        }
        media {
            playbackRequiresUserGesture = true
        }
        viewport {
            overviewMode = true
            wide = true
            zoom {
                enabled = false
                builtInControls = false
                controlsVisible = false
            }
        }
        userAgent {
            append("MyApp/1.0")
            // replaceWith("完整 UA") // 与 append 二选一
        }
    }

    display {
        darkMode = DarkMode.Auto
        scrollBarsEnabled = true
        longPressSelectionEnabled = true
    }
}
```

`cache { policy = ... }` 只控制网页资源加载策略：

| 策略 | 行为 |
| --- | --- |
| `Default` | 采用 WebView 默认缓存语义 |
| `CacheFirst` | 有缓存时优先使用，包括可能过期的响应；没有时访问网络 |
| `NetworkOnly` | 不使用缓存响应 |
| `CacheOnly` | 只读缓存，不访问网络 |

它不会清理 Cookie、DOM Storage 或应用内其他 WebView 的共享缓存。每个 Session 独占自己的 WebView，释放时直接销毁，避免扩展对象或页面状态跨 Session 泄漏。

数据清理使用独立 API，避免把“加载策略”和“删除数据”混为一谈：

```kotlin
browser.withSession { session ->
    session.clearViewState()                 // 仅当前 Session 的历史/临时状态
    session.clearSharedHttpCache()           // 整个应用的 WebView HTTP 缓存
    session.clearSharedSslPreferences()
}

lifecycleScope.launch {
    BitwebcBrowsingData.clearSharedStorage()         // 等待 Cookie 删除完成，再返回
}
```

## 本地资源与请求拦截

资源路由不属于 WebSettings，因此位于独立 scope：

```kotlin
resources {
    assets {
        route("https://app.example.com/", "offline_pkg")
    }
    interceptor(customInterceptor)
}
```

URL 前缀和 assets 路径会在配置时立即校验。

## 文件选择与下载

引入可选 artifact 后，需要对当前 Session 显式启用；库不会通过 Manifest Provider 全局静默安装插件：

```kotlin
integrations {
    fileChooser()
    downloads(
        config = DownloadConfig(confirmBeforeDownload = true),
        onController = { controller ->
            // controller.tasks / pause / resume / cancel / retry / forget
        }
    )
}
```

插件配置只作用于当前 Session，多个 Compose 页面互不覆盖。

Android 13+ 的 `POST_NOTIFICATIONS` 由宿主按自己的权限 UX 请求；未授权时下载仍会执行，只是不显示通知。下载任务不会为了等待 Activity 权限结果而丢失或绑定到一次 Activity 实例。

## JS Bridge、MessagePort 与网页权限

Bridge 和 MessagePort 必须显式声明可信的精确 HTTP(S) Origin：

```kotlin
bridges {
    javascript(
        name = "NativeApp",
        bridge = nativeBridge,
        allowedOrigins = setOf("https://app.example.com")
    )

    messagePorts(setOf("https://app.example.com")) { webView, receivePort, sendPort ->
        // 建立双向通道
    }
}
```

相机、麦克风和定位的网页请求默认全部拒绝：

```kotlin
webPermissions {
    allowFrom("https://app.example.com")
}
```

宿主仍需按实际能力声明系统权限，例如 `CAMERA`、`RECORD_AUDIO`、`ACCESS_COARSE_LOCATION` 和 `ACCESS_FINE_LOCATION`。SSL 错误默认阻断，混合内容默认使用 `MixedContentPolicy.Block`。

## UI 与客户端

```kotlin
ui {
    indicator {
        color("#2F80ED")
        heightDp(2)
    }
    errorPage {
        layout(R.layout.web_error)
        retryView(R.id.retry)
        errorMessageView(R.id.message)
    }
}

clients {
    webViewClient { customWebViewClient() }
    webChromeClient { customWebChromeClient() }
    errorPolicy(customErrorPolicy)
    onSslError { _, _ -> false }
}
```

自定义 client 会作为 Bitwebc 内部 client 链的下游，不会替换状态、错误恢复和安全中间件。

## View API

XML：

```xml
<cn.lmcw.bitwebc.core.ui.BitwebcView
    android:id="@+id/bitwebc_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

Activity：

```kotlin
val session = findViewById<BitwebcView>(R.id.bitwebc_view).setup(this) {
    navigation {
        initial("https://example.com")
    }
    webSettings {
        cache { policy = WebResourceCachePolicy.Default }
    }
    callbacks {
        onEvent { event -> /* 一次性事件 */ }
    }
}

lifecycleScope.launch {
    session.state.collect { page ->
        // page.isLoading / page.canGoBack / page.title / ...
    }
}
```

Fragment 使用其 View 生命周期：

```kotlin
bitwebcView.setup(this) {
    navigation { initial("https://example.com") }
}
```

Session 提供 `loadUrl`、`reload`、`stopLoading`、`goBack`、`goForward`、`evaluateJavaScript`、`saveState` 和 `release`。底层 WebView 在渲染进程恢复时可能更换，因此不作为稳定公开句柄暴露。

## 本地验证

```bash
./gradlew test lintDebug assembleDebug :sample:assembleRelease
./gradlew :bitwebc-compose:pixel2Api35DebugAndroidTest \
  :bitwebc-filechooser:pixel2Api35DebugAndroidTest \
  :sample:pixel2Api35DebugAndroidTest
```

CI 会构建四个 AAR、执行 JVM/设备测试，并使用开启 R8 与资源压缩的 sample release 验证 consumer rules。
