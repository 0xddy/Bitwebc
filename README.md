# Bitwebc

Bitwebc 是一个基于 Android WebView 的增强库，使用 Kotlin DSL 进行配置。它提供统一的加载进度、错误页、生命周期与事件流，并支持可选的文件选择与下载能力，便于在 App 内快速集成 H5 页面。

**环境要求**

- Android minSdk 24+
- Kotlin 1.9+ / Java 11+
- AndroidX

---

## 一、引入模块

项目采用多模块结构，可按需引入。

**方式一：源码依赖（推荐用于参与开发或未发布到 Maven 时）**

1. 克隆仓库到本地，在工程的 `settings.gradle.kts` 中引入模块：

```kotlin
include(":bitwebc-core")
include(":bitwebc-filechooser")  // 可选：文件选择
include(":bitwebc-download")     // 可选：下载
```

2. 在需要使用 WebView 的模块（如 app）的 `build.gradle.kts` 中声明依赖：

```kotlin
dependencies {
    implementation(project(":bitwebc-core"))
    // 需要文件选择（如 input[type=file]）时添加：
    implementation(project(":bitwebc-filechooser"))
    // 需要下载时添加：
    implementation(project(":bitwebc-download"))

    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

**方式二：通过 JitPack 引入（仓库已发布到 JitPack 时）**

1. 在工程根目录的 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中加入 JitPack：

```kotlin
repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

2. 在需要使用 WebView 的模块的 `build.gradle.kts` 中按模块引入（将 `YourGitHubUsername`、`Bitwebc`、`版本号` 替换为实际值）：

```kotlin
dependencies {
    implementation("com.github.YourGitHubUsername.Bitwebc:bitwebc-core:版本号")
    implementation("com.github.YourGitHubUsername.Bitwebc:bitwebc-filechooser:版本号")  // 可选
    implementation("com.github.YourGitHubUsername.Bitwebc:bitwebc-download:版本号")     // 可选
    // 以及 androidx.webkit、core-ktx、appcompat、coroutines 等，见方式一
}
```

版本号可使用 GitHub 的 Release 标签（如 `1.0.0`）、提交 hash 或 `main-SNAPSHOT`（分支最新快照）。

**说明**

- `bitwebc-core` 为必选，提供 WebView 容器、DSL 配置、进度条、错误页、事件与 JSBridge 等。
- `bitwebc-filechooser`、`bitwebc-download` 为可选；不引入时，文件选择/下载相关能力不会生效，需自行通过 `registerFileChooserHandler` / `registerDownloadHandler` 注册实现，或忽略对应能力。

---

## 二、最小集成：只加载一个 URL

先完成「只打开一个网页」的集成，再逐步加功能。

**1. 布局中放入 WebView 容器**

在 Activity 的布局里使用 `BitwebcView` 作为 WebView 的容器（全屏示例）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<cn.lmcw.bitwebc.core.ui.BitwebcView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/bitwebc_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

**2. 在 Activity 中配置并加载 URL**

在 `onCreate` 中获取 `BitwebcView`，调用 `setup`，在 DSL 里至少指定要加载的地址即可：

```kotlin
import androidx.appcompat.app.AppCompatActivity
import cn.lmcw.bitwebc.core.ui.BitwebcView

class MainActivity : AppCompatActivity() {

    private var session: BitwebcSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bitwebcView = findViewById<BitwebcView>(R.id.bitwebc_view)

        session = bitwebcView.setup(this) {
            loadUrl("https://example.com")
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
```

`setup { }` 会返回 `BitwebcSession`，用于后续控制 WebView 和释放资源；在 `onDestroy` 中调用 `session?.release()` 即可。

至此，你已经完成最小集成：打开页面、自带加载进度条、返回键会先尝试 WebView 后退。

---

## 三、常用配置

在 `setup { }` 的闭包内可以链式增加以下配置。

**1. 自定义错误页**

加载失败时展示自定义视图，并指定「重试」按钮和错误信息文案的 ViewId：

```kotlin
val customErrorView = LayoutInflater.from(this)
    .inflate(R.layout.view_web_error, bitwebcView, false)

session = bitwebcView.setup(this) {
    loadUrl("https://example.com")
    errorPage(
        errorView = customErrorView,
        retryViewId = R.id.btn_retry,
        errorMessageViewId = R.id.tv_error_message
    )
}
```

错误页布局需包含：一个根视图、重试按钮（如 `btn_retry`）、用于显示错误信息的 TextView（如 `tv_error_message`）。库会在加载失败时显示该视图，点击重试会重新加载当前 URL。

**2. WebView 设置**

通过 `settings { }` 配置 WebSettings，例如：

```kotlin
settings {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    loadWithOverviewMode = true
    useWideViewPort = true
    supportZoom = false
    builtInZoomControls = false
    displayZoomControls = false
    allowFileAccess = false
    userAgentSuffix = "MyApp/1.0"
    cacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK)
    disableScrollBars()
    disableLongPressSelection()
}
```

