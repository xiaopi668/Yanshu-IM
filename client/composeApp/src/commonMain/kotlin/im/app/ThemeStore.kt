package im.app

/**
 * 用户选择的主题色（Material You 的种子色）。
 *
 * 返回 null 表示「跟随平台默认」：Android 12+ 跟随壁纸动态取色，其它平台用品牌色。
 * Web/桌面没有壁纸这个概念，能给用户的等价物就是「自己挑一个种子色」——
 * 这也是 M3 在 Web 上的通行做法。
 */
expect fun loadAccentSeed(): String?

/** 保存主题色；传 null 表示清除，回到「跟随默认」 */
expect fun saveAccentSeed(hex: String?)
