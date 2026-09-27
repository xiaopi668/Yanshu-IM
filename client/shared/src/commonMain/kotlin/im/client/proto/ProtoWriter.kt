package im.client.proto

// 最小 protobuf 编解码（wire type 0=varint, 2=length-delimited），只支持 IM 协议所需子集。

/** 解码错误：字节流越界/格式非法时抛出，由 Frames.decodeFrame 捕获后丢弃该帧 */
class ProtoDecodeException(message: String) : RuntimeException(message)

class ProtoWriter {
    private val out = ArrayList<Byte>(256)

    fun varintField(field: Int, value: Long) {
        tag(field, 0)
        writeVarint(value)
    }

    fun boolField(field: Int, value: Boolean) = varintField(field, if (value) 1L else 0L)

    fun bytesField(field: Int, bytes: ByteArray) {
        tag(field, 2)
        writeVarint(bytes.size.toLong())
        bytes.forEach { out.add(it) }
    }

    fun stringField(field: Int, value: String) = bytesField(field, value.encodeToByteArray())

    fun messageField(field: Int, message: ProtoWriter) = bytesField(field, message.toByteArray())

    fun repeatedBytesField(field: Int, values: List<ByteArray>) = values.forEach { bytesField(field, it) }

    fun repeatedStringField(field: Int, values: List<String>) = values.forEach { stringField(field, it) }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

    private fun writeVarint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.add(x.toByte())
                return
            }
            out.add(((x and 0x7FL) or 0x80L).toByte())
            x = x ushr 7
        }
    }
}

class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    /** 返回 field -> 每条为 (wireType, value) 列表；varint 存 Long，length-delimited 存 ByteArray */
    fun readAll(): Map<Int, List<Field>> {
        val map = LinkedHashMap<Int, MutableList<Field>>()
        while (pos < data.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            when (val wireType = (key and 0x7).toInt()) {
                0 -> map.getOrPut(field) { mutableListOf() }.add(Field(wireType, readVarint(), null))
                1 -> {
                    if (data.size - pos < 8) throw ProtoDecodeException("fixed64 字段越界: pos=$pos, size=${data.size}")
                    pos += 8; continue
                }
                2 -> {
                    val len = readVarint().toInt()
                    // 越界（含 len 为负）抛解码错误，不能让 copyOfRange 抛 IndexOutOfBounds
                    if (len < 0 || len > data.size - pos) throw ProtoDecodeException("length-delimited 字段越界: len=$len, pos=$pos, size=${data.size}")
                    val b = data.copyOfRange(pos, pos + len)
                    pos += len
                    map.getOrPut(field) { mutableListOf() }.add(Field(wireType, null, b))
                }
                5 -> {
                    if (data.size - pos < 4) throw ProtoDecodeException("fixed32 字段越界: pos=$pos, size=${data.size}")
                    pos += 4; continue
                }
                else -> throw ProtoDecodeException("不支持的 wire type $wireType")
            }
        }
        return map
    }

    private fun readVarint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            if (pos >= data.size) throw ProtoDecodeException("varint 读取越界: pos=$pos, size=${data.size}")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    class Field(val wireType: Int, val num: Long?, val bytes: ByteArray?)
}

fun Map<Int, List<ProtoReader.Field>>.varints(field: Int): List<Long> =
    this[field]?.mapNotNull { it.num } ?: emptyList()

fun Map<Int, List<ProtoReader.Field>>.firstVarint(field: Int): Long? =
    varints(field).firstOrNull()

fun Map<Int, List<ProtoReader.Field>>.bytes(field: Int): List<ByteArray> =
    this[field]?.mapNotNull { it.bytes } ?: emptyList()

fun Map<Int, List<ProtoReader.Field>>.firstBytes(field: Int): ByteArray? =
    bytes(field).firstOrNull()

fun Map<Int, List<ProtoReader.Field>>.firstString(field: Int): String? =
    firstBytes(field)?.decodeToString()
