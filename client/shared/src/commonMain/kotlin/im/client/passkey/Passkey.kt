package im.client.passkey

/**
 * 平台通行密钥能力（WebAuthn）。
 *
 * 三端现状（不糊弄）：
 * - Web：真实现（navigator.credentials），见 wasmJsMain
 * - Android：需要 Credential Manager + Digital Asset Links（assetlinks.json 指向站点域名），
 *   暂未接 —— 返回 supported=false，界面会提示「该平台暂不支持」
 * - 桌面（JVM）：没有系统级 WebAuthn API，只能内嵌浏览器；同样返回 false
 *
 * 接口约定：create/get 收到/返回的都是**服务端原样的 JSON**，
 * 二进制字段用 base64url —— 平台差异只在这层体现，上层不关心。
 */
interface PasskeyPlatform {
    /** 这台设备此刻能否用通行密钥（有 PublicKeyCredential 且可用） */
    val supported: Boolean

    /** 调 navigator.credentials.create；返回浏览器给的 credential JSON */
    suspend fun create(optionsJson: String): Result<String>

    /** 调 navigator.credentials.get；返回断言 JSON */
    suspend fun get(optionsJson: String): Result<String>
}

expect fun passkeyPlatform(): PasskeyPlatform
