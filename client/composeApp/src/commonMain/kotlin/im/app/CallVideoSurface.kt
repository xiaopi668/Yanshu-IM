package im.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 视频画面的角色：远端大画面 / 本地预览小窗 */
enum class CallVideoRole { Remote, Local }

/**
 * 通话视频画面。
 *
 * Compose 画不了视频流（canvas 上没有解码器），三端各自用平台方式承载：
 * - Android：原生 SurfaceViewRenderer（WebRTC 的 VideoSink）
 * - Web：DOM video 图层，位置由 Compose 回传（见 wasm 的 actual）
 * - 桌面：目前无媒体视频能力，只放占位
 *
 * 注意：**不要**把 Android 的实现包进会裁剪的容器（Card/clip）—— SurfaceView 与 Compose
 * 是两套图层，被裁剪会直接黑屏。圆角只作用在外层背景。
 */
@Composable
expect fun CallVideoSurface(role: CallVideoRole, modifier: Modifier = Modifier)
