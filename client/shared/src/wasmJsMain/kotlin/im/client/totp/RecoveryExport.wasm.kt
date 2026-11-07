package im.client.totp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Web 端导出：下载用 Blob + <a download>，打印用新窗口 + window.print()。
 *
 * 为什么不直接 window.print()：那会把整个 IM 界面（含侧栏与消息）一起打出来。
 * 这里新开一个只含恢复码的窗口，打完自动关闭。
 */
@JsFun("""(fileName, content) => {
  try {
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 5000);
    return true;
  } catch (e) { return false; }
}""")
private external fun jsSave(fileName: String, content: String): Boolean

@JsFun("""(content) => {
  try {
    const w = window.open('', '_blank', 'width=720,height=900');
    if (!w) return false;
    const esc = content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    w.document.write(
      '<!doctype html><meta charset="utf-8"><title>雁书 · 恢复码</title>' +
      '<style>body{font:14px/1.7 system-ui,sans-serif;padding:32px;color:#111}' +
      'pre{white-space:pre-wrap;font:13px/1.6 ui-monospace,monospace}</style>' +
      '<pre>' + esc + '</pre>'
    );
    w.document.close();
    w.focus();
    w.print();
    return true;
  } catch (e) { return false; }
}""")
private external fun jsPrint(content: String): Boolean

private object WebRecoveryExport : RecoveryExport {
    override val canSave: Boolean = true
    override val canPrint: Boolean = true

    override suspend fun save(fileName: String, content: String): Boolean =
        jsSave(fileName, content)

    override suspend fun print(content: String): Boolean =
        jsPrint(content)
}

actual fun recoveryExport(): RecoveryExport = WebRecoveryExport
