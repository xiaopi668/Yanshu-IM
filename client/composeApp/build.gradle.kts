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
    apply(plugin = libs.plugins.android.app.get().pluginId)
    extensions.configure<BaseAppModuleExtension>("android") {
        namespace = "im.app"
        compileSdk = 36
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
