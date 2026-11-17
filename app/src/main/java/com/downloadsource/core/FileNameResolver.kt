package com.downloadsource.core

import android.util.Base64
import java.net.URLDecoder

/**
 * 文件名解析：Content-Disposition / URL / Content-Type 三路兜底。
 * 解决中文乱码、双编码、RFC2047、引号包裹、无扩展名、八种以上压缩/音视频格式识别。
 */
object FileNameResolver {

    private const val MAX_LEN = 120

    private val MIME_EXT = mapOf(
        // 压缩包
        "application/zip" to ".zip",
        "application/x-zip-compressed" to ".zip",
        "application/x-rar-compressed" to ".rar",
        "application/vnd.rar" to ".rar",
        "application/x-7z-compressed" to ".7z",
        "application/x-tar" to ".tar",
        "application/gzip" to ".gz",
        "application/x-gzip" to ".gz",
        "application/x-xz" to ".xz",
        "application/x-bzip2" to ".bz2",
        // 安装包
        "application/vnd.android.package-archive" to ".apk",
        "application/vnd.android.package-archive+zip" to ".apk",
        "application/x-ios-app" to ".ipa",
        "application/x-msdownload" to ".exe",
        "application/x-msi" to ".msi",
        "application/x-apple-diskimage" to ".dmg",
        "application/x-iso9660-image" to ".iso",
        // 文档
        "application/pdf" to ".pdf",
        "application/epub+zip" to ".epub",
        "application/x-mobipocket-ebook" to ".mobi",
        "application/msword" to ".doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ".docx",
        "application/vnd.ms-excel" to ".xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx",
        "application/vnd.ms-powerpoint" to ".ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx",
        "application/json" to ".json",
        "application/xml" to ".xml", "text/xml" to ".xml",
        "text/csv" to ".csv",
        "text/plain" to ".txt",
        // 视频
        "video/mp4" to ".mp4",
        "video/x-m4v" to ".m4v",
        "video/x-matroska" to ".mkv",
        "video/webm" to ".webm",
        "video/x-msvideo" to ".avi",
        "video/quicktime" to ".mov",
        "video/x-flv" to ".flv",
        "video/mp2t" to ".ts",
        "video/ogg" to ".ogv",
        "application/vnd.apple.mpegurl" to ".m3u8", "application/x-mpegurl" to ".m3u8", "audio/mpegurl" to ".m3u8", "audio/x-mpegurl" to ".m3u8",
        "application/dash+xml" to ".mpd",
        // 音频
        "audio/mpeg" to ".mp3",
        "audio/mp4" to ".m4a", "audio/x-m4a" to ".m4a",
        "audio/aac" to ".aac",
        "audio/flac" to ".flac", "audio/x-flac" to ".flac",
        "audio/wav" to ".wav", "audio/x-wav" to ".wav",
        "audio/ogg" to ".ogg",
        "audio/opus" to ".opus",
        "audio/x-ms-wma" to ".wma",
        "application/octet-stream" to ".bin"
    )

    private val KNOWN_EXT = setOf(
        // 压缩
        "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "zst", "cab", "iso",
        // 安装
        "apk", "xapk", "apks", "aab", "ipa", "exe", "msi", "dmg", "deb", "rpm",
        // 文档
        "pdf", "epub", "mobi", "azw3", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "txt", "csv", "json", "xml", "md", "srt", "ass",
        // 视频
        "mp4", "mkv", "webm", "avi", "mov", "flv", "m4v", "ts", "m3u8", "mpd", "ogv", "wmv", "rmvb", "3gp",
        // 音频
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "ape",
        // 图片
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic",
        // 其它
        "torrent", "bin", "dat", "db"
    )

    /** 主入口：依次尝试 响应头 -> URL -> Content-Type */
    fun resolve(url: String, disposition: String?, contentType: String?): String? {
        val fromHeader = fromDisposition(disposition)
        if (!fromHeader.isNullOrBlank()) return sanitize(fromHeader, url, contentType)

        val fromUrl = fromUrl(url)
        if (!fromUrl.isNullOrBlank()) return sanitize(fromUrl, url, contentType)

        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        val ext = mime?.let { MIME_EXT[it] }
        if (ext != null && ext != ".bin") return "file_${System.currentTimeMillis()}$ext"
        return null
    }

