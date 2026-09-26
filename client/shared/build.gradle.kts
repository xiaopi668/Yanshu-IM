import com.android.build.gradle.LibraryExtension

val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        rootProject.file("local.properties").exists()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.android.lib) apply false
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
    apply(plugin = libs.plugins.android.lib.get().pluginId)
    extensions.configure<LibraryExtension>("android") {
        namespace = "im.client.shared"
        compileSdk = 36
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

// 连接层调试入口：gradle -p . :shared:runDebugConn
afterEvaluate {
    tasks.register<JavaExec>("runDebugConn") {
        group = "im-debug"
        val comp = kotlin.targets.getByName("desktop").compilations.getByName("test")
        mainClass.set("im.client.DebugConnTestKt")
        classpath = (comp.runtimeDependencyFiles ?: files()) + files(comp.output.allOutputs)
        standardInput = System.`in`
    }
}

sqldelight {
    databases {
        create("ImDatabase") {
            packageName.set("im.client.db")
        }
    }
}
