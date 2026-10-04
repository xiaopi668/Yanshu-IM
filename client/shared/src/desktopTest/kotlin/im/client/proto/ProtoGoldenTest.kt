package im.client.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.io.File

/**
 * 跨语言协议一致性校验。
 *
 * proto/im.proto 是唯一事实来源：服务端由 protoc 生成 Go 实现，客户端是手写编解码，
 * 两者靠人工对齐字段号——这是本项目最大的协议漂移风险。
 *
 * proto/golden 下的 .bin 由服务端 Go 实现（protoc 生成）编码，本测试：
 *   1) 解码并断言字段值，校验客户端解码器没有读错字段号；
 *   2) 把客户端编码器输出与 proto/golden/client 下的 .bin 快照比对，
 *      快照的语义正确性由服务端 TestClientGolden 反向解码校验。
 *
 * 改协议：./proto/gen.sh --update 重建服务端 golden，再跑
 * UPDATE_CLIENT_GOLDEN=1 gradle :shared:desktopTest 重建客户端快照。
 */
class ProtoGoldenTest {

    companion object {
        /** 从若干候选工作目录向上找仓库根，兼容 gradle 从仓库根或模块目录启动两种情况 */
        fun repoRoot(): File {
            val candidates = listOf(
                File("."), File(".."), File("../.."), File("../../.."), File("../../../..")
            )
            for (c in candidates) {
                val f = File(c.canonicalFile, "proto/golden")
                if (f.isDirectory) return f.canonicalFile.parentFile.parentFile
            }
            error("未找到 proto/golden，当前目录=${File(".").canonicalPath}")
        }

        fun readGolden(name: String): ByteArray {
            val f = File(repoRoot(), "proto/golden/$name.bin")
            check(f.isFile) { "缺少 ${f.path}，先执行 ./proto/gen.sh --update" }
            return f.readBytes()
        }
    }

    // ---------- 服务端 → 客户端：解码器必须读对字段号 ----------

    @Test
    fun heartbeatFrame() {
        assertIs<FrameKind.Heartbeat>(Frames.decodeFrame(readGolden("heartbeat")))
    }

    @Test
    fun authRespOkWithLargeUint64() {
        val d = assertIs<FrameKind.AuthResp>(Frames.decodeFrame(readGolden("auth_resp_ok"))).data
        assertEquals(true, d.ok)
        assertEquals(42L, d.maxSeqs["s_1_2"])
        // 超过 2^53 的 seq：JS Number 会丢精度，客户端必须按 64 位整数处理
        assertEquals(9007199254740993L, d.maxSeqs["g_100"])
    }

    @Test
    fun authRespFail() {
        val d = assertIs<FrameKind.AuthResp>(Frames.decodeFrame(readGolden("auth_resp_fail"))).data
        assertEquals(false, d.ok)
        assertEquals("account disabled", d.reason)
        assertEquals(emptyMap(), d.maxSeqs)
    }

    @Test
    fun msgNotifyText() {
        val m = assertIs<FrameKind.MsgNotify>(Frames.decodeFrame(readGolden("msg_notify_text"))).msg
        assertEquals("s_1_2", m.conversationId)
        assertEquals(42L, m.seq)
        assertEquals("1700000000000000001", m.serverMsgId)
        assertEquals("u1", m.fromUid)
        assertEquals(MsgType.Text, m.msgType)
        assertEquals("hi", m.text)
        assertEquals(1700000000000L, m.sentAt)
    }

    @Test
    fun msgNotifyImageWithAttachmentAndMentions() {
        val m = assertIs<FrameKind.MsgNotify>(Frames.decodeFrame(readGolden("msg_notify_image"))).msg
        assertEquals(MsgType.Image, m.msgType)
        val att = assertNotNull(m.attachment)
        assertEquals("http://minio:9000/im-attachments/b.jpg", att.url)
        assertEquals("b.jpg", att.name)
        assertEquals(6789L, att.size)
        assertEquals("image/jpeg", att.mime)
        assertEquals(640, att.width)
        assertEquals(480, att.height)
        assertEquals(listOf("u3"), m.mentionUids)
    }

    @Test
    fun msgAck() {
        val d = assertIs<FrameKind.MsgAck>(Frames.decodeFrame(readGolden("msg_ack"))).data
        assertEquals("cm-1", d.clientMsgId)
        assertEquals(42L, d.seq)
        assertEquals("1700000000000000001", d.serverMsgId)
        assertEquals("s_1_2", d.conversationId)
    }

    @Test
    fun msgPullRespNestedMessages() {
        val r = assertIs<FrameKind.MsgPullResp>(Frames.decodeFrame(readGolden("msg_pull_resp"))).resp
        assertEquals("s_1_2", r.conversationId)
        assertEquals(true, r.hasMore)
        assertEquals(44L, r.maxSeq)
        assertEquals(2, r.msgs.size)

        val sys = r.msgs[0]
        assertEquals(43L, sys.seq)
        assertEquals(MsgType.System, sys.msgType)
        assertEquals("""{"type":"member_added"}""", sys.text)

        val file = r.msgs[1]
        assertEquals(44L, file.seq)
        assertEquals(MsgType.File, file.msgType)
        assertEquals("report.pdf", assertNotNull(file.attachment).name)
    }

