package im.client

fun main() {
    val driver = im.client.db.createDriver()
    println("driver=$driver")
    val db = driver?.let { im.client.db.ImDatabase(it) }
    db?.imQueries?.upsertConversation("c1", "single", "t", 5, 3)
    val list = db?.imQueries?.loadAllConversations()?.executeAsList()
    println("convs=$list")
}
