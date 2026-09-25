package im.client.file

/** 选中的文件 */
data class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

/** expect/actual：选择文件；返回 null 表示取消或不支持 */
expect suspend fun pickFile(): PickedFile?
