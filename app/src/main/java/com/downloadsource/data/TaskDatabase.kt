package com.downloadsource.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "download_tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    val dirPath: String,
    val threads: Int,
    val speedLimitKb: Long,
    val headersJson: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val error: String?,
    val outputPath: String?,
    val createdAt: Long
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    // 补全：清空全表的方法
    @Query("DELETE FROM download_tasks")
    suspend fun clearAll()
}

@Database(entities = [TaskEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "download_source_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
