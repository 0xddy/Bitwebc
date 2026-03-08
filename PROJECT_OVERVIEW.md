# Bitwebc 项目分析与梳理

## 一、项目概览

**Bitwebc** 是一个基于 Android 系统 WebView 的**增强型 Web 容器库**，使用 **Kotlin** 编写，采用 **DSL 风格 API**，支持可选扩展模块（文件选择、下载）。  
根项目名：`Bitwebc`，多模块 Gradle 工程，最低 SDK 21，目标 SDK 34。

---

## 二、模块结构

```
Bitwebc/
├── bitwebc-core      # 核心库（必选）
├── bitwebc-download  # 下载扩展（可选，依赖 core）
├── bitwebc-filechooser # 文件选择扩展（可选，依赖 core）
└── sample            # 示例 App（依赖 core + download + filechooser）
```

| 模块 | 类型 | 命名空间 | 说明 |
|------|------|----------|------|
| **bitwebc-core** | Android Library | `cn.lmcw.bitwebc.core` | WebView 容器、DSL、事件、布局、桥接、路由等 |
| **bitwebc-download** | Android Library | `cn.lmcw.bitwebc.download` | 网页内下载：通知、进度、MediaStore/FileProvider |
| **bitwebc-filechooser** | Android Library | `cn.lmcw.bitwebc.filechooser` | `<input type="file">`：相册/文档/相机/录像/录音 |
| **sample** | Application | `cn.lmcw.bitwebc.sample` | 演示 Bitwebc 用法与扩展集成 |

依赖关系：`sample` → `bitwebc-core` + `bitwebc-filechooser` + `bitwebc-download`；扩展模块仅依赖 `bitwebc-core`。

---

## 三、核心库 (bitwebc-core) 架构

### 3.1 入口与 DSL

- **`Bitwebc`**（object）  
  - `Bitwebc.with(activity, block)` / `Bitwebc.with(fragment, container, block)`：在 Activity 或 Fragment 中创建并启动一个 Web 会话，返回 `BitwebcSession`。  
  - `Bitwebc.prewarm(activity, count)`：预创建 WebView 放入池中。  
  - `Bitwebc.events(activity)`：按 Activity 维度的 `SharedFlow<BitwebcEvent>`。  
  - `Bitwebc.eventReporter(activity)`：获取 `(BitwebcEvent) -> Unit`，用于把事件上报到该 Activity 的 Hub（如传给 download 模块）。

- **`BitwebcBuilder`**  
  链式配置：加载 URL、挂载容器、错误页/布局、进度条、生命周期、WebViewClient/WebChromeClient 拦截、文件选择/下载、JSBridge、事件监听、是否从池中复用 WebView、WebSettings 等，最后 `launch()` 得到 `BitwebcSession`。

- **`BitwebcSession`**  
  持有当前 `WebView`、根视图、生命周期观察者与返回键回调；提供 `loadUrl`、事件监听增删、`release()`。  
  `release()` 会移除返回键回调并触发 lifecycle 的释放逻辑（从视图树移除、回收或销毁 WebView）。

### 3.2 配置与 WebView 设置

- **`BitwebcSettings`**  
  封装常用 `WebSettings`：JS、DOM 存储、数据库、缩放、概览模式、User-Agent 等，通过 `settings { }` 块应用到当前 WebView。

### 3.3 客户端与中间件

- **`MiddlewareWebClientBase`** / **`MiddlewareWebChromeBase`**  
  将未处理逻辑委托给 `next`，便于链式组合（core 默认实现 + 文件选择等扩展）。

- **`DefaultWebViewClient`**  
  - URL：`BitwebcSchemeRouter` 处理非 http(s)/about 的 scheme（如 intent、tel 等），其余可上报 `SchemeFallback`。  
  - 页面开始/结束：切布局、进度条、上报 `PageStarted`/`PageFinished`。  
  - 主帧错误、SSL 错误、渲染进程退出：显示错误页、进度条重置、上报对应事件。

- **`DefaultWebChromeClient`**  
  进度条、JS alert/confirm 弹窗、全屏视频（CustomView）、全屏时隐藏/恢复系统栏与返回键处理，并上报 `FullscreenChanged`。  
  文件选择通过 `next` 链交给 [IFileChooserHandler] 实现（默认由 bitwebc-filechooser 模块注册）。

### 3.4 路由

- **`BitwebcSchemeRouter`**  
  识别 http/https/about 放行；其他 scheme 用 `Intent.ACTION_VIEW` 打开；`intent:` 解析失败时尝试 `browser_fallback_url` 在 WebView 内打开。

