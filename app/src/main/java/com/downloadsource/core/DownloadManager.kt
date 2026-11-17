package com.downloadsource.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.downloadsource.BuildConfig
import com.downloadsource.storage.DirStore
import com.downloadsource.utils.UserAgents
import kotlinx.coroutines.delay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DownloadManager {
    /** DNS 结果本地缓存 5 分钟：多线程分片不再各自解析域名，建连更快 */
    private object CachedDns : okhttp3.Dns {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<java.net.InetAddress>, Long>>()
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.takeIf { now - it.second < 5 * 60 * 1000L }?.let { return it.first }
            val fresh = okhttp3.Dns.SYSTEM.lookup(hostname)
            cache[hostname] = fresh to now
            return fresh
        }
    }

    // 连接池与调度器按大文件多分片场景配置，避免每主机 5 路并发把线程数限制成摆设
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(okhttp3.ConnectionPool(160, 15, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = 512; maxRequestsPerHost = 256 })
        .dns(CachedDns)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val segmenter = Segmenter(httpClient)
    private val jobs = ConcurrentHashMap<String, Job>()

    var MAX_CONCURRENT = 3

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun init(context: Context) {
        scope.launch {
            _tasks.value = TaskStore.loadAsync(context)
            _tasks.value.forEach {
                if (it.status == TaskStatus.Running) updateTaskState(context, it.id) { t -> t.copy(status = TaskStatus.Pending, speed = 0) }
            }
            NetworkMonitor.start(context)
            pumpQueue(context)
        }
    }

    fun add(context: Context, url: String, customName: String?, threads: Int, speedLimitKb: Long, headers: Map<String, String>, baseDir: File) {
        val tempName = customName?.let { sanitizeFileName(it) } ?: "parsing_${System.currentTimeMillis()}"
        // 补默认 UA / Accept：不少站点对裸客户端限速或直接 403
        val finalHeaders = buildMap<String, String> {
            putAll(headers)
            if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", UserAgents.DEFAULT)
            if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
            if (keys.none { it.equals("Referer", true) }) {
                runCatching { "https://" + java.net.URI(url).host + "/" }.getOrNull()?.let { put("Referer", it) }
            }
        }
        val task = DownloadTask(url = url, fileName = tempName, dirPath = baseDir.absolutePath, threads = threads.coerceIn(1, 64), speedLimitKb = speedLimitKb, headers = finalHeaders)

        scope.launch {
            _tasks.update { it + task }
            TaskStore.save(context, _tasks.value)

            try {
                if (Aria2Manager.getServer(context).isNotBlank()) {
                    val success = Aria2Manager.addTask(context, url)
                    if (success) {
                        remove(context, task.id)
                        withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "已推送到 Aria2 下载啦", android.widget.Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                }

                val shareInfo = NetDiskParser.detect(url)
                if (shareInfo.type != NetDiskParser.DiskType.UNKNOWN) {
                    if (true) {
                        val results = DiskParser.parse(context, shareInfo)
                        remove(context, task.id)
                        val newTasks = mutableListOf<DownloadTask>()
                        for (file in results) {
                            val catDir = createCategoryDir(baseDir, file.name)
                            newTasks.add(DownloadTask(url = file.url, fileName = sanitizeFileName(file.name), dirPath = catDir.absolutePath, threads = 8, speedLimitKb = speedLimitKb, headers = finalHeaders))
                        }
                        _tasks.update { it + newTasks }
                        TaskStore.save(context, _tasks.value)
                        for (newTask in newTasks) { startTask(context, newTask.id) }
                    }
                    return@launch
                }

                val parsedUrl = MirrorResolver.resolveBest(context, url, httpClient)
                val realName = sanitizeFileName(customName ?: probeRealFileName(parsedUrl, finalHeaders))
                    .let { n ->
                        // 流媒体清单不能当成品文件：m3u8/mpd 落盘一定是 mp4
                        val ext = n.substringAfterLast('.', "").lowercase()
                        if (ext == "m3u8" || ext == "mpd") n.substringBeforeLast('.') + ".mp4" else n
                    }
                val catDir = createCategoryDir(baseDir, realName)
                // 同名冲突：磁盘已存在 + 队列里同目录同名任务，都要算进去，避免两个任务抢一个文件名
                val taken = _tasks.value.filter { it.dirPath == catDir.absolutePath && it.id != task.id }.map { it.fileName }
                updateTaskState(context, task.id) {
                    it.copy(url = parsedUrl, fileName = uniqueName(catDir, realName, taken), dirPath = catDir.absolutePath)
                }
                startTask(context, task.id)
            } catch (e: Exception) {
                updateTaskState(context, task.id) { it.copy(status = TaskStatus.Failed, error = e.message) }
            }
        }
    }

    /** 文件名净化：阻止 ../ 穿越、非法字符与超长名 */
    fun sanitizeFileName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name.replace(Regex("[\\p{Cntrl}/:*?\"<>|]"), "_")
        name = name.trim('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") name = "download_${System.currentTimeMillis()}"
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "")
            name = name.substring(0, 100) + if (ext.isNotEmpty() && ext.length < 10) ".$ext" else ""
        }
        return name
    }

    /** 复用浏览器 Cookie：需要登录/鉴权的私有源，直接带上当前会话 */
    fun cookieHeader(url: String): String? {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            var cookies: String? = null
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                cookies = runCatching { cm.getCookie(url) }.getOrNull()
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    cookies = runCatching { cm.getCookie(url) }.getOrNull()
                    latch.countDown()
                }
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            cookies?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    /** 按文件头纠正扩展名：服务器给错后缀/没给后缀时，落地文件仍能被系统正确识别 */
    private fun correctExtensionByMagic(file: File): File {
        if (!file.exists() || file.length() < 12) return file
        val head = ByteArray(16)
        runCatching { file.inputStream().use { it.read(head) } }
        val ext = file.extension.lowercase()
        val detected = when {
            head[0] == 0x25.toByte() && head[1] == 0x50.toByte() && head[2] == 0x44.toByte() && head[3] == 0x46.toByte() -> "pdf"
            head[0] == 0x52.toByte() && head[1] == 0x61.toByte() && head[2] == 0x72.toByte() -> "rar"
            head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() && head[2] == 0xBC.toByte() -> "7z"
            head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> "gz"
            head[0] == 0x42.toByte() && head[1] == 0x5A.toByte() -> "bz2"
            head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() -> "xz"
            head[0] == 0x49.toByte() && head[1] == 0x44.toByte() && head[2] == 0x33.toByte() -> "mp3"
            head[0] == 0x66.toByte() && head[1] == 0x4C.toByte() -> "flac"
            head[0] == 0x4F.toByte() && head[1] == 0x67.toByte() && head[2] == 0x67.toByte() -> "ogg"
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() -> "png"
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() -> "mkv"
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> "zip"
            head[4] == 0x66.toByte() && head[5] == 0x74.toByte() && head[6] == 0x79.toByte() && head[7] == 0x70.toByte() -> {
                val brand = String(head.copyOfRange(8, 12), Charsets.ISO_8859_1)
                if (brand.startsWith("M4A")) "m4a" else if (brand.startsWith("qt")) "mov" else "mp4"
            }
            else -> ""
        }
        if (detected.isEmpty()) return file
        // zip 家族：apk/xapk 本身也是 zip，别把 apk 改成 zip
        val finalExt = if (detected == "zip") {
            when (ext) {
                "apk", "xapk", "apks", "aab", "epub", "docx", "xlsx", "pptx", "jar" -> ext
                else -> if (file.name.contains("AndroidManifest") || looksLikeApk(file)) "apk" else "zip"
            }
        } else detected
        if (ext == finalExt) return file
        val base = file.name.substringBeforeLast('.')
        var renamed = File(file.parentFile, "$base.$finalExt")
        var i = 1
        while (renamed.exists()) { renamed = File(file.parentFile, "$base ($i).$finalExt"); i++ }
        return try { if (file.renameTo(renamed)) renamed else file } catch (e: Exception) { file }
    }

    private fun looksLikeApk(file: File): Boolean = runCatching {
        java.util.zip.ZipFile(file).use { z -> z.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    /** 同名文件自动加序号，避免覆盖已有文件 */
    private fun uniqueName(dir: File, name: String, taken: Collection<String> = emptyList()): String {
        val f = File(dir, name)
        if (!f.exists() && taken.none { it.equals(name, true) }) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (!File(dir, candidate).exists() && taken.none { it.equals(candidate, true) }) return candidate
            i++
        }
    }

    private fun createCategoryDir(baseDir: File, fileName: String): File {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val sub = when (ext) {
            "mp4", "mkv", "avi", "mov", "ts", "webm", "flv", "m4v", "wmv", "ogv", "rmvb", "3gp", "m3u8", "mpd" -> "视频"
            "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "ape", "aiff" -> "音频"
            "apk", "xapk", "apks", "aab", "exe", "ipa", "msi", "dmg", "iso" -> "安装包"
            "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "heic" -> "图片"
            "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "epub", "mobi", "json", "xml" -> "文档"
            "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst", "cab", "torrent" -> "压缩包"
            else -> "其他"
        }
        return File(baseDir, sub).apply { if (!exists()) mkdirs() }
    }

    fun start(context: Context, id: String) { scope.launch { pumpQueue(context) } }

    private fun startTask(context: Context, id: String) {
        if (_tasks.value.count { it.status == TaskStatus.Running } >= MAX_CONCURRENT) return

        val t0 = _tasks.value.find { it.id == id } ?: return
        val t = if (t0.dirPath.isBlank()) {
            val fixed = t0.copy(dirPath = DirStore.resolveDownloadDir(context).absolutePath)
            updateTaskState(context, id) { it.copy(dirPath = fixed.dirPath) }
            fixed
        } else t0
        if (t.status == TaskStatus.Running) return

        // 空间检查：低于 100MB 直接失败提示，避免写到一半磁盘满
        run {
            val dir = File(t.dirPath)
            val free = try { dir.usableSpace } catch (e: Exception) { Long.MAX_VALUE }
            if (free in 1..(100L shl 20)) {
                updateTaskState(context, id) { it.copy(status = TaskStatus.Failed, error = "存储空间不足（剩余 ${free / 1024 / 1024}MB）") }
                return
            }
        }

        updateTaskState(context, id) { it.copy(status = TaskStatus.Running, error = null) }
        DownloadService.start(context)

        // 登录态共享：任务没有显式 Cookie 时，取浏览器里该站点的 Cookie
        val effectiveHeaders = if (t.headers.keys.any { it.equals("Cookie", true) }) t.headers else {
            cookieHeader(t.url)?.let { t.headers + ("Cookie" to it) } ?: t.headers
        }
        if (effectiveHeaders != t.headers) updateTaskState(context, id) { it.copy(headers = effectiveHeaders) }

        jobs[id] = scope.launch {
            try {
                val outFile = File(t.dirPath, t.fileName)
                val out = segmenter.download(t.id, t.url, outFile, t.threads, t.speedLimitKb, effectiveHeaders) { d, total, speed, segs ->
                    _tasks.update { list ->
                        list.map {
                            if (it.id == id) it.copy(downloadedBytes = d, totalBytes = if (total > 0) total else it.totalBytes, speed = speed, segments = segs) else it
                        }
                    }
                    DownloadService.updateProgress(context, t.fileName, d, total, speed)
                }
                val finalOut = correctExtensionByMagic(out)
                updateTaskState(context, id) { it.copy(status = TaskStatus.Completed, fileName = finalOut.name, downloadedBytes = maxOf(it.totalBytes, finalOut.length()), totalBytes = maxOf(it.totalBytes, finalOut.length()), speed = 0, outputPath = finalOut.absolutePath) }

                if (out.name.endsWith(".apk", true)) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (e: Exception) {}
                }
                // 完成后清理同名残留
                runCatching { File(t.dirPath, ".${t.fileName}.part").delete() }
                runCatching { File(t.dirPath, ".${t.fileName}.ts_parts").deleteRecursively() }
                checkPending(context)
            } catch (e: CancellationException) {
                // 暂停/删除由 pause/remove 维护状态，这里不再覆盖
                throw e
            } catch (e: Exception) {
                val cur = _tasks.value.find { it.id == id }
                if (cur != null && cur.retries < 2) {
                    // 自动重试两次，间隔递增；重试期间保持 Pending 让队列泵接上
                    updateTaskState(context, id) { it.copy(status = TaskStatus.Pending, error = "自动重试 ${it.retries + 1}/2：${e.message}", retries = it.retries + 1, segments = emptyList(), speed = 0) }
                    scope.launch { delay(1500L * (cur.retries + 1)); pumpQueue(context) }
                } else {
                    updateTaskState(context, id) { it.copy(status = TaskStatus.Failed, error = e.message, speed = 0) }
                }
                checkPending(context)
            } finally {
                jobs.remove(id)
                pumpQueue(context)
            }
        }
    }

    fun moveToVault(context: Context, id: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        // outputPath 可能为空（旧任务/改名后），退回按目录+文件名定位，避免"点了没反应/文件消失"
        val srcFile = File(t.outputPath?.takeIf { it.isNotBlank() } ?: File(t.dirPath, t.fileName).absolutePath)
        if (!srcFile.exists()) {
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, "源文件不存在：${srcFile.name}", android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        val moved = com.downloadsource.storage.VaultRepo.import(context, srcFile, t.fileName)
        if (moved) {
            _tasks.update { list -> list.filterNot { it.id == id } }
            TaskStore.save(context, _tasks.value)
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, "已加密移入私密保险箱", android.widget.Toast.LENGTH_SHORT).show() }
        } else {
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, "移入失败，请检查存储权限", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    fun rename(context: Context, id: String, newName: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        val raw = newName.trim()
        if (raw.isEmpty()) {
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, "文件名不能为空", android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        val dir = File(t.dirPath.ifBlank { DirStore.resolveDownloadDir(context).absolutePath })
        // 以真实落盘文件为准：改名/魔数纠正后 outputPath 才是准的
        val src = File(t.outputPath?.takeIf { it.isNotBlank() } ?: File(dir, t.fileName).absolutePath)
        var safe = sanitizeFileName(raw)
        // 省略扩展名时自动沿用原文件后缀，避免变成无后缀文件打不开
        val srcExt = src.name.substringAfterLast('.', "")
        if (safe.substringAfterLast('.', "").isEmpty() && srcExt.isNotEmpty()) safe = "$safe.$srcExt"
        var dest = File(dir, safe)
        // 同名不覆盖：自动加 (1)(2)
        if (dest.exists() && dest.absolutePath != src.absolutePath) dest = File(dir, uniqueName(dir, safe))
        val moved = when {
            !src.exists() -> false
            src.absolutePath == dest.absolutePath -> true
            src.renameTo(dest) -> true
            else -> runCatching { src.copyTo(dest, overwrite = false); src.delete(); dest.length() > 0 }.getOrDefault(false)
        }
        if (moved) {
            updateTaskState(context, id) { it.copy(fileName = dest.name, outputPath = dest.absolutePath) }
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, "已重命名为 ${dest.name}", android.widget.Toast.LENGTH_SHORT).show() }
        } else {
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, "重命名失败：源文件不可写", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    fun autoResumeTasks(context: Context) {
        _tasks.value.filter { it.status == TaskStatus.Pending || it.status == TaskStatus.Paused }.forEach { startTask(context, it.id) }
    }

    fun pause(context: Context, id: String) {
        segmenter.cancelTask(id)
        jobs.remove(id)?.cancel()
        updateTaskState(context, id) { it.copy(status = TaskStatus.Paused, speed = 0, segments = emptyList()) }
        checkPending(context)
    }

    fun remove(context: Context, id: String) {
        val t = _tasks.value.find { it.id == id }
        segmenter.cancelTask(id)
        jobs.remove(id)?.cancel()
        if (t != null) {
            runCatching { File(t.dirPath, ".${t.fileName}.ts_parts").deleteRecursively() }
            runCatching { File(t.dirPath, ".${t.fileName}.part").delete() }
        }
        _tasks.update { list -> list.filterNot { it.id == id } }
        TaskStore.save(context, _tasks.value)
        checkPending(context)
    }

    private fun updateTaskState(context: Context, id: String, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
        TaskStore.save(context, _tasks.value)
    }

    /**
     * 队列泵：只要有空位就按先来后到补一个任务。
     * 旧逻辑只在 checkPending 里“取第一个 pending”，遇到并发上限就一直卡在 Pending，
     * 界面上表现为永远排队、永远不动——也就是所谓的幽灵任务。
     */
    private fun pumpQueue(context: Context) {
        val running = _tasks.value.count { it.status == TaskStatus.Running }
        if (running >= MAX_CONCURRENT) return
        val next = _tasks.value.firstOrNull { it.status == TaskStatus.Pending && !jobs.containsKey(it.id) } ?: run {
            if (_tasks.value.none { it.status == TaskStatus.Running }) DownloadService.stop(context)
            return
        }
        startTask(context, next.id)
        // 还有空位就继续补
        if (running + 1 < MAX_CONCURRENT) pumpQueue(context)
    }

    private fun checkPending(context: Context) = pumpQueue(context)

    /** HEAD 拿不到名字时用 1 字节 Range 请求兜底，兼容禁 HEAD 的站点 */
    private fun probeRealFileName(url: String, headers: Map<String, String>): String {
        try {
            if (url.contains(".m3u8")) {
                val name = url.substringBefore('?').substringAfterLast('/')
                return if (name.contains('.')) name else "video_${System.currentTimeMillis()}.mp4"
            }
            val builder = okhttp3.Request.Builder().url(url).header("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> builder.header(k, v) }
            httpClient.newCall(builder.head().build()).execute().use { resp ->
                parseNameFrom(resp)?.let { return it }
            }
            val ranged = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").header("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> ranged.header(k, v) }
            httpClient.newCall(ranged.build()).execute().use { resp ->
                parseNameFrom(resp)?.let { return it }
            }
            val path = java.net.URI(url).path ?: ""
            val last = path.substringAfterLast('/')
            if (last.isNotBlank() && last.contains('.')) return java.net.URLDecoder.decode(last, "UTF-8")
            return "file_${System.currentTimeMillis()}.bin"
        } catch (e: Exception) {
            return "file_${System.currentTimeMillis()}.bin"
        }
    }

    /** 兼容 GBK / 百分号编码 / RFC2047 / 带引号 / 缺扩展名，统一交给 FileNameResolver */
    private fun parseNameFrom(resp: okhttp3.Response): String? =
        FileNameResolver.resolve(
            resp.request.url.toString(),
            resp.header("Content-Disposition"),
            resp.header("Content-Type")
        )
}
