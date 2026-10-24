import com.android.build.api.dsl.ApplicationExtension
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

// ---------- 构建期配置：默认服务器地址 / 是否隐藏地址输入 ----------
// 用途：私有化部署时把地址固化进客户端，登录页不再暴露「服务器地址」入口。
// 三个平台通用（含 Web）：
//   ./gradlew :composeApp:assembleDebug \
//     -Pim.api=https://im.example.com -Pim.ws=wss://im.example.com/ws -Pim.hideServer=true
// 也可写进 gradle.properties（im.api / im.ws / im.hideServer），
// 或用环境变量 IM_APP_API / IM_APP_WS / IM_APP_HIDE_SERVER（便于 CI 注入）。
val imApiProp: String? = (findProperty("im.api") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("IM_APP_API")?.takeIf { it.isNotBlank() }
val imWsProp: String? = (findProperty("im.ws") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("IM_APP_WS")?.takeIf { it.isNotBlank() }
val imHideServer: Boolean = (findProperty("im.hideServer") as String?)?.toBooleanStrictOrNull()
    ?: System.getenv("IM_APP_HIDE_SERVER")?.toBooleanStrictOrNull()
    ?: false
val imApi = imApiProp ?: "http://127.0.0.1:10002"
val imWs = imWsProp ?: "ws://127.0.0.1:10001/ws"

val imConfigDir = layout.buildDirectory.dir("generated/imconfig")
val genImConfig = tasks.register("generateImConfig") {
    // 声明成输入：改了属性必须重新生成，否则会命中缓存、出现「改了没生效」
    inputs.property("api", imApi)
    inputs.property("ws", imWs)
    inputs.property("hide", imHideServer)
    inputs.property("pinned", imApiProp != null)
    outputs.dir(imConfigDir)
    doLast {
        val out = imConfigDir.get().asFile.resolve("im/app/ImBuildConfig.kt")
        out.parentFile.mkdirs()
        val q = "\""
        out.writeText(
            """
            |package im.app
            |
            |/** 由 Gradle 任务 generateImConfig 生成，请勿手工修改。 */
            |internal object ImBuildConfig {
            |    const val API_BASE: String = $q$imApi$q
            |    const val WS_BASE: String = $q$imWs$q
            |    const val HIDE_SERVER_CONFIG: Boolean = $imHideServer
            |    /** 构建时是否显式钉住了 API 地址（钉住后 Web 端不再按页面域名推导） */
            |    const val API_PINNED: Boolean = ${imApiProp != null}
            |}
            |""".trimMargin()
        )
    }
}

// 业务代码引用 ImBuildConfig，编译前必须先跑生成任务
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(genImConfig)
}

// ---------- 版本号 ----------
// 优先级：-PverName/-PverCode > 环境变量 IM_APP_VERSION/IM_APP_VERSION_CODE
//        > 最近的 git tag（vX.Y.Z）> 默认 0.1.0 / 1
//
// 为什么不能写死：Android 升级要求 versionCode 单调递增，固定为 1 会导致
// 新版本 APK 覆盖安装被系统拒绝。用最近 tag 推导，发版自然递增。
fun gitNearestTag(): String? = try {
    val proc = ProcessBuilder("git", "describe", "--tags", "--abbrev=0", "--match", "v*")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    out.takeIf { it.matches(Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+$")) }?.removePrefix("v")
} catch (_: Throwable) {
    null
}

fun semverToCode(v: String): Int = runCatching {
    val parts = v.split(".").map { it.toInt() }
    parts[0] * 10000 + parts[1] * 100 + parts[2]
}.getOrDefault(1)

val explicitVerName = (findProperty("verName") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("IM_APP_VERSION")?.takeIf { it.isNotBlank() }
val tagVerName = gitNearestTag()
val appVersionName = explicitVerName ?: tagVerName ?: "0.1.0"
val appVersionCode = (findProperty("verCode") as String?)?.toIntOrNull()
    ?: System.getenv("IM_APP_VERSION_CODE")?.toIntOrNull()
    ?: if (explicitVerName != null || tagVerName != null) semverToCode(appVersionName) else 1

/** 用 ./gradlew :composeApp:printVersion 查看解析结果（发版前自查用） */
tasks.register("printVersion") {
    doLast {
        println("versionName=" + appVersionName + " versionCode=" + appVersionCode)
        println("来源: " + (if (explicitVerName != null) "显式参数/环境变量" else if (tagVerName != null) "git tag v" + tagVerName else "默认值"))
    }
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
            // 生成 Res 类以访问 composeResources 下的图片（品牌 logo）
            implementation(compose.components.resources)
        }
        commonMain {
            // 构建期生成的 ImBuildConfig（默认地址 / 是否隐藏地址输入）
            kotlin.srcDir(imConfigDir)
        }
        val desktopMain by getting {
            dependencies { implementation(compose.desktop.currentOs) }
        }
        val desktopTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        // Android 侧需要 activity-compose 提供 ComponentActivity / setContent /
        // registerForActivityResult —— 这些不在 compose 的通用依赖里
        if (hasAndroidSdk) getByName("androidMain") { dependencies {
            implementation(libs.androidx.activity.compose)
        } }
    }
}

if (hasAndroidSdk) {
    extensions.configure<ApplicationExtension>("android") {
        namespace = "im.app"
        // compileSdk 37：Compose 1.12 的 Android 产物明确要求（AGP 也需 ≥ 9.1）
        compileSdk = 37
        // 显式钉住 build-tools：默认值是「某个 AGP 内置版本」，且会尝试往 SDK 目录里装，
        // 在只读 SDK（或 CI 未预装）时直接失败。
        buildToolsVersion = "37.0.0"
        defaultConfig {
            applicationId = "im.app"
            minSdk = 26
            targetSdk = 37
            versionCode = appVersionCode
            versionName = appVersionName
        }
        packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// 资源类固定包名，避免依赖默认推导（默认包名随 group/模块变化，容易一改就编译不过）
compose.resources {
    publicResClass = true
    packageOfResClass = "im.app.resources"
}

compose.desktop {
    application {
        mainClass = "im.app.MainKt"
    }
}
