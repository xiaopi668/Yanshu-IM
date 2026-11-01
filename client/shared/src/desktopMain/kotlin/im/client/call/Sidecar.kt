package im.client.call

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 桌面端通话侧车（yanshu-call-agent）的进程封装。
 *
 * 为什么走独立进程：桌面（JVM）没有官方 Kotlin LiveKit SDK，而 Rust SDK 是最完整的
 * 选择。把媒体栈放进独立进程有几个实际好处：
 *   - 崩溃隔离：媒体栈挂了不会带走整个 IM 客户端；
 *   - 打包灵活：按平台分发二进制，主端不引入 JNI 依赖；
 *   - 可独立升级：换 SDK 版本只换一个可执行文件。
 *
 * 协议是 stdin/stdout 的 JSON Lines，见 desktop-call/src/proto.rs。
 * **stdout 只走协议，日志在 stderr** —— 这里按行读，任何杂输出都会污染解析。
 */
@Serializable
internal data class SidecarEvent(
    val evt: String,
    val state: String? = null,
    val message: String? = null,
    val fatal: Boolean = false,
    val version: String? = null,
    /** ready 事件里带的后端名：stub / livekit */
    val backend: String? = null,
)

/** 侧车二进制的定位与启动 */
internal object Sidecar {

    /**
     * 按优先级找可执行文件：
     * 1. JVM 参数 -Dim.call.sidecar=/path（开发/调试用）
     * 2. 应用目录下 native/<os>-<arch>/yanshu-call-agent（jpackage 安装形态）
     * 3. PATH 里（系统包管理安装）
     *
     * 找不到就返回 null —— 此时 supported=false，UI 会明确提示「该平台暂不支持通话」，
     * 而不是让人对着无声界面发呆。
     */
    fun locate(): File? {
        System.getProperty("im.call.sidecar")?.let { p ->
            val f = File(p)
            if (f.canExecute()) return f
        }
        val exe = if (isWindows()) "yanshu-call-agent.exe" else "yanshu-call-agent"
        // 2) 应用目录（jpackage 的 app/ 或开发时的运行目录）
        val appDir = runCatching {
            File(Sidecar::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()
        if (appDir != null) {
            val candidates = listOf(
                File(appDir, "native/$exe"),
                File(appDir, "native/${osTag()}/$exe"),
                File(appDir.parentFile ?: appDir, "native/$exe"),
            )
            candidates.firstOrNull { it.canExecute() }?.let { return it }
        }
        // 3) PATH
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        return path.map { File(it, exe) }.firstOrNull { it.canExecute() }
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun osTag(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val o = when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            else -> "linux"
        }
        val a = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "x64"
        }
        return "$o-$a"
    }
}

/** 一次通话对应的侧车会话 */
internal class SidecarSession private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }
    private val events = MutableSharedFlow<SidecarEvent>(extraBufferCapacity = 64)

    /** 进程意外退出时回调（JVM 侧据此把 UI 置为失败，而不是永远显示「已接通」） */
    var onBroken: ((String) -> Unit)? = null

    private val readerThread = thread(isDaemon = true, name = "sidecar-reader") {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val evt = runCatching { json.decodeFromString(SidecarEvent.serializer(), line) }.getOrNull()
                if (evt == null) {
                    System.err.println("[sidecar] 无法解析: $line")
                    return@forEachLine
                }
                events.tryEmit(evt)
                if (evt.evt == "error" && evt.fatal) {
                    onBroken?.invoke(evt.message ?: "通话媒体层错误")
                }
            }
        } finally {
            // 读线程结束 = 进程不再产出 → 若还在通话中，就是断了
            onBroken?.invoke("通话媒体进程已退出")
        }
    }

    /** stderr 转印到应用日志：媒体层的警告/错误都在这里，丢掉就无从排查 */
    private val errThread = thread(isDaemon = true, name = "sidecar-stderr") {
        runCatching {
            process.errorStream.bufferedReader().forEachLine { System.err.println("[sidecar] $it") }
        }
    }

    private fun sendLine(line: String) {
        runCatching {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    fun sendJoin(url: String, token: String) {
        sendLine("{\"cmd\":\"join\",\"url\":${url.jsonStr()},\"token\":${token.jsonStr()},\"mic\":true}")
    }

    fun sendMuted(muted: Boolean) = sendLine("{\"cmd\":\"set_muted\",\"muted\":$muted}")

    /** 等待某条状态事件；超时返回 false（调用方据此报错，而不是无限挂起） */
    suspend fun awaitState(target: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            events.first { it.evt == "state" && it.state == target }
            true
        } ?: false

    /** 等待进程就绪（ready 事件），返回事件本身（含 backend 名） */
    suspend fun awaitReadyEvent(timeoutMs: Long = 5_000): SidecarEvent? =
        withTimeoutOrNull(timeoutMs) { events.first { it.evt == "ready" } }

    suspend fun awaitReady(timeoutMs: Long = 5_000): Boolean = awaitReadyEvent(timeoutMs) != null

    override fun close() {
        onBroken = null // 主动关闭不算异常
        runCatching { sendLine("{\"cmd\":\"leave\"}") }
        runCatching { writer.close() }
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    companion object {
        fun start(binary: File): SidecarSession {
            val pb = ProcessBuilder(binary.absolutePath)
                .redirectErrorStream(false)
            val p = pb.start()
            val w = p.outputStream.bufferedWriter()
            return SidecarSession(p, w)
        }
    }
}

/** 最小 JSON 字符串转义（url/token 里可能有引号，虽然极少） */
internal fun String.jsonStr(): String = buildString(length + 2) {
    append('"')
    for (c in this@jsonStr) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}
