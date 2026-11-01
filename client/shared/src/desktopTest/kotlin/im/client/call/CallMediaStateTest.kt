package im.client.call

import im.client.CallController
import im.client.CallMediaState
import im.client.CallState
import im.client.CallUiState
import im.client.net.ImConnection
import im.client.proto.CallEventType
import im.client.proto.CallSignalData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 媒体状态机回归测试（AGENT.md §6.6：「状态迁移要用测试钉住」）。
 *
 * 钉住的是 [CallController] 与 [MediaSession] 之间的契约，不是 LiveKit：
 * - 信令通了 ≠ 媒体通了：Accept 之后先是 `InCall + Connecting`，join 成功才 `Connected`；
 * - 入会失败/地址没配/平台不支持，如实落到 `Failed` / `Unsupported`，不假装在通话；
 * - 所有结束路径都要 `leave()`（麦克风必须被显式释放，AGENT.md §6.2）。
 *
 * 稳定性：没有真实网络、没有真实时钟依赖（只在轮询判据之间用 10ms 让出 CPU），
 * 每条断言的终止条件都由 StateFlow 驱动，全部套 `withTimeout`，不会挂死。
 */
class CallMediaStateTest {

    // ---------- 用例 1：正常入会 ----------

    @Test
    fun acceptJoinsOnceAndReachesConnected() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeMediaSession().apply { joinGate = gate }
        withController(fake) { controller ->
            controller.handleSignal(acceptSignal())

            // 1) 信令已进通话，媒体停在 Connecting —— 这一格里 UI 必须显示「正在接入语音」，
            //    不能提前显示「已接通」（Web 端第一版就是在这里把"接通了"写在没开麦的时刻）。
            val connecting = awaitState(controller) {
                it.state == CallState.InCall && it.media == CallMediaState.Connecting
            }
            assertEquals("room-1", connecting.roomName)
            assertEquals("tok-1", connecting.livekitToken)
            assertEquals("", connecting.mediaError)

            // 2) 放行入会 → Connected，且 join 只被调用一次、参数就是 (livekit_url, token)
            gate.complete(Unit)
            val connected = awaitState(controller) { it.media == CallMediaState.Connected }
            assertEquals(CallState.InCall, connected.state)
            assertEquals(listOf(LIVEKIT_URL to "tok-1"), fake.joinCalls.toList())
        }
    }

    // ---------- 用例 2：入会失败 ----------

    @Test
    fun joinFailureSurfacesMediaErrorAndStaysInCall() {
        val fake = FakeMediaSession(joinResult = Result.failure(RuntimeException("boom")))
        withController(fake) { controller ->
            controller.handleSignal(acceptSignal())

            val failed = awaitState(controller) { it.media == CallMediaState.Failed }
            // 信令这一侧仍然是 InCall（对方还等着通话），失败原因如实回填给界面
            assertEquals(CallState.InCall, failed.state)
            assertTrue(failed.mediaError.contains("boom"), "mediaError=${failed.mediaError}")
            assertEquals(1, fake.joinCalls.size)
        }
    }

    // ---------- 用例 3：平台没有媒体实现 ----------

    @Test
    fun unsupportedPlatformNeverJoins() {
        val fake = FakeMediaSession(supported = false)
        withController(fake) { controller ->
            controller.handleSignal(acceptSignal())

            val s = awaitState(controller) { it.media == CallMediaState.Unsupported }
            assertEquals(CallState.InCall, s.state)
            assertTrue(s.mediaError.isNotBlank(), "Unsupported 必须带可读原因")
            // 关键：连 join 都不该尝试（桌面端就是这条路径）
            assertEquals(0, fake.joinCalls.size)
        }
    }

    // ---------- 用例 4：服务端没下发 livekit_url ----------

    @Test
    fun missingLivekitUrlFailsWithoutJoining() {
        val fake = FakeMediaSession()
        withController(fake, livekitUrl = "") { controller ->
            controller.handleSignal(acceptSignal())

            val s = awaitState(controller) { it.media == CallMediaState.Failed }
            assertTrue(s.mediaError.contains("livekit_url"), "mediaError=${s.mediaError}")
            assertEquals(0, fake.joinCalls.size)
        }
    }

    // ---------- 用例 5：通话中挂断 ----------

    @Test
    fun hangupLeavesRoomAndReturnsToIdle() {
        val fake = FakeMediaSession()
        withController(fake) { controller ->
            controller.handleSignal(acceptSignal())
            awaitState(controller) { it.media == CallMediaState.Connected }

            controller.handleSignal(CallSignalData(callId = "call-1", event = CallEventType.Hangup))

            val idle = awaitState(controller) { it.state == CallState.Idle }
            // 回到初始态：不残留 Connected / Failed，否则下一通电话会带着上一条的错误文案开局
            assertEquals(CallMediaState.None, idle.media)
            assertEquals("", idle.mediaError)
            assertEquals("", idle.callId)
            // teardownMedia() 是 scope.launch 出去的：必须等到 leave() 真的跑完，
            // 麦克风才算是被释放了（不能只看状态回到 Idle）
            awaitCondition { fake.leaveCount.get() == 1 }
        }
    }

    // ---------- 用例 6：静音 ----------

    @Test
    fun toggleMuteFlipsStateAndNotifiesMedia() {
        val fake = FakeMediaSession()
        withController(fake) { controller ->
            controller.handleSignal(acceptSignal())
            awaitState(controller) { it.media == CallMediaState.Connected }

            controller.toggleMute()
            assertTrue(controller.uiState.value.muted)
            assertEquals(listOf(true), fake.muteCalls.toList())

            // 再点一次恢复：状态与媒体面必须同步翻转，不能只改一边
            controller.toggleMute()
            assertFalse(controller.uiState.value.muted)
            assertEquals(listOf(true, false), fake.muteCalls.toList())
        }
    }

    // ---------- 用例 7：入会途中挂断（leave 与 join 的竞态） ----------

    /**
     * 挂断发生在 `media.join` 还没返回的窗口里：这一轮 join 会「晚到地」连接成功。
     * 如果只是丢弃它的结果，房间和麦克风就没人收了（系统状态栏的麦克风指示灯会一直亮），
     * 所以取消失败的这一轮必须再补一次 leave()。
     */
    @Test
    fun hangupDuringJoinReleasesMediaThatLandsLate() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeMediaSession().apply { joinGate = gate }
        withController(fake) { controller ->
            controller.handleSignal(acceptSignal())
            awaitState(controller) { it.media == CallMediaState.Connecting }

            // 挂断：这一次 teardownMedia() 只换来一次 leave()
            controller.hangUp()
            awaitState(controller) { it.state == CallState.Idle }
            awaitCondition { fake.leaveCount.get() == 1 }

            // 放行入会：它已经没用了，但必须被显式收尾 → 第二次 leave()
            gate.complete(Unit)
            awaitCondition { fake.leaveCount.get() == 2 }
        }
    }
}

