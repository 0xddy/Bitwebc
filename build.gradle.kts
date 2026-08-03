// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// 在库模块上最后应用 maven-publish，确保能拿到 Android 的 release 组件
subprojects {
    if (
        name == "bitwebc-core" ||
        name == "bitwebc-filechooser" ||
        name == "bitwebc-download" ||
        name == "bitwebc-compose"
    ) {
        apply(plugin = "maven-publish")
    }
}
