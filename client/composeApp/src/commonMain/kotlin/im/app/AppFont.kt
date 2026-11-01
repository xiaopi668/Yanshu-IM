package im.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * 应用字体。
 *
 * 为什么需要它：Web 端（Compose/Wasm）没有系统字体可用，中文字形要靠运行时下载
 * 回退字体 —— 首屏会先渲染成方框（口口口），过一会儿才正常；冷门字甚至可能一直缺。
 *
 * 解决办法是把一份中文字体子集随包发（GB2312 一级字 + ASCII + 中文标点，约 1.7MB）。
 * Android / 桌面本来就有系统字体，返回 null 表示「继续用系统默认」，不白占体积。
 */
@Composable
expect fun appFontFamily(): FontFamily?