    @Test
    fun callInviteAcceptTimeout() {
        val invite = assertIs<FrameKind.CallSignalFrame>(
            Frames.decodeFrame(readGolden("call_invite"))
        ).data
        assertEquals("ab12cd34", invite.callId)
        assertEquals(CallEventType.Invite, invite.event)
        assertEquals("u2", invite.text)

        val accept = assertIs<FrameKind.CallSignalFrame>(
            Frames.decodeFrame(readGolden("call_accept"))
        ).data
        assertEquals(CallEventType.Accept, accept.event)
        assertEquals("call-ab12cd34", accept.roomName)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.sig", accept.livekitToken)

        val timeout = assertIs<FrameKind.CallSignalFrame>(
            Frames.decodeFrame(readGolden("call_timeout"))
        ).data
        assertEquals(CallEventType.Timeout, timeout.event)
    }

    @Test
    fun contactEvents() {
        val req = assertIs<FrameKind.ContactEventFrame>(
            Frames.decodeFrame(readGolden("contact_request"))
        ).data
        assertEquals("request", req.type)
        assertEquals("r-1", req.requestId)
        assertEquals("u9", req.fromUid)
        assertEquals("alice01", req.yid)
        assertEquals("Alice", req.nickname)
        assertEquals("我是 Alice", req.message)

        val ok = assertIs<FrameKind.ContactEventFrame>(
            Frames.decodeFrame(readGolden("contact_accepted"))
        ).data
        assertEquals("accepted", ok.type)
        assertEquals("r-1", ok.requestId)
        assertEquals("Alice", ok.nickname)
    }

    /** Frame field 12：失败应答。客户端解不出它，"发送失败"就传不到 UI，消息会一直停在发送中 */
    @Test
    fun errorFrame() {
        val e = assertIs<FrameKind.ErrorFrame>(
            Frames.decodeFrame(readGolden("error_send_failed"))
        ).data
        assertEquals("cm-err-1", e.refClientMsgId)
        assertEquals("not_member", e.code)
        assertEquals("not a member of conversation s_1_2", e.message)
    }

    /** 脏帧必须返回 null 而不是抛异常，否则一个坏帧会炸断读循环 */
    @Test
    fun corruptedFrameIsNull() {
        assertNull(Frames.decodeFrame(byteArrayOf(0x0A, 0x7F, 0x01)))
        assertNull(Frames.decodeFrame(byteArrayOf(0x08)))
    }

    // ---------- 客户端 → 服务端：编码器输出快照 ----------

    private fun uplinkFrames(): Map<String, ByteArray> = linkedMapOf(
        "auth_req" to Frames.authReq("jwt-token-abc", "dev-1", "desktop"),
        "msg_send_text" to Frames.msgSend("cm-1", "s_1_2", MsgType.Text, "你好，雁书"),
        "msg_send_image" to Frames.msgSend(
            "cm-2", "g_100", MsgType.Image, "",
            Attachment(
                url = "http://minio:9000/im-attachments/a.png",
                name = "a.png", size = 12345, mime = "image/png",
                width = 100, height = 80,
            )
        ),
        "msg_pull_req" to Frames.pullReq("s_1_2", 42, 200),
        "msg_read" to Frames.msgRead("s_1_2", 44),
    )

    @Test
    fun uplinkEncoderSnapshot() {
        val clientDir = File(repoRoot(), "proto/golden/client")
        val update = System.getenv("UPDATE_CLIENT_GOLDEN") == "1"
        if (update) clientDir.mkdirs()

        for ((name, bytes) in uplinkFrames()) {
            val f = File(clientDir, "$name.bin")
            if (update) {
                f.writeBytes(bytes)
                continue
            }
            check(f.isFile) {
                "缺少 ${f.path}，执行 UPDATE_CLIENT_GOLDEN=1 gradle :shared:desktopTest 重建"
            }
            val want = f.readBytes()
            if (!want.contentEquals(bytes)) {
                error(
                    "客户端上行帧编码与快照不一致（字段号/字段顺序漂移？）: $name\n" +
                        "  快照=${want.toHex()}\n  实际=${bytes.toHex()}"
                )
            }
        }
    }

    /** 上行帧不是下行分支，应识别为 Unknown 而不是误判成别的类型 */
    @Test
    fun uplinkFramesAreWellFormed() {
        assertEquals(FrameKind.Unknown, Frames.decodeFrame(Frames.authReq("t", "d", "desktop")))
        assertEquals(
            FrameKind.Unknown,
            Frames.decodeFrame(Frames.msgSend("c", "s", MsgType.Text, "x"))
        )
        assertNotNull(Frames.decodeFrame(Frames.msgRead("s_1_2", 44)))
        assertNotNull(Frames.decodeFrame(Frames.pullReq("s_1_2", 42, 200)))
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
