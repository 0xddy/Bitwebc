plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.lmcw.bitwebc.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.fragment.ktx)
    api(libs.androidx.webkit)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = rootProject.findProperty("POM_GROUP_ID")?.toString() ?: "com.github.Bitwebc"
                artifactId = "bitwebc-core"
                version = rootProject.findProperty("VERSION_NAME")?.toString() ?: "1.0.0"
            }
        }
    }
}
