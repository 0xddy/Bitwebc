plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.lmcw.bitwebc.filechooser"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":bitwebc-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
