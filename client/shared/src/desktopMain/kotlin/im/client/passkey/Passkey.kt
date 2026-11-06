package im.client.passkey

/**
 * 该平台暂不支持通行密钥。
 *
 * Android 要走 Credential Manager，且需要在站点上放 assetlinks.json 做域名绑定（Digital Asset Links），
 * 否则系统会拒绝把凭据给这个 App —— 那部分属于阶段二；
 * 桌面（JVM）没有系统级 WebAuthn API。
 *
 * 在接上之前如实返回 false，界面会提示「该浏览器或平台不支持通行密钥」，
 * 而不是让用户在设置里点了半天没反应。
 */
private object UnsupportedPasskey : PasskeyPlatform {
    override val supported: Boolean = false

    override suspend fun create(optionsJson: String): Result<String> =
        Result.failure(UnsupportedOperationException("该平台暂不支持通行密钥（请用浏览器登录后绑定）"))

    override suspend fun get(optionsJson: String): Result<String> =
        Result.failure(UnsupportedOperationException("该平台暂不支持通行密钥（请用浏览器登录）"))
}

actual fun passkeyPlatform(): PasskeyPlatform = UnsupportedPasskey
