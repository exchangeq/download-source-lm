package com.downloadsource.core

import android.content.Context
import com.downloadsource.utils.UserAgents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

object MirrorResolver {
    private const val PREF = "mirrors"
    private const val KEY_LIST = "enabled_list"
    private const val KEY_BEST = "best_mirror"
    private const val KEY_BEST_TS = "best_mirror_ts"
    private const val BLACKLIST_MS = 10 * 60 * 1000L
    private const val KEY_AUTO = "auto_github"

    /**
     * 节点池：100 个前缀型代理候选。
     * 默认直连；用户开启镜像后并发测活，只走真正能用的节点，死链自动拉黑 10 分钟。
     */
    val AVAILABLE_MIRRORS: List<String> = listOf(        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghps.cc/",
        "https://ghproxy.cc/",
        "https://ghproxy.com/",
        "https://gh.api.99988866.xyz/",
        "https://ghproxy.cfd/",
        "https://gh-proxy.net/",
        "https://ghproxy.top/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.link/",
        "https://ghdl.site/",
        "https://gh.xxooo.cf/",
        "https://gh.llkk.cc/",
        "https://gh.505688.xyz/",
        "https://ghproxy.imjih.cn/",
        "https://ghapi.xmoxi.cn/",
        "https://gh.xjhua.com/",
        "https://gh.9288.top/",
        "https://ghp.llkk.cc/",
        "https://gh.ddlc.top/",
        "https://gh.mabc.cc/",
        "https://gh.zzzzz.eu.org/",
        "https://gh-proxy.vercel.app/",
        "https://gh.ikxin.com/",
        "https://gh.jasonzeng.dev/",
        "https://gh.nxkr.com/",
        "https://gh.7cmh.cn/",
        "https://gh.mrruan.com/",
        "https://gh.sixyin.com/",
        "https://gh.zwc456.com/",
        "https://gh.trtgames.com/",
        "https://gh-proxy.top/",
        "https://ghproxy.ga/",
        "https://gh.xtunnel.top/",
        "https://gh-proxy.eu.org/",
        "https://ghp.ci-3d.com/",
        "https://gh.h233.eu.org/",
        "https://gh-proxy.xyz/",
        "https://ghproxy.cool/",
        "https://ghproxy.vip/",
        "https://ghfast.net/",
        "https://ghcf.uk/",
        "https://gh.api.xmoxi.cn/",
        "https://gh.oyxx.cn/",
        "https://gh.1ove.top/",
        "https://gh.hyf0.cn/",
        "https://gh.wlzhi.top/",
        "https://gh.cloudns.asia/",
        "https://gh.moeyy.xyz/",
        "https://ghproxy.hyf0.cn/",
        "https://gh.qzsy.net/",
        "https://gh.bjzw.cn/",
        "https://gh.zzk.pw/",
        "https://gh.sdai.top/",
        "https://gh.benypx.com/",
        "https://gh.cdn.ink/",
        "https://gh.wyf.one/",
        "https://ghproxy.fun/",
        "https://gh.20010501.xyz/",
        "https://gh.mixool.top/",
        "https://gh.yuxz.com/",
        "https://gh.jiu.su/",
        "https://gh.xinboring.cn/",
        "https://ghproxy.lsmg.cc/",
        "https://gh.tbace.com/",
        "https://gh.litianyi.com/",
        "https://gh.xiaomo.run/",
        "https://gh.zhyi.la/",
        "https://gh.ccc.best/",
        "https://gh.xmdm.com/",
        "https://gh.muyo.cc/",
        "https://gh.zsxw.org/",
        "https://gh.dhx.pub/",
        "https://gh.5unz.com/",
        "https://gh.jianyun.xyz/",
        "https://gh.skysu.cn/",
        "https://gh.dyv.ink/",
        "https://gh.rlwl.top/",
        "https://gh.qyli.cc/",
        "https://gh.kxuh.cn/",
        "https://gh.hhhx.xyz/",
        "https://gh.zzyy.xyz/",
        "https://gh.xuanyuan.ink/",
        "https://gh.dlsite.cyou/",
        "https://gh.qxiao.cc/",
        "https://gh.zzu.ren/",
        "https://gh.moyu.moe/",
        "https://gh.txdy.ltd/",
        "https://gh.99yy.ink/",
        "https://gh.mmzz.ink/",
        "https://gh.starw.ink/",
        "https://gh.lmx.ink/",
        "https://gh.rmlx.top/",
        "https://gh.xiaoniao.ink/",
        "https://gh.fastsoso.cn/",
        "https://gh.dddn.ink/",
        "https://gh.zmzf.ink/",
        "https://gh.zxy.ink/",
        "https://gh.ggyy.ink/")

