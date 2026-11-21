package com.downloadsource.storage

import android.content.Context

object DisclaimerStore {
    private const val PREF = "lm_disclaimer"
    private const val KEY = "accepted"

    val TEXT = """
        【免责声明】
        1. 本应用为个人学习与技术研究用途的下载/浏览工具，只做数据传输与本地管理，不存储、不提供任何内容。
        2. 通过本应用下载的内容版权归原作者或权利方所有。请仅下载你有权获取的内容，不得用于商业传播、二次分发或任何侵权用途。
        3. 网盘/网站解析依赖你本人在对应平台的有效登录态与授权。请遵守各平台服务条款，因违规使用导致的账号风险由使用者自行承担。
        4. 本应用不绕过任何付费、会员或版权保护机制；解析失败、链接失效属于正常情况，请勿用于规避平台限制。
        5. 使用本应用即表示你已知悉并同意以上条款；如不同意，请立即卸载并停止使用。
    """.trimIndent()

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun accept(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}
