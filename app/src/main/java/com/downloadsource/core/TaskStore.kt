package com.downloadsource.core

import android.content.Context
import com.downloadsource.data.AppDatabase
import com.downloadsource.data.TaskEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object TaskStore {
    private val gson = Gson()
    private val dbMutex = Mutex() // 防止并发写入冲突

    suspend fun loadAsync(context: Context): List<DownloadTask> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).taskDao()
        val entities = dao.getAllTasks().first()
        entities.map { e ->
            DownloadTask(
                id = e.id, url = e.url, fileName = e.fileName, dirPath = e.dirPath,
                threads = e.threads, speedLimitKb = e.speedLimitKb,
                headers = gson.fromJson(e.headersJson, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap(),
                totalBytes = e.totalBytes, downloadedBytes = e.downloadedBytes,
                status = try { TaskStatus.valueOf(e.status) } catch (ex: Exception) { TaskStatus.Pending },
                error = e.error, outputPath = e.outputPath, createdAt = e.createdAt
            )
        }
    }

    // 修复：采用“先清空，再插入”的方式，确保数据库与内存完全一致，不再出现幽灵任务
    fun save(context: Context, list: List<DownloadTask>) {
        val dao = AppDatabase.getDatabase(context).taskDao()
        val entities = list.map { t ->
            TaskEntity(
                id = t.id, url = t.url, fileName = t.fileName, dirPath = t.dirPath,
                threads = t.threads, speedLimitKb = t.speedLimitKb,
                headersJson = gson.toJson(t.headers),
                totalBytes = t.totalBytes, downloadedBytes = t.downloadedBytes,
                status = t.status.name, error = t.error, outputPath = t.outputPath, createdAt = t.createdAt
            )
        }
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            dbMutex.withLock {
                androidx.room.withTransaction(AppDatabase.getDatabase(context)) {
                    dao.clearAll()
                    if (entities.isNotEmpty()) {
                        dao.insertTasks(entities)
                    }
                }
            }
        }
    }
}