按需开启/关闭 JavaScript、存储、缩放、缓存等。

**3. 进度条样式**

通过 `indicator { }` 修改顶部进度条颜色和高度：

```kotlin
indicator {
    color("#FF3B30")
    heightDp(3)
}
```

**4. 拦截 URL（如自定义 Scheme）**

通过 `webViewInterceptor` 传入自定义 `WebViewClient`，在 `shouldOverrideUrlLoading` 中处理特定链接（如 `bitwebc://close` 关闭页面）：

```kotlin
webViewInterceptor(object : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val url = request.url?.toString().orEmpty()
        if (url.startsWith("bitwebc://close")) {
            finish()
            return true
        }
        return false
    }
})
```

---

## 四、事件监听

`BitwebcSession` 提供 `events: SharedFlow<BitwebcEvent>`，可在协程中收集页面加载、下载、文件选择、全屏等事件。

在 Activity 中示例：

```kotlin
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import kotlinx.coroutines.launch

lifecycleScope.launch {
    session?.events?.collect { event ->
        when (event) {
            is BitwebcEvent.PageStarted -> { /* 页面开始加载 */ }
            is BitwebcEvent.PageFinished -> { /* 页面加载完成 */ }
            is BitwebcEvent.PageError -> { /* 加载错误 */ }
            is BitwebcEvent.SslError -> { /* SSL 错误 */ }
            is BitwebcEvent.DownloadQueued,
            is BitwebcEvent.DownloadProgress,
            is BitwebcEvent.DownloadSuccess,
            is BitwebcEvent.DownloadFailed -> { /* 下载相关 */ }
            is BitwebcEvent.FileChooserPermissionDenied,
            is BitwebcEvent.FileChooserCancelled,
            is BitwebcEvent.FileChooserFailed -> { /* 文件选择相关 */ }
            is BitwebcEvent.FullscreenChanged -> { /* 全屏变化 */ }
            is BitwebcEvent.SchemeFallback -> { /* 无法处理的 Scheme */ }
            is BitwebcEvent.RenderProcessGone -> { /* 渲染进程退出 */ }
        }
    }
}
```

也可使用 `session?.addEventListener(listener)` 注册 `BitwebcEventListener`，按需二选一。

---

## 五、JSBridge：网页调用 Native

**1. 定义 Bridge 对象**

用 `@JavascriptInterface` 标注需要暴露给前端的方法：

```kotlin
import android.webkit.JavascriptInterface

class MyJsBridge(private val context: Context) {
    @JavascriptInterface
    fun showToast(message: String) {
        Toast.makeText(context, "JS->Native: $message", Toast.LENGTH_SHORT).show()
    }
}
```

**2. 在 setup 中注册**

通过 `jsBridge(name, bridge)` 注册，前端通过 `window[name].methodName()` 调用：

```kotlin
val myBridge = MyJsBridge(this)
session = bitwebcView.setup(this) {
    loadUrl("https://example.com")
    jsBridge("BitwebcApp", myBridge)
}
```

前端示例：`window.BitwebcApp.showToast('hello')`。

**3. Native 调用 JS**

在获得 `WebView` 后，可使用扩展方法安全执行 JS（如页面加载完成后通知前端）：

```kotlin
import cn.lmcw.bitwebc.core.bridge.evaluateJavascriptSafe

session?.webView?.postDelayed({
    session?.webView?.evaluateJavascriptSafe(
        "typeof window.onNativeReady === 'function' && window.onNativeReady('ready');"
    )
}, 800L)
```

---

## 六、可选能力：下载

若已依赖 `bitwebc-download`，可在 `setup` 中注册下载处理器；否则需要自行实现 `DownloadHandler` 并注册。

**1. 使用内置下载模块（带确认弹窗）**

```kotlin
import cn.lmcw.bitwebc.download.BitwebcDownloadFactory
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.ui.DownloadConfirmUi

registerDownloadHandler { activity ->
    BitwebcDownloadFactory.create(
        activity,
        DownloadConfig(
            confirmBeforeDownload = true,
            confirmUi = DownloadConfirmUi { act, request, onDecision ->
                AlertDialog.Builder(act)
                    .setTitle("下载确认")
                    .setMessage("确认下载：${request.url.take(80)}...")
                    .setPositiveButton("下载") { _, _ -> onDecision(true) }
                    .setNegativeButton("取消") { _, _ -> onDecision(false) }
                    .setOnCancelListener { onDecision(false) }
                    .show()
            }
        )
    )
}
```

**2. 不弹窗直接下载**

将 `confirmBeforeDownload = false`，并可不传 `confirmUi`（或传默认实现）。

---

## 七、可选能力：文件选择

H5 中 `input[type=file]` 会触发 Android 的 `onShowFileChooser`。若已依赖 `bitwebc-filechooser`，库会提供默认实现；也可自行实现 `FileChooserHandler` 并通过以下方式注册：

```kotlin
registerFileChooserHandler { activity ->
    MyFileChooserHandler(activity)
}
```

