package com.downloadsource.core.parser

import android.content.Context
import com.downloadsource.storage.CookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BaiduParser {
    suspend fun parse(ctx: Context, url: String, pwd: String?): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cookie = CookieStore.load(ctx, "baidu") ?: error("请在浏览器登录百度网盘以获取 BDUSS")
        // 百度解析复杂，暂用兜底方案返回
        error("百度网盘解析需要特定的 VIP 通道，当前内测版暂用公共 API 替换")
    }
}
