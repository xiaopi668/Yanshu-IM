package im.client.file

// Android 端文件选择需要 Activity Result API，M4 阶段接入；当前返回 null
actual suspend fun pickFile(): PickedFile? = null