未依赖 filechooser 且未注册时，控制台会提示未解析到 FileChooserHandler，文件选择将不可用。

---

## 八、其它配置摘要

- **SSL 错误策略**：`sslErrorPolicy { url, error -> ... }`，返回 `true` 放行，`false` 走默认拦截。
- **WebMessagePort**：`messagePorts { webView, receivePort, sendPort -> ... }`，用于与前端 `postMessage` / channel 通信。
- **资源拦截与离线包**：在 `settings { interceptors { assetsRoute("https://app.example.com/", "offline_pkg") } }` 中把指定 URL 前缀映射到 assets 目录。
- **池化与回收**：`reuseWebViewFromPool(true)` 开启 WebView 池；`poolRecycleOptions { clearCacheOnRecycle(enable = true, includeDisk = false) }` 控制回收时是否清缓存。
- **生命周期**：`lifeCycle(lifeCycle)` 传入自定义 `WebLifecycle`，在 `onAttach/onResume/onPause/onDestroy` 做扩展。
- **自定义 UI 弹窗**：通过 `nativeUiDelegate(webUIProvider)` 替换 JS 的 alert/confirm/prompt 等默认弹窗。

---

## 九、在 Fragment 中使用

若页面是 Fragment，可把 `BitwebcView` 放在 Fragment 的布局中，用带 `Fragment` 的 `setup` 重载：

```kotlin
bitwebcView.setup(fragment) {
    loadUrl("https://example.com")
}
```

此时会使用 Fragment 的 `viewLifecycleOwner` 管理生命周期。

---

## 十、示例工程

仓库中的 `sample` 模块演示了上述大部分能力：自定义错误页、URL 拦截、事件收集、JSBridge、下载确认、WebMessagePort、进度条与池化等。可直接运行 sample 查看效果，并对照 `MainActivity.kt` 与布局文件做对照学习。

---

## 十一、API 简要说明

| 概念 | 说明 |
|------|------|
| `BitwebcView` | 布局中使用的容器 View，通过 `setup(activity/fragment) { }` 配置并启动。 |
| `BitwebcSession` | `setup` 的返回值，持有 `webView`、`events`，以及 `loadUrl`、`release`、`addEventListener` 等。 |
| `BitwebcBuilder` | DSL 的构建器，在 `setup { }` 闭包内调用的 `loadUrl`、`errorPage`、`settings` 等均在其上配置。 |
| `BitwebcEvent` | 密封类，包含 PageStarted/PageFinished/PageError、SslError、Download*、FileChooser*、FullscreenChanged、SchemeFallback、RenderProcessGone 等。 |

按「引入模块 → 最小集成 → 常用配置 → 事件与 JSBridge → 下载/文件选择 → 其它配置」的顺序逐步叠加，即可在现有工程中完成集成与扩展。

---

## 十二、发布到 JitPack（维护者）

将本仓库发布到 [JitPack](https://jitpack.io) 后，他人可通过「方式二」依赖，无需克隆源码。步骤如下。

**1. 配置 groupId 与版本**

在项目根目录 `gradle.properties` 中已预留：

- `POM_GROUP_ID=com.github.YourGitHubUsername.Bitwebc`：把 `YourGitHubUsername` 改成你的 GitHub 用户名（仓库需在该账号下）。
- `VERSION_NAME=1.0.0`：每次发版前改为与本次 Release 标签一致（如 `1.0.0`）。

**2. 推送代码并打 Release**

- 将上述修改与当前代码推送到 GitHub。
- 在 GitHub 仓库页面创建 Release，标签与 `VERSION_NAME` 一致（如 `v1.0.0` 或 `1.0.0`，以你在 JitPack 使用的为准）。

**3. 触发 JitPack 构建**

- 打开 https://jitpack.io ，在输入框填入仓库地址（如 `https://github.com/YourGitHubUsername/Bitwebc`），点击 Look up。
- 选择刚打的标签或提交，点击 Get it。JitPack 会按仓库根目录的 `jitpack.yml` 使用 JDK 17，并执行三个库模块的 `publishReleasePublicationToMavenLocal`，生成并托管 AAR。

**4. 他人引用方式**

- 在 `settings.gradle.kts` 的 `repositories` 中添加 `maven { url = uri("https://jitpack.io") }`。
- 依赖写法：`implementation("com.github.YourGitHubUsername.Bitwebc:bitwebc-core:版本号")`，同理可引用 `bitwebc-filechooser`、`bitwebc-download`。

**本地自测发布**

在项目根目录执行：

```bash
./gradlew :bitwebc-core:publishReleasePublicationToMavenLocal :bitwebc-filechooser:publishReleasePublicationToMavenLocal :bitwebc-download:publishReleasePublicationToMavenLocal
```

成功后在本地 Maven 仓库（如 `~/.m2/repository`）中可看到对应 groupId/artifactId/version 的 AAR 与 POM，与 JitPack 拉取到的结构一致。
