import com.android.build.api.dsl.LibraryExtension

val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        rootProject.file("local.properties").exists()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    // 视频画面是 @Composable expect/actual（CallVideoSurface），这个模块必须能编译 Compose：
    // - kotlin.plugin.compose：Compose 编译器插件（版本跟随 kotlin，见 version catalog）
    // - org.jetbrains.compose：提供 compose.* 依赖坐标（runtime/foundation/ui）
    // 只加编译器与 UI 运行库，不引 material3 —— 这个模块只画一块占位/渲染区域，
    // 文案与配色由 composeApp 的界面层负责。版本全部来自 libs.versions.toml，未改动。
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.lib) apply false
}

// 同 composeApp：AGP 必须先于 androidTarget() 应用（见该文件的说明）
if (hasAndroidSdk) {
    apply(plugin = libs.plugins.android.lib.get().pluginId)
}

kotlin {
    if (hasAndroidSdk) androidTarget()
    jvm("desktop")
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            // 视频画面：CallVideoSurface 的 expect 在 commonMain，actual 分三端
            // （Android 用 AndroidView + SurfaceViewRenderer，Web 只回传坐标，桌面是占位）
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.ws)
            implementation(libs.ktor.client.content)
            implementation(libs.ktor.serialization.json)
        }
        val desktopMain by getting { dependencies {
            implementation(libs.ktor.engine.cio)
            implementation(libs.sqldelight.sqlite)
        } }
        if (hasAndroidSdk) getByName("androidMain") { dependencies {
            implementation(libs.ktor.engine.okhttp)
            implementation(libs.sqldelight.android)
            // 通话媒体面：LiveKit Android SDK。Web 端走随产物发的 livekit-client，
            // 桌面没有官方 Kotlin SDK（维持 supported=false），因此只有这里引。
            implementation(libs.livekit.android)
        } }
        val wasmJsMain by getting { dependencies {
            implementation(libs.ktor.engine.js)
            implementation(libs.kotlinx.browser)
        } }
        val desktopTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

if (hasAndroidSdk) {
    extensions.configure<LibraryExtension>("android") {
        namespace = "im.client.shared"
        compileSdk = 37
        buildToolsVersion = "37.0.0"
        defaultConfig { minSdk = 26 }
    }
}

// DB 冒烟入口：gradle :shared:runDbSmoke
tasks.register<JavaExec>("runDbSmoke") {
    group = "im-debug"
    val comp = kotlin.targets.getByName("desktop").compilations.getByName("test")
    mainClass.set("im.client.DbSmokeKt")
    classpath = (comp.runtimeDependencyFiles ?: files()) + files(comp.output.allOutputs)
}

sqldelight {
    databases {
        create("ImDatabase") {
            packageName.set("im.client.db")
        }
    }
}
