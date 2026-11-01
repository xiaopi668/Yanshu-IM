package im.client.call

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 侧车协议的真实集成测试：启动**真实进程**，跑一遍 ready → join → connected → leave。
 *
 * 找不到二进制时直接跳过（不是失败）：CI 的 JVM job 没有 Rust 工具链，
 * 侧车构建由独立的 rust-sidecar job 负责；本地构建过侧车后这条测试会自动生效。
 */
class SidecarIntegrationTest {

    /** 从当前目录向上找 desktop-call 的构建产物 */
    private fun binary(): File? {
        val names = listOf(
            "desktop-call/target/debug/yanshu-call-agent",
            "desktop-call/target/release/yanshu-call-agent",
        )
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val d = dir ?: return@repeat
            names.forEach { rel ->
                val f = File(d, rel)
                if (f.canExecute()) return f
            }
            dir = d.parentFile
        }
        return null
    }

    @Test
    fun joinThenLeave() = runBlocking {
        val bin = binary()
        if (bin == null) {
            println("[sidecar-test] 未找到构建产物，跳过（先跑 cargo build）")
            return@runBlocking
        }

        val s = SidecarSession.start(bin)
        try {
            val ready = s.awaitReadyEvent(5_000)
            assertTrue(ready != null, "应收到 ready")
            // 只有 stub 后端能在没有 LiveKit 服务器的情况下连通；
            // 本地若构建过 --features livekit，这条测试没有意义，跳过而不是误报失败。
            if (ready.backend != "stub") {
                println("[sidecar-test] 二进制是 ${ready.backend} 后端，跳过协议连通性测试")
                return@runBlocking
            }
            s.sendJoin("wss://sidecar-test.invalid", "test-token")
            assertTrue(s.awaitState("connected", 5_000), "应进入 connected")
            // stderr 有日志、stdout 只有协议；能走到这里说明解析没被污染
            s.sendMuted(true)
            s.sendMuted(false)
        } finally {
            s.close()
        }
    }
}