    private fun fromDisposition(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // 1) RFC 5987/6266：filename*=UTF-8''%E4%B8%AD.zip 或 filename*=GBK''...
        Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.let { v ->
            val value = v.trim().trim('"')
            val charset = value.substringBefore("''", "").trim().ifBlank { "UTF-8" }
            val encoded = value.substringAfter("''", value)
            decodePercent(encoded, charset)?.let { if (it.isNotBlank()) return cleanQuotes(it) }
        }
        // 2) 普通 filename="..."，可能带分号、带引号、被百分号编码
        Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.let { v ->
            decodePercent(v, "UTF-8")?.let { if (it.isNotBlank()) return cleanQuotes(it) }
        }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.let { v ->
            decodePercent(v.trim(), "UTF-8")?.let { if (it.isNotBlank()) return cleanQuotes(it) }
        }
        return null
    }

    private fun fromUrl(url: String): String? {
        val path = runCatching { java.net.URI(url).rawPath }.getOrNull()
            ?: url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (last.isNotBlank()) {
            val decoded = decodePercent(last, "UTF-8") ?: last
            val ext = decoded.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && (ext in KNOWN_EXT || ext.length in 2..5)) return decoded
            if (decoded.contains('.') && decoded.length > 3) return decoded
        }
        // URL 里带 ?file=xxx.zip / ?filename= 的站点
        val query = url.substringAfter('?', "").substringBefore('#')
        query.split('&').forEach { pair ->
            val k = pair.substringBefore('=').lowercase()
            if (k in setOf("file", "filename", "name", "download", "dl", "fname")) {
                val v = decodePercent(pair.substringAfter('=', ""), "UTF-8") ?: return@forEach
                if (v.isNotBlank() && v.contains('.')) return v.substringAfterLast('/')
            }
        }
        return null
    }

    /** 百分号解码；UTF-8 出现替换字符时回退 GBK，再回退 ISO-8859-1 救 mojibake */
    private fun decodePercent(raw: String, charset: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        // RFC 2047：=?UTF-8?B?5Lit5paH?=
        Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=").find(text)?.let { m ->
            val cs = m.groupValues[1].substringBefore('*')
            val enc = m.groupValues[2].uppercase()
            val payload = m.groupValues[3]
            val decoded = runCatching {
                if (enc == "B") String(Base64.decode(payload, Base64.DEFAULT), charset(cs))
                else payload.replace('_', ' ').let { q -> URLDecoder.decode(q, charset(cs)) }
            }.getOrNull()
            if (!decoded.isNullOrBlank()) text = decoded
        }
        val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull() ?: return text
        if (decoded.contains('\uFFFD')) {
            runCatching { URLDecoder.decode(text, "GBK") }.getOrNull()?.let { if (!it.contains('\uFFFD')) return it }
        }
        // ISO-8859-1 被当成 UTF-8 的经典乱码（Ã¢â‚¬â€œ 之类）
        if (decoded.contains('Ã') || decoded.contains('â')) {
            runCatching {
                String(decoded.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            }.getOrNull()?.let { if (!it.contains('\uFFFD')) return it }
        }
        return decoded
    }

    private fun charset(name: String) = runCatching { java.nio.charset.Charset.forName(name) }
        .getOrDefault(Charsets.UTF_8)

    private fun cleanQuotes(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').trim().trim('"', '\'', ' ')

    /** 兜底扩展名：Content-Type 决定；没有就用 URL 里的 */
    private fun sanitize(raw: String, url: String, contentType: String?): String {
        var name = cleanQuotes(raw)
        // 去掉 Windows/Unix 非法字符与控制符
        name = name.replace(Regex("[\\p{Cntrl}/:*?\"<>|\\\\]"), "_")
        name = name.replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") {
            name = "file_${System.currentTimeMillis()}"
        }
        // 没有扩展名时按 Content-Type / URL 补
        var ext = name.substringAfterLast('.', "")
        if (ext.isEmpty() || ext.length > 6 || ext.contains(' ')) {
            val mimeExt = contentType?.substringBefore(';')?.trim()?.lowercase()?.let { MIME_EXT[it] }
            val urlExt = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
                .substringAfterLast('.', "").lowercase()
            val add = mimeExt ?: if (urlExt in KNOWN_EXT) ".$urlExt" else ""
            if (add.isNotEmpty() && add != ".bin") name = "$name$add"
            ext = name.substringAfterLast('.', "")
        }
        if (name.length > MAX_LEN) {
            val base = name.substringBeforeLast('.', name)
            name = base.take(MAX_LEN - ext.length - 1) + if (ext.isNotEmpty() && ext.length <= 6) ".$ext" else ""
        }
        return name
    }
}
