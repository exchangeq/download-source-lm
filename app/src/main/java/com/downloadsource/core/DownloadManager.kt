package com.downloadsource.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.downloadsource.BuildConfig
import com.downloadsource.storage.DirStore
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
    // 连接池与调度器按大文件多分片场景配置，避免每主机 5 路并发把线程数限制成摆设
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(okhttp3.ConnectionPool(48, 8, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 })
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
        val task = DownloadTask(url = url, fileName = tempName, dirPath = baseDir.absolutePath, threads = threads.coerceIn(1, 16), speedLimitKb = speedLimitKb, headers = headers)

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
                    if (BuildConfig.CHANNEL == "zeta" || BuildConfig.CHANNEL == "omega") {
                        val results = DiskParser.parse(context, shareInfo)
                        remove(context, task.id)
                        val newTasks = mutableListOf<DownloadTask>()
                        for (file in results) {
                            val catDir = createCategoryDir(baseDir, file.name)
                            newTasks.add(DownloadTask(url = file.url, fileName = sanitizeFileName(file.name), dirPath = catDir.absolutePath, threads = 6, speedLimitKb = speedLimitKb, headers = headers))
                        }
                        _tasks.update { it + newTasks }
                        TaskStore.save(context, _tasks.value)
                        for (newTask in newTasks) { startTask(context, newTask.id) }
                    }
                    return@launch
                }

                val parsedUrl = MirrorResolver.resolveFastest(context, url)
                val realName = sanitizeFileName(customName ?: probeRealFileName(parsedUrl, headers))
                val catDir = createCategoryDir(baseDir, realName)
                updateTaskState(context, task.id) {
                    it.copy(url = parsedUrl, fileName = uniqueName(catDir, realName), dirPath = catDir.absolutePath)
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
        name = name.replace(Regex("[\u0000-\u001F/:*?"<>|]"), "_")
        name = name.trim('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") name = "download_${System.currentTimeMillis()}"
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "")
            name = name.substring(0, 100) + if (ext.isNotEmpty() && ext.length < 10) ".$ext" else ""
        }
        return name
    }

    /** 同名文件自动加序号，避免覆盖已有文件 */
    private fun uniqueName(dir: File, name: String): String {
        val f = File(dir, name)
        if (!f.exists()) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (!File(dir, candidate).exists()) return candidate
            i++
        }
    }

    private fun createCategoryDir(baseDir: File, fileName: String): File {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val sub = when (ext) {
            "mp4", "mkv", "avi", "mov", "ts", "webm", "flv" -> "视频"
            "mp3", "flac", "wav", "aac", "m4a", "ogg" -> "音频"
            "apk", "exe", "ipa" -> "安装包"
            "jpg", "jpeg", "png", "gif", "webp", "svg" -> "图片"
            "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv" -> "文档"
            "zip", "rar", "7z", "tar", "gz" -> "压缩包"
            else -> "其他"
        }
        return File(baseDir, sub).apply { if (!exists()) mkdirs() }
    }

    fun start(context: Context, id: String) { scope.launch { pumpQueue(context) } }

    private fun startTask(context: Context, id: String) {
        if (_tasks.value.count { it.status == TaskStatus.Running } >= MAX_CONCURRENT) return

        val t = _tasks.value.find { it.id == id } ?: return
        if (t.status == TaskStatus.Running) return

        updateTaskState(context, id) { it.copy(status = TaskStatus.Running, error = null) }
        DownloadService.start(context)

        jobs[id] = scope.launch {
            try {
                val outFile = File(t.dirPath, t.fileName)
                val out = segmenter.download(t.id, t.url, outFile, t.threads, t.speedLimitKb, t.headers) { d, total, speed ->
                    _tasks.update { list ->
                        list.map {
                            if (it.id == id) it.copy(downloadedBytes = d, totalBytes = if (total > 0) total else it.totalBytes, speed = speed) else it
                        }
                    }
                    DownloadService.updateProgress(context, t.fileName, d, total, speed)
                }
                updateTaskState(context, id) { it.copy(status = TaskStatus.Completed, downloadedBytes = maxOf(it.totalBytes, out.length()), totalBytes = maxOf(it.totalBytes, out.length()), speed = 0, outputPath = out.absolutePath) }

                if (out.name.endsWith(".apk", true)) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (e: Exception) {}
                }
                checkPending(context)
            } catch (e: CancellationException) {
                // 暂停/删除由 pause/remove 维护状态，这里不再覆盖
                throw e
            } catch (e: Exception) {
                updateTaskState(context, id) { it.copy(status = TaskStatus.Failed, error = e.message, speed = 0) }
                checkPending(context)
            } finally {
                jobs.remove(id)
                pumpQueue(context)
            }
        }
    }

    fun moveToVault(context: Context, id: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        val srcFile = File(t.outputPath ?: return)
        if (srcFile.exists()) {
            val vaultDir = File(DirStore.resolveDownloadDir(context), ".vault")
            if (!vaultDir.exists()) vaultDir.mkdirs()
            val destFile = File(vaultDir, t.fileName)
            if (srcFile.renameTo(destFile)) {
                _tasks.update { list -> list.filterNot { it.id == id } }
                TaskStore.save(context, _tasks.value)
                Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, "已移入私密目录", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun rename(context: Context, id: String, newName: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        val safe = sanitizeFileName(newName)
        val dir = File(t.dirPath); val oldFile = File(dir, t.fileName); val newFile = File(dir, safe)
        if (oldFile.exists()) oldFile.renameTo(newFile)
        updateTaskState(context, id) { it.copy(fileName = safe, outputPath = newFile.absolutePath) }
    }

    fun autoResumeTasks(context: Context) {
        _tasks.value.filter { it.status == TaskStatus.Pending || it.status == TaskStatus.Paused }.forEach { startTask(context, it.id) }
    }

    fun pause(context: Context, id: String) {
        segmenter.cancelTask(id)
        jobs.remove(id)?.cancel()
        updateTaskState(context, id) { it.copy(status = TaskStatus.Paused, speed = 0) }
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

    private fun parseNameFrom(resp: okhttp3.Response): String? {
        val disposition = resp.header("Content-Disposition")
        if (disposition != null && disposition.contains("filename=")) {
            var name = disposition.substringAfter("filename=").trim('"', ' ')
            if (name.contains("''")) name = name.substringAfter("''")
            return java.net.URLDecoder.decode(name, "UTF-8")
        }
        val last = resp.request.url.encodedPath.substringAfterLast('/')
        if (last.isNotBlank() && last.contains('.')) return java.net.URLDecoder.decode(last, "UTF-8")
        return null
    }
}
