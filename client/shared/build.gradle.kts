import com.android.build.gradle.LibraryExtension

val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        rootProject.file("local.properties").exists()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
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
        compileSdk = 36
        buildToolsVersion = "36.0.0"
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
