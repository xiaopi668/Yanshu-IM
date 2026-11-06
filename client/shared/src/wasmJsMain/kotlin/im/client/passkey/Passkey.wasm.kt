package im.client.passkey

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Web 端通行密钥（WebAuthn）。
//
// 为什么要这么一段 JS：WebAuthn 的 API 全部吃/吐 ArrayBuffer，而服务端往返的是
// base64url 字符串。中间这层「base64url <-> ArrayBuffer」必须准确，
// 少解一个字段浏览器就抛 SecurityError，而且提示文本不会告诉你是哪个字段。
//
// 注入方式与 livekit-client 一致：首次使用时插 script，不引 npm 依赖、不改构建脚本。

@JsFun(
    """() => {
  if (window.__imPasskey) return true;
  const b64uToBuf = (s) => {
    const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
    const b = atob((s + pad).replace(/-/g, "+").replace(/_/g, "/"));
    const buf = new Uint8Array(b.length);
    for (let i = 0; i < b.length; i++) buf[i] = b.charCodeAt(i);
    return buf.buffer;
  };
  const bufToB64u = (buf) => {
    const bytes = new Uint8Array(buf);
    let bin = "";
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  };

  // 服务端返回的 publicKey 选项里，challenge / user.id / credential.id 都是 base64url
  const inflate = (opts) => {
    const pk = Object.assign({}, opts);
    if (pk.challenge) pk.challenge = b64uToBuf(pk.challenge);
    if (pk.user && pk.user.id) pk.user = Object.assign({}, pk.user, { id: b64uToBuf(pk.user.id) });
    if (Array.isArray(pk.excludeCredentials)) {
      pk.excludeCredentials = pk.excludeCredentials.map((c) => Object.assign({}, c, { id: b64uToBuf(c.id) }));
    }
    if (Array.isArray(pk.allowCredentials)) {
      pk.allowCredentials = pk.allowCredentials.map((c) => Object.assign({}, c, { id: b64uToBuf(c.id) }));
    }
    return pk;
  };

  // 浏览器返回的 credential 转成服务端能解析的 JSON
  const deflate = (cred) => {
    const r = cred.response;
    const out = { id: cred.id, rawId: bufToB64u(cred.rawId), type: cred.type };
    if (cred.authenticatorAttachment) out.authenticatorAttachment = cred.authenticatorAttachment;
    if (r.attestationObject) {
      out.response = {
        clientDataJSON: bufToB64u(r.clientDataJSON),
        attestationObject: bufToB64u(r.attestationObject),
        transports: r.getTransports ? r.getTransports() : [],
      };
    } else {
      out.response = {
        clientDataJSON: bufToB64u(r.clientDataJSON),
        authenticatorData: bufToB64u(r.authenticatorData),
        signature: bufToB64u(r.signature),
        userHandle: r.userHandle ? bufToB64u(r.userHandle) : null,
      };
    }
    return JSON.stringify(out);
  };

  window.__imPasskey = {
    supported: () => !!(window.PublicKeyCredential && navigator.credentials),
    create: (optionsJson, onOk, onErr) => {
      try {
        const opts = JSON.parse(optionsJson);
        navigator.credentials.create({ publicKey: inflate(opts.publicKey || opts) })
          .then((cred) => onOk(deflate(cred)))
          .catch((e) => onErr(String((e && (e.name + ": " + e.message)) || e)));
      } catch (e) { onErr(String(e)); }
    },
    get: (optionsJson, onOk, onErr) => {
      try {
        const opts = JSON.parse(optionsJson);
        navigator.credentials.get({ publicKey: inflate(opts.publicKey || opts) })
          .then((cred) => onOk(deflate(cred)))
          .catch((e) => onErr(String((e && (e.name + ": " + e.message)) || e)));
      } catch (e) { onErr(String(e)); }
    },
  };
  return true;
}"""
)
private external fun jsInstallPasskey(): Boolean

@JsFun("(optionsJson, onOk, onErr) => window.__imPasskey.create(optionsJson, onOk, onErr)")
private external fun jsCreate(optionsJson: String, onOk: (String) -> Unit, onErr: (String) -> Unit)

@JsFun("(optionsJson, onOk, onErr) => window.__imPasskey.get(optionsJson, onOk, onErr)")
private external fun jsGet(optionsJson: String, onOk: (String) -> Unit, onErr: (String) -> Unit)

private object WebPasskey : PasskeyPlatform {
    override val supported: Boolean
        get() = runCatching { jsInstallPasskey() }.getOrDefault(false)

    override suspend fun create(optionsJson: String): Result<String> = suspendCancellableCoroutine { cont ->
        runCatching {
            jsCreate(
                optionsJson,
                { json -> cont.resume(Result.success(json)) },
                { err -> cont.resume(Result.failure(RuntimeException(humanize(err)))) },
            )
        }.onFailure { cont.resume(Result.failure(it)) }
    }

    override suspend fun get(optionsJson: String): Result<String> = suspendCancellableCoroutine { cont ->
        runCatching {
            jsGet(
                optionsJson,
                { json -> cont.resume(Result.success(json)) },
                { err -> cont.resume(Result.failure(RuntimeException(humanize(err)))) },
            )
        }.onFailure { cont.resume(Result.failure(it)) }
    }

    /** 把浏览器的 DOMException 名翻译成人能看懂的原因 */
    private fun humanize(raw: String): String = when {
        raw.contains("NotAllowedError") ->
            "已取消或超时（也可能是这台设备没有可用的指纹/面容）"
        raw.contains("SecurityError") ->
            "当前域名与通行密钥的 RP ID 不匹配（检查 IM_PUBLIC_BASE_URL 与 IM_WEBAUTHN_RP_ID）"
        raw.contains("InvalidStateError") ->
            "这台设备上已经绑定过该账号的通行密钥"
        raw.contains("NotSupportedError") ->
            "该浏览器或设备不支持通行密钥"
        else -> raw
    }
}

actual fun passkeyPlatform(): PasskeyPlatform = WebPasskey
