package im.client.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 解码健壮性：未知枚举值兜底、脏帧返回 null 而不是崩溃 */
class FramesDecodeTest {
    @Test
    fun unknownMsgTypeFallsBackToText() {
        assertEquals(MsgType.Text, MsgType.from(99))          // 服务端新增枚举不崩
        assertEquals(MsgType.Text, MsgType.from(-1))
    }

    @Test
    fun garbageFrameReturnsNull() {
        // field 1 length-delimited，声明长度 127 但缓冲区只有 1 字节
        assertNull(Frames.decodeFrame(byteArrayOf(0x0A, 0x7F)))
        // 非法 wire type
        assertNull(Frames.decodeFrame(byteArrayOf(0x0B)))
        // 空帧：无字段，返回 Unknown（不是解码错误）
        assertEquals(FrameKind.Unknown, Frames.decodeFrame(byteArrayOf()))
    }

    @Test
    fun heartbeatFrameStillDecodes() {
        assertEquals(FrameKind.Heartbeat, Frames.decodeFrame(Frames.heartbeat()))
    }
}
