package com.downloadsource.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.downloadsource.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File

object DownloadManager {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        })
        .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val segmenter = Segmenter(httpClient)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()
    var MAX_CONCURRENT = 3

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun init(context: Context) {
        scope.launch {
            _tasks.value = TaskStore.loadAsync(context)
            _tasks.value.forEach { if (it.status == TaskStatus.Running) updateTaskState(context, it.id) { t -> t.copy(status = TaskStatus.Pending, speed = 0) } }
            NetworkMonitor.start(context)
        }
    }

    fun add(context: Context, url: String, customName: String?, threads: Int, speedLimitKb: Long, headers: Map<String, String>, baseDir: File) {
        val tempName = customName ?: "parsing_${System.currentTimeMillis()}"
        val task = DownloadTask(url = url, fileName = tempName, dirPath = baseDir.absolutePath, threads = threads, speedLimitKb = speedLimitKb, headers = headers)
        
        scope.launch {
            mutex.withLock { _tasks.value = _tasks.value + task }
            TaskStore.save(context, _tasks.value)
            
            try {
                if (Aria2Manager.getServer(context).isNotBlank()) {
                    val success = Aria2Manager.addTask(context, url)
                    if (success) {
                        remove(context, task.id)
                        withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "已推送到 Aria2 下载啦 (๑•̀ㅂ•́)و✧", android.widget.Toast.LENGTH_SHORT).show() }
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
                            newTasks.add(DownloadTask(url = file.url, fileName = file.name, dirPath = catDir.absolutePath, threads = threads, speedLimitKb = speedLimitKb, headers = headers))
                        }
                        
                        mutex.withLock { _tasks.value = _tasks.value + newTasks }
                        TaskStore.save(context, _tasks.value)
                        
                        for (newTask in newTasks) {
                            startTask(context, newTask.id)
                        }
                    }
                    return@launch
                }
                
                var parsedUrl = MirrorResolver.resolveFastest(context, url)
                val realName = if (customName == null) probeRealFileName(parsedUrl, headers) else customName
                val catDir = createCategoryDir(baseDir, realName)
                updateTaskState(context, task.id) { it.copy(url = parsedUrl, fileName = realName, dirPath = catDir.absolutePath) }
                startTask(context, task.id)
            } catch (e: Exception) { 
                updateTaskState(context, task.id) { it.copy(status = TaskStatus.Failed, error = e.message) } 
            }
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
            "html", "css", "js", "py", "java", "kt", "cpp", "c", "json", "xml" -> "代码"
            else -> "其他"
        }
        return File(baseDir, sub).apply { if (!exists()) mkdirs() }
    }

    fun start(context: Context, id: String) { scope.launch { startTask(context, id) } }
    
    private fun startTask(context: Context, id: String) {
        if (_tasks.value.count { it.status == TaskStatus.Running } >= MAX_CONCURRENT) { 
            updateTaskState(context, id) { it.copy(status = TaskStatus.Pending) }; return 
        }
        val t = _tasks.value.find { it.id == id } ?: return
        if (t.status == TaskStatus.Running) return
        
        updateTaskState(context, id) { it.copy(status = TaskStatus.Running, error = null) }
        DownloadService.start(context)
        
        jobs[id] = scope.launch {
            try {
                val out = segmenter.download(t.id, t.url, File(t.dirPath), t.fileName, t.threads, t.speedLimitKb, t.headers) { d, total, speed ->
                    _tasks.update { list ->
                        list.map {
                            if (it.id == id) it.copy(downloadedBytes = d, totalBytes = total, speed = speed) else it
                        }
                    }
                    DownloadService.updateProgress(context, t.fileName, d, total, speed)
                }
                updateTaskState(context, id) { it.copy(status = TaskStatus.Completed, downloadedBytes = it.totalBytes, speed = 0, outputPath = out.absolutePath) }
                
                if (out.name.endsWith(".apk", true)) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (e: Exception) {}
                }
                checkPending(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动暂停或删除，状态由 pause/remove 维护，不写失败
            } catch (e: Exception) {
                updateTaskState(context, id) { it.copy(status = TaskStatus.Failed, error = e.message) }
                checkPending(context)
            }
        }
    }
    
    // 修复：移入保险箱，将 withContext 替换为 Handler
    fun moveToVault(context: Context, id: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        val srcFile = File(t.outputPath ?: return)
        if (srcFile.exists()) {
            val vaultDir = File(t.dirPath).parentFile?.let { File(it, ".vault") } ?: return
            if (!vaultDir.exists()) vaultDir.mkdirs()
            val destFile = File(vaultDir, t.fileName)
            if (srcFile.renameTo(destFile)) {
                _tasks.value = _tasks.value.filterNot { it.id == id }
                TaskStore.save(context, _tasks.value)
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "已移入保险箱 (๑•̀ㅂ•́)و✧", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "移入失败 (´･_･`)", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "文件不存在 (´･_･`)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun rename(context: Context, id: String, newName: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        val dir = File(t.dirPath); val oldFile = File(dir, t.fileName); val newFile = File(dir, newName)
        if (oldFile.exists()) oldFile.renameTo(newFile)
        updateTaskState(context, id) { it.copy(fileName = newName, outputPath = newFile.absolutePath) }
    }
    
    fun autoResumeTasks(context: Context) {
        _tasks.value.filter { it.status == TaskStatus.Pending || it.status == TaskStatus.Paused }.forEach { startTask(context, it.id) }
    }

    fun pause(context: Context, id: String) { segmenter.cancelTask(id); jobs[id]?.cancel(); jobs.remove(id); updateTaskState(context, id) { it.copy(status = TaskStatus.Paused, speed = 0) } }
    fun remove(context: Context, id: String) {
        val t = _tasks.value.find { it.id == id }
        segmenter.cancelTask(id)
        jobs.remove(id)?.cancel()
        if (t != null) {
            runCatching { File(t.dirPath, ".${t.fileName}.parts").deleteRecursively() }
            runCatching { File(t.dirPath, ".${t.fileName}.ts_parts").deleteRecursively() }
        }
        _tasks.update { list -> list.filterNot { it.id == id } }
        TaskStore.save(context, _tasks.value)
    }
    private fun updateTaskState(context: Context, id: String, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
        TaskStore.save(context, _tasks.value)
    }
    private fun checkPending(context: Context) { if (_tasks.value.none { it.status == TaskStatus.Running || it.status == TaskStatus.Pending }) DownloadService.stop(context) else { _tasks.value.firstOrNull { it.status == TaskStatus.Pending }?.let { startTask(context, it.id) } } }

    private fun probeRealFileName(url: String, headers: Map<String, String>): String {
        try {
            if (url.contains(".m3u8")) return "video_${System.currentTimeMillis()}.mp4"
            val builder = okhttp3.Request.Builder().url(url).head()
            headers.forEach { (k, v) -> builder.header(k, v) }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val disposition = resp.header("Content-Disposition")
                if (disposition != null && disposition.contains("filename=")) {
                    var name = disposition.substringAfter("filename=").trim('"', ' ')
                    if (name.contains("''")) name = name.substringAfter("''")
                    return java.net.URLDecoder.decode(name, "UTF-8")
                }
                val path = resp.request.url.encodedPath
                var last = path.substringAfterLast('/')
                if (last.isNotBlank() && last.contains('.')) return java.net.URLDecoder.decode(last, "UTF-8")
                
                val mime = resp.header("Content-Type") ?: ""
                val ext = mimeToExtension(mime)
                
                if (last.isBlank()) last = "download_${System.currentTimeMillis()}"
                return "$last$ext"
            }
        } catch (e: Exception) { return "file_${System.currentTimeMillis()}.bin" }
    }

    private fun mimeToExtension(mime: String): String {
        val m = mime.lowercase()
        return when {
            m.contains("video/mp4") -> ".mp4"
            m.contains("video/mpeg") -> ".mpeg"
            m.contains("video/quicktime") -> ".mov"
            m.contains("video/x-msvideo") -> ".avi"
            m.contains("video/x-matroska") -> ".mkv"
            m.contains("video/webm") -> ".webm"
            m.contains("video/x-flv") -> ".flv"
            m.contains("video/mp2t") -> ".ts"
            m.contains("audio/mpeg") -> ".mp3"
            m.contains("audio/mp4") -> ".m4a"
            m.contains("audio/aac") -> ".aac"
            m.contains("audio/x-wav") -> ".wav"
            m.contains("audio/flac") -> ".flac"
            m.contains("audio/ogg") -> ".ogg"
            m.contains("application/vnd.android.package-archive") -> ".apk"
            m.contains("application/x-msdownload") -> ".exe"
            m.contains("application/zip") -> ".zip"
            m.contains("application/x-rar-compressed") -> ".rar"
            m.contains("application/x-7z-compressed") -> ".7z"
            m.contains("application/x-tar") -> ".tar"
            m.contains("application/gzip") -> ".gz"
            m.contains("text/plain") -> ".txt"
            m.contains("text/html") -> ".html"
            m.contains("text/css") -> ".css"
            m.contains("application/javascript") -> ".js"
            m.contains("application/json") -> ".json"
            m.contains("application/xml") -> ".xml"
            m.contains("application/pdf") -> ".pdf"
            m.contains("application/msword") -> ".doc"
            m.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document") -> ".docx"
            m.contains("application/vnd.ms-excel") -> ".xls"
            m.contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") -> ".xlsx"
            m.contains("application/vnd.ms-powerpoint") -> ".ppt"
            m.contains("application/vnd.openxmlformats-officedocument.presentationml.presentation") -> ".pptx"
            m.contains("application/vnd.oasis.opendocument.text") -> ".odt"
            m.contains("image/jpeg") -> ".jpg"
            m.contains("image/png") -> ".png"
            m.contains("image/gif") -> ".gif"
            m.contains("image/webp") -> ".webp"
            m.contains("image/svg+xml") -> ".svg"
            m.contains("image/x-icon") -> ".ico"
            m.contains("text/x-python") -> ".py"
            m.contains("text/x-java") -> ".java"
            m.contains("text/x-c") -> ".c"
            m.contains("text/x-c++") -> ".cpp"
            else -> ".bin" 
        }
    }
}
