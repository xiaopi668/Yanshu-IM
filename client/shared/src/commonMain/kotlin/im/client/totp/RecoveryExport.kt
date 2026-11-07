package im.client.totp

/**
 * 恢复码的「导出」能力：保存为文件 / 打印。
 *
 * 三端落地方式差异很大（Web 用 Blob 与 window.print；桌面用文件对话框与 PrinterJob；
 * Android 用 SAF 与 PrintManager），所以做成 expect/actual，界面只调这几个方法。
 *
 * 为什么没有 copy()：剪贴板用 Compose 的 LocalClipboardManager 就够，三端一致，
 * 不必再抽象一层。
 */
interface RecoveryExport {
    /** 该平台能否「保存为文件」（能力缺失时界面隐藏按钮，而不是点了没反应） */
    val canSave: Boolean

    /** 该平台能否「打印」 */
    val canPrint: Boolean

    /** 保存为纯文本；返回是否成功（用户取消也算 false） */
    suspend fun save(fileName: String, content: String): Boolean

    /** 调起打印；返回是否成功发起 */
    suspend fun print(content: String): Boolean
}

expect fun recoveryExport(): RecoveryExport

/**
 * 生成给人看 / 存档的恢复码文本。
 *
 * 复制、保存、打印共用同一份 —— 三处各写一遍文案迟早会不一致，
 * 而这段文字是用户在**最慌张的时候**（手机丢了）要读的。
 */
fun recoveryCodesAsText(account: String, codes: List<String>, generatedAt: String): String {
    val sb = StringBuilder()
    sb.appendLine("雁书 · 两步验证恢复码")
    sb.appendLine("============================")
    sb.appendLine()
    if (account.isNotBlank()) sb.appendLine("账号：" + account)
    if (generatedAt.isNotBlank()) sb.appendLine("生成时间：" + generatedAt)
    sb.appendLine("共 " + codes.size + " 个，每个只能使用一次。")
    sb.appendLine()
    for (i in codes.indices) {
        val no = (i + 1).toString().padStart(2, ' ')
        sb.appendLine("  " + no + ".  " + codes[i])
    }
    sb.appendLine()
    sb.appendLine("用法：登录时在「两步验证码」输入框填入其中一个恢复码（用过的立即失效）。")
    sb.appendLine("注意：手机丢失时这是唯一入口；用完就没有第二条自助出路，只能联系管理员。")
    sb.appendLine("请勿截图分享 —— 这张纸上等价于你的第二把钥匙。")
    return sb.toString()
}
