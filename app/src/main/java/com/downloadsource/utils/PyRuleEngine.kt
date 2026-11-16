package com.downloadsource.utils
import android.content.Context
import java.util.regex.Pattern
object PyRuleEngine {
    private val mediaPatterns = mutableListOf<Pattern>()
    private val adPatterns = mutableListOf<Pattern>()
    fun init(context: Context) {
        try {
            val inputStream = context.assets.open("sniffer_engine.py")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
            var line: String?; var mode = 0
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.startsWith("MEDIA_PATTERNS")) mode = 1
                else if (l.startsWith("AD_BLOCK_PATTERNS")) mode = 2
                else if (l.startsWith("]")) mode = 0
                else if (l.startsWith("r\"")) {
                    val regex = l.substringAfter("r\"").substringBefore("\"")
                    if (mode == 1) mediaPatterns.add(Pattern.compile(regex))
                    if (mode == 2) adPatterns.add(Pattern.compile(regex))
                }
            }
            reader.close()
        } catch (e: Exception) { }
    }
    fun shouldBlock(url: String): Boolean = adPatterns.any { it.matcher(url).matches() }
    fun shouldSniff(url: String): Boolean = mediaPatterns.any { it.matcher(url).matches() }
}