private const val LIVEKIT_URL = "wss://livekit.test/rtc"

/**
 * 假媒体面：只记录「被怎么调用了」，不碰真实网络与音频。
 *
 * [joinGate] 非空时 join 会挂起，用来把 `Connecting` 这个中间态钉在画面里 ——
 * 真实 SDK 里它对应"正在申请麦克风 / 正在建连"，本来就会停几百毫秒到几秒。
 */
private class FakeMediaSession(
    override val supported: Boolean = true,
    private val joinResult: Result<Unit> = Result.success(Unit),
) : MediaSession {
    /** join 的入参（url 到 token）。join 跑在控制器自己的 scope 上，所以用同步列表 */
    val joinCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** setMuted 收到的值，按顺序记录 */
    val muteCalls: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    /** leave 次数：>1 说明重复释放，0 说明麦克风没关（都是 bug） */
    val leaveCount = AtomicInteger(0)

    @Volatile
    var joinGate: CompletableDeferred<Unit>? = null

    override suspend fun join(url: String, token: String): Result<Unit> {
        joinCalls.add(url to token)
        joinGate?.await()
        return joinResult
    }

    override fun setMuted(muted: Boolean) {
        muteCalls.add(muted)
    }

    override suspend fun leave() {
        leaveCount.incrementAndGet()
    }
}

private fun acceptSignal(
    callId: String = "call-1",
    token: String = "tok-1",
) = CallSignalData(
    callId = callId,
    event = CallEventType.Accept,
    roomName = "room-1",
    livekitToken = token,
)

/**
 * 起一个只会被测试驱动的 [CallController]。
 *
 * - `ws://127.0.0.1:1/ws`：端口 1 上没有监听者，而 [ImConnection] 只有 `connect()`
 *   才会建连，构造本身是纯本地的，不需要服务端。
 * - scope 由测试自己建，结束时 cancel：控制器 init 里注册了信令收集和响铃轮询协程，
 *   不取消会一直留在测试 JVM 里（也拖慢 `--tests 'im.client.call.*'` 的收尾）。
 */
private fun withController(
    media: MediaSession,
    livekitUrl: String = LIVEKIT_URL,
    block: suspend CoroutineScope.(CallController) -> Unit,
) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 第 3、4 个参数是 scope 与 media，正是为了把状态机从真实平台里摘出来测
    val controller = CallController(
        connection = ImConnection("ws://127.0.0.1:1/ws"),
        myUid = "uid-self",
        scope = scope,
        media = media,
    ).apply { this.livekitUrl = livekitUrl }
    try {
        scope.block(controller)
    } finally {
        scope.cancel()
    }
}

/** 等某个 [CallUiState] 谓词成立。StateFlow 会给新收集者补发当前值，所以不会漏掉已发生的状态 */
private suspend fun awaitState(
    controller: CallController,
    timeoutMs: Long = 5_000,
    predicate: (CallUiState) -> Boolean,
): CallUiState = withTimeout(timeoutMs) { controller.uiState.first(predicate) }

/** 等一件不是 flow 的事（例如 leave 次数）：小步让出 CPU，不依赖固定 sleep */
private suspend fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!predicate()) delay(10)
    }
}
