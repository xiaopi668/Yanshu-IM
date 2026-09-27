package im.client.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import im.client.db.ImDatabase
import im.client.proto.Msg
import im.client.proto.MsgType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MemoryStore 行为验证：ACK 保留消息（不闪没）、回显去重、清缓存、有序插入与淘汰。
 * 用内存 SQLite，验证内存态与 SQLite 态一致。
 */
class MemoryStoreTest {
    private fun newStore(): Pair<MemoryStore, ImDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ImDatabase.Schema.create(driver)
        val db = ImDatabase(driver)
        return MemoryStore(db) to db
    }

    private fun pending(text: String, at: Long) = Msg(
        conversationId = "c1",
        fromUid = "u1",
        msgType = MsgType.Text,
        text = text,
        sentAt = at,
        clientMsgId = "cm-$at",
        sending = true,
    )

    @Test
    fun ackKeepsMessageAndCleansPendingRow() {
        val (store, db) = newStore()
        store.upsertMessage(pending("hi", 1))
        // ACK 到达但 notify 回显还没来：消息不能被删掉
        store.confirmMessage("cm-1", "srv-1", 7, "c1")

        val list = store.messages.value["c1"]!!
        assertEquals(1, list.size)
        assertEquals("srv-1", list[0].serverMsgId)
        assertEquals(7, list[0].seq)
        assertFalse(list[0].sending)

        // SQLite：p_ 脏行已清，只剩一条 server_msg_id 行，pending=0
        val rows = db.imQueries.loadRecent("c1", 50).executeAsList()
        assertEquals(1, rows.size)
        assertEquals("srv-1", rows[0].server_msg_id)
        assertEquals(0, rows[0].pending)
        assertEquals("cm-1", rows[0].client_msg_id)
    }

    @Test
    fun ackAfterNotifyDoesNotDuplicate() {
        val (store, db) = newStore()
        store.upsertMessage(pending("hi", 1))
        // 回显先到（notify 不带 clientMsgId）
        store.upsertMessage(
            Msg(conversationId = "c1", serverMsgId = "srv-1", seq = 7, fromUid = "u1", msgType = MsgType.Text, text = "hi", sentAt = 1)
        )
        assertEquals(2, store.messages.value["c1"]!!.size)

        // ACK 到达：合并成一条，内存与 DB 都不留重复行
        store.confirmMessage("cm-1", "srv-1", 7, "c1")
        val list = store.messages.value["c1"]!!
        assertEquals(1, list.size)
        assertEquals("srv-1", list[0].serverMsgId)
        assertEquals(1, db.imQueries.loadRecent("c1", 50).executeAsList().size)
    }

    @Test
    fun clearCachesEmptiesBothTablesAndRebindsOwner() {
        val (store, db) = newStore()
        store.setCacheOwner("u-old")
        store.setConversations(listOf(Conversation("c1", "single", "old", 3, 1)))
        store.upsertMessage(pending("hi", 1))

        store.clearCaches()
        store.setCacheOwner("u-new")

        val convs = db.imQueries.loadAllConversations().executeAsList()
        // 除了归属账号保留行（__cache_owner__），其它会话缓存都应清空
        assertEquals(1, convs.size)
        assertEquals("__cache_owner__", convs[0].id)
        assertEquals("u-new", convs[0].title)
        assertEquals(0, db.imQueries.loadRecent("c1", 50).executeAsList().size)
        assertTrue(store.conversations.value.isEmpty())
        assertTrue(store.messages.value.isEmpty())
        assertEquals("u-new", store.cacheOwnerUid)
    }

    @Test
    fun upsertKeepsSeqOrderAndCapsMemory() {
        val store = MemoryStore(null)   // Web 端 db=null 路径
        repeat(520) { i ->
            store.upsertMessage(
                Msg(conversationId = "c1", serverMsgId = "s$i", seq = i + 1L, fromUid = "u1",
                    msgType = MsgType.Text, text = "m$i", sentAt = i.toLong())
            )
        }
        val list = store.messages.value["c1"]!!
        // 单会话最多常驻 500 条，且按 seq 有序、丢最旧的
        assertEquals(500, list.size)
        assertEquals("s20", list.first().serverMsgId)
        assertEquals("s519", list.last().serverMsgId)
        assertTrue(list.zipWithNext().all { (a, b) -> a.seq < b.seq })
    }
}