    private val deadUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var best: String = ""

    fun load(context: Context): List<String> {
        val set = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_LIST, setOf("")) ?: setOf("")
        return set.toList()
    }

    fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_LIST, list.toSet()).apply()
    }

    /** 用户显式启用的节点；未启用则返回空，表示直连 */
    private fun enabled(context: Context): List<String> = load(context).filter { it.isNotBlank() }

    /** GitHub 自动加速默认开启：没手动配节点时自动挑前 12 个候选竞速 */
    private fun autoGithub(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoGithub(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, value).apply()
    }

    private fun pool(context: Context): List<String> {
        val manual = enabled(context)
        return if (manual.isNotEmpty()) manual
        else if (autoGithub(context)) AVAILABLE_MIRRORS.take(12)
        else emptyList()
    }

    private fun isGithub(url: String) = url.contains("github.com") || url.contains("githubusercontent.com")

    private fun join(mirror: String, url: String): String =
        if (mirror.endsWith("/")) "$mirror$url" else "$mirror/$url"

    private fun markDead(m: String) { deadUntil[m] = System.currentTimeMillis() + BLACKLIST_MS }
    private fun isDead(m: String) = (deadUntil[m] ?: 0L) > System.currentTimeMillis()

    private fun cachedBest(context: Context): String {
        if (best.isNotBlank() && !isDead(best)) return best
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val m = prefs.getString(KEY_BEST, "") ?: ""
        val ts = prefs.getLong(KEY_BEST_TS, 0L)
        if (m.isNotBlank() && !isDead(m) && System.currentTimeMillis() - ts < 30 * 60 * 1000L) {
            best = m
            return m
        }
        return ""
    }

    private fun saveBest(context: Context, m: String) {
        best = m
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_BEST, m).putLong(KEY_BEST_TS, System.currentTimeMillis()).apply()
    }

    /** 同步兜底：只使用已验证节点，绝不把死链拼进 URL */
    fun resolveFastest(context: Context, url: String): String {
        if (!isGithub(url)) return url
        if (pool(context).isEmpty()) return url
        val m = cachedBest(context)
        return if (m.isNotBlank()) join(m, url) else url
    }

    /**
     * 并发竞速：前 24 个候选同时探测，谁先回包用谁，4 秒内没有可用节点则直连。
     * 比逐个重试快一个数量级，也避免写入时才发现镜像挂了。
     */
    suspend fun resolveBest(context: Context, url: String, client: OkHttpClient): String {
        if (!isGithub(url)) return url
        val base = pool(context)
        if (base.isEmpty()) return url

        val cached = cachedBest(context)
        if (cached.isNotBlank()) return join(cached, url)

        val alive = base.filterNot { isDead(it) }
        if (alive.isEmpty()) return url

        val winner = withTimeoutOrNull(4000L) {
            coroutineScope {
                val ch = Channel<String>(Channel.UNLIMITED)
                val jobs = alive.take(24).map { m ->
                    launch(Dispatchers.IO) {
                        try {
                            val probe = Request.Builder().url(join(m, url))
                                .header("User-Agent", UserAgents.DEFAULT)
                                .header("Range", "bytes=0-0")
                                .build()
                            client.newCall(probe).execute().use { r ->
                                if (r.isSuccessful || r.code == 206) ch.send(m) else markDead(m)
                            }
                        } catch (e: Exception) {
                            markDead(m)
                        }
                    }
                }
                val m = ch.receive()
                jobs.forEach { it.cancel() }
                m
            }
        }

        if (winner != null) {
            saveBest(context, winner)
            return join(winner, url)
        }
        return url
    }

    /** 供设置页“测速”按钮使用：返回可用节点数量 */
    suspend fun healthCheck(context: Context, client: OkHttpClient): Int = coroutineScope {
        val list = enabled(context).ifEmpty { return@coroutineScope 0 }
        var ok = 0
        val jobs = list.map { m ->
            launch(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(join(m, "https://raw.githubusercontent.com/exchangeq/download-source-lm/main/README.md"))
                        .header("User-Agent", UserAgents.DEFAULT).build()
                    client.newCall(req).execute().use { if (it.isSuccessful) ok++ else markDead(m) }
                } catch (e: Exception) { markDead(m) }
            }
        }
        jobs.forEach { it.join() }
        ok
    }
}
