pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // io.livekit:livekit-android 的音频路由依赖（com.github.davidliu:audioswitch）
        // 只发布在 JitPack 上。没有这个仓库，Android 目标会在依赖解析阶段失败：
        //   Could not find com.github.davidliu:audioswitch:039a35a…
        // 只影响依赖解析，不放任何构建插件到类路径。
        maven("https://jitpack.io") {
            // JitPack 会对每个 artifact 回 "Found"/"Not found" 一类的重定向，
            // 限定分组可以避免 Google/MavenCentral 的坐标被打到 JitPack 上试探。
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "im-client"
include(":shared", ":composeApp")
