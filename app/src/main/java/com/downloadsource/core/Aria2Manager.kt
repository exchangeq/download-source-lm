package com.downloadsource.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object Aria2Manager {
    private val client = OkHttpClient()
    
    fun getServer(ctx: Context): String = ctx.getSharedPreferences("aria2", Context.MODE_PRIVATE).getString("server", "") ?: ""
    fun setServer(ctx: Context, url: String) { ctx.getSharedPreferences("aria2", Context.MODE_PRIVATE).edit().putString("server", url).apply() }

    suspend fun addTask(ctx: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        val server = getServer(ctx)
        if (server.isBlank()) return@withContext false
        
        // 修复：使用 JSONObject 安全转义，防止 URL 中的特殊字符破坏 JSON 结构
        val jsonBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "aria2.addUri")
            put("id", "ds")
            val params = JSONArray()
            // 兼容 http://host:6800/jsonrpc?token=xxx 形式
            val token = server.substringAfter("token=", "").substringBefore("&")
            if (token.isNotBlank()) params.put("token:$token")
            params.put(JSONArray().put(url))
            put("params", params)
        }.toString()

        val req = Request.Builder()
            .url(server.substringBefore("?"))
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
            
        try {
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) { false }
    }
}
