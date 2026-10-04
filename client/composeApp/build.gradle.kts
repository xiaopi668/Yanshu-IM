import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        rootProject.file("local.properties").exists()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.app) apply false
}

// AGP 必须在 androidTarget() 之前应用：Kotlin 插件在创建 android 目标时会校验
// 「Android Gradle Plugin 是否已就位」，晚一步 apply 就会报 Missing Android Gradle Plugin。
// 之前这段 apply 写在 kotlin{} 之后，导致只要设了 ANDROID_HOME 构建就必然失败 ——
// 也就是 Android 目标其实从来没有编译通过过。
if (hasAndroidSdk) {
    apply(plugin = libs.plugins.android.app.get().pluginId)
}

kotlin {
    if (hasAndroidSdk) androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "imApp.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        val desktopMain by getting {
            dependencies { implementation(compose.desktop.currentOs) }
        }
    }
}

if (hasAndroidSdk) {
    extensions.configure<BaseAppModuleExtension>("android") {
        namespace = "im.app"
        compileSdk = 36
        // 显式钉住 build-tools：AGP 的默认值是 35.0.0，与 compileSdk 36 不匹配，
        // 且默认行为是「去 SDK 目录里装」——在只读 SDK（或 CI 未预装）时会直接失败。
        buildToolsVersion = "36.0.0"
        defaultConfig {
            applicationId = "im.app"
            minSdk = 26
            targetSdk = 36
            versionCode = 1
            versionName = "0.1.0"
        }
        packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

compose.desktop {
    application {
        mainClass = "im.app.MainKt"
    }
}