### 3.5 事件

- **`BitwebcEvent`**（sealed）  
  页面：PageStarted / PageFinished / PageError / SslError / SchemeFallback / RenderProcessGone；全屏 FullscreenChanged；下载：DownloadQueued / DownloadProgress / DownloadSuccess / DownloadFailed / DownloadPermissionDenied；文件选择：FileChooserPermissionDenied / FileChooserCancelled / FileChooserFailed。

- **`BitwebcEventHub`**  
  每个 Activity 一个 Hub：`SharedFlow` 广播 + `BitwebcEventListener` 列表，`emit()` 同时写 Flow 与回调。

- **`BitwebcEventCenter`**  
  按 Activity 缓存 Hub，提供 `hub(activity)` 和 `reporter(activity)`。

### 3.6 布局与进度条

- **`IWebLayout`**  
  `createRoot`、`attach`（WebView + 进度条）、`showWebContent`、`showError(message, onRetry)`、`root()`。

- **`DefaultWebLayout`**  
  FrameLayout 根布局，内置错误区域（文案 + 重试按钮）与 WebView、进度条层级。

- **`CustomErrorWebLayout`**  
  使用宿主提供的错误 View，可指定重试控件 id、错误文案控件 id。

- **`IWebIndicator`**  
  `createView`、`onPageStarted`、`onProgressChanged`、`onPageFinished`、`reset`。

- **`WebIndicator`**  
  顶部进度条：宽度随进度动画、完成后淡出。

### 3.7 生命周期与回收

- **`ILifeCycle`**  
  回调：onAttach / onResume / onPause / onDestroy（均与 WebView 绑定）。

- **`BitwebcLifecycleObserver`**  
  监听 Activity 生命周期：onResume/onPause 时调用 WebView 的 resume/pause 以及 `BitwebcGlobalManager` 的计数；onDestroy 时执行 `release()`：调用 `ILifeCycle.onDestroy`、从视图树移除、根据是否复用池决定 `BitwebcWebViewPool.recycle` 或 `destroySafelyWithAboutBlank()`。

- **`BitwebcGlobalManager`**  
  全局“已 resume 的 WebView”计数，仅在第一个 resume 时 `resumeTimers()`、最后一个 pause 时 `pauseTimers()`，避免误伤其他 WebView。

- **`WebViewCleanup.destroySafelyWithAboutBlank()`**  
  先清历史、从父布局移除、加载 about:blank、停止加载、反射清空 Client、再 destroy，降低泄漏风险。

### 3.8 WebView 池

- **`BitwebcWebViewPool`**  
  预创建或复用 WebView，上限 3；acquire 时替换为当前 Context；recycle 时清空、about:blank、清 Client、从父布局移除、Context 换回 Application，池满则 destroy。

### 3.9 JSBridge

- **`BitwebcJsBridge`**  
  `injectSafely(webView, name, bridge)`：API 17+、名称正则校验、移除系统危险接口后 `addJavascriptInterface`。  
  扩展：`WebView.evaluateJavascriptSafe(script, onResult)` 兼容旧系统。

---

## 四、扩展模块

### 4.1 bitwebc-download

- **`BitwebcDownloadFactory.createDefault(activity, eventReporter)`**  
  返回 [IDownloadHandler]（core 接口），供 `WebView.setDownloadListener` 使用。

- **`BitwebcDownloadHandler`**（实现 `IDownloadHandler`）  
  - 使用 OkHttp 下载，支持并发数、通知渠道、Android 13+ 通知权限。  
  - 大文件可配置前台策略（如阈值、回调）。  
  - 通过 `eventReporter` 上报 DownloadQueued / DownloadProgress / DownloadSuccess / DownloadFailed / DownloadPermissionDenied。  
  - 下载结果写入 MediaStore（Q+）或通过 FileProvider（旧版）；通知点击可打开文件。

Core 的 `BitwebcBuilder` 在 `autoDownload == true` 时通过 **BitwebcPlugins** 已注册的默认工厂创建（模块通过 ContentProvider 自动注册），未依赖 download 模块则不会注入。

### 4.2 bitwebc-filechooser

- **`IFileChooserHandler`**（core 接口）  
  `createWebChromeClient(next)` 返回用于处理 `<input type="file">` 的 WebChromeClient；外部可传入自定义实现。

- **`DefaultFileChooserHandler`**（继承 `MiddlewareWebChromeBase`）  
  默认实现：根据 accept/capture 类型选择相册（PickVisualMedia）、多选文档（OpenMultipleDocuments）、拍照、录像、录音等，权限与 ActivityResult 用协程挂起等待；结果通过 `ValueCallback<Array<Uri>>` 回传；通过 `eventReporter` 上报 FileChooser 相关事件。

- **`BitwebcFileChooserFactory.createDefault(activity, eventReporter)`**  
  返回实现 `IFileChooserHandler` 的工厂，其 `createWebChromeClient(next)` 内部创建 `DefaultFileChooserHandler`。

Core 在 `autoDefaultFileChooser == true` 时通过 **BitwebcPlugins** 已注册的默认工厂创建并链入 next，未依赖 filechooser 模块则不会注入。

---

## 五、Sample 使用方式

- **MainActivity**  
  - 使用 `Bitwebc.with(this) { ... }.attachTo(host).loadUrl("https://asmrby.com")`。  
  - `settings { }` 配置 JS、存储、缩放、User-Agent 等。  
  - `indicator { }` 自定义进度条颜色与高度。  
  - `autoDownload(true)` 使用模块注册的默认下载；或 `downloadHandler(myIDownloadHandler)` 传入自定义实现。  
  - `jsBridge("BitwebcApp", sampleJsBridge)` 注入 JSBridge。  
  - 通过 `Bitwebc.events(this).collect { }` 消费全局事件（如 DownloadFailed 打日志）。  
  - 页面加载后通过 `session?.webView?.evaluateJavascriptSafe("window.onNativeReady && ...")` 做 Native→JS 调用。  
  - onDestroy 中 `session?.release()`。

- **SampleJsBridge**  
  `@JavascriptInterface showToast(message)`，供前端调用。

---

## 六、数据流与扩展集成小结

- **事件流**：DefaultWebViewClient / DefaultWebChromeClient / 扩展（Download、FileChooser）调用 `eventReporter` → BitwebcEventHub.emit → SharedFlow + BitwebcEventListener。  
- **下载**：WebView 触发的下载由 `DownloadListener` 处理；若使用 `BitwebcDownloadHandler`，需将 `Bitwebc.eventReporter(activity)` 传入以便统计与通知。  
- **文件选择**：不依赖 filechooser 时，core 不会设置 FileChooser；依赖后通过 BitwebcPlugins 自动挂到 ChromeClient 链上，无需在 sample 里显式创建；自定义实现可实现 [IFileChooserHandler] 后通过 `fileChooserHandler(...)` 传入。

---

## 七、技术栈与依赖（节选）

- **语言 / 构建**：Kotlin 2.0.21，AGP 9.1.0，Gradle Kotlin DSL，版本集中在 `gradle/libs.versions.toml`。  
- **Core**：AndroidX Core、Activity、AppCompat、Material、Lifecycle、Coroutines。  
- **Download**：OkHttp 4.12、Coroutines、Activity Result、Notification、MediaStore/FileProvider。  
- **FileChooser**：Activity Result（PickVisualMedia、OpenMultipleDocuments、TakePicture 等）、FileProvider。

---

## 八、目录与文件索引（按职责）

| 职责 | 路径 |
|------|------|
| 入口 DSL | `core/dsl/Bitwebc.kt`, `BitwebcBuilder.kt`, `BitwebcSession.kt` |
| 配置 | `core/settings/BitwebcSettings.kt` |
| Web 客户端 | `core/client/DefaultWebViewClient.kt`, `DefaultWebChromeClient.kt`, `Middleware*.kt` |
| 路由 | `core/route/BitwebcSchemeRouter.kt` |
| 事件 | `core/event/BitwebcEvent.kt`, `BitwebcEventHub.kt`, `BitwebcEventCenter.kt` |
| 布局/进度条 | `core/ui/DefaultWebLayout.kt`, `CustomErrorWebLayout.kt`, `WebIndicator.kt` |
| 生命周期/池/清理 | `core/lifecycle/*`, `core/pool/BitwebcWebViewPool.kt` |
| 桥接 | `core/bridge/BitwebcJsBridge.kt` |
| API 接口 | `core/api/ILifeCycle.kt`, `IWebLayout.kt`, `IWebIndicator.kt`, `IDownloadHandler.kt`, `IFileChooserHandler.kt` |
| 下载扩展 | `bitwebc-download/BitwebcDownloadFactory.kt`, `BitwebcDownloadHandler.kt`（实现 IDownloadHandler） |
| 文件选择扩展 | `bitwebc-filechooser/DefaultFileChooserHandler.kt`, `BitwebcFileChooserFactory.kt`, `accept/FileChooserAcceptResolver.kt` |

以上为 Bitwebc 项目的整体分析与梳理，便于后续维护、扩展或接入新业务。
