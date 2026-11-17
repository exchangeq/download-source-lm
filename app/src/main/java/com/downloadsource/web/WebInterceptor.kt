package com.downloadsource.web

import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class WebInterceptor {
    data class SniffedMedia(val url: String, val mime: String?, val isBlob: Boolean)

    private val _sniffedData = MutableSharedFlow<SniffedMedia>()
    val sniffedData: SharedFlow<SniffedMedia> = _sniffedData

    @JavascriptInterface
    fun onMediaFound(url: String, mime: String?) {
        if (url.isBlank()) return
        // 过滤掉无关的 icon
        if (url.contains("favicon") || url.contains(".svg") || url.contains(".gif")) return
        _sniffedData.tryEmit(SniffedMedia(url, mime, url.startsWith("blob:")))
    }

    @JavascriptInterface
    fun onBlobFound(base64Data: String, mime: String) {
        if (base64Data.isBlank()) return
        val url = "data:$mime;base64,$base64Data"
        _sniffedData.tryEmit(SniffedMedia(url, mime, true))
    }

    // 极限嗅探：fetch / XHR / 播放器 / 页面文本 / resource timing 五路并进
    val injectionScript = """
        (function() {
            if (window.__lmSniffInstalled) return;
            window.__lmSniffInstalled = true;
            var EXT = /(\.mp4|\.m4v|\.mkv|\.webm|\.mov|\.flv|\.avi|\.wmv|\.ogv|\.3gp|\.m3u8|\.mpd|\.ts|\.mp3|\.m4a|\.aac|\.flac|\.wav|\.ogg|\.opus|\.wma|\.ape|\.zip|\.rar|\.7z|\.tar|\.gz|\.xz|\.bz2|\.zst|\.apk|\.xapk|\.apks|\.ipa|\.pdf|\.epub|\.mobi|\.docx?|\.xlsx?|\.pptx?|\.csv|\.torrent|\.iso|\.dmg|\.exe|\.msi)(\?|#|$)/i;
            var seen = {};
            function report(u, mime) {
                if (!u || typeof u !== 'string') return;
                if (u.indexOf('data:') === 0) return;
                if (!EXT.test(u) && !(mime && (/^(video|audio)\//.test(mime) || /mpegurl|dash\+xml/.test(mime)))) return;
                if (seen[u]) return;
                seen[u] = 1;
                try { window.AndroidBridge.onMediaFound(u, mime || null); } catch (e) {}
            }
            var of = window.fetch;
            window.fetch = function(input) {
                try { report(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
                var p = of.apply(this, arguments);
                try { p.then(function(r) { try { report(r.url, r.headers.get('content-type')); } catch (e) {} }); } catch (e) {}
                return p;
            };
            var oo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u) {
                try { report(u); } catch (e) {}
                return oo.apply(this, arguments);
            };
            var os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function() {
                var xhr = this;
                xhr.addEventListener('load', function() {
                    try { report(xhr.responseURL, xhr.getResponseHeader('content-type')); } catch (e) {}
                });
                return os.apply(this, arguments);
            };
            function reportBlob(u) {
                if (seen[u]) return;
                seen[u] = 1;
                try {
                    fetch(u).then(function(r) { return r.blob(); }).then(function(b) {
                        if (b.size > 120 * 1024 * 1024) return;
                        var fr = new FileReader();
                        fr.onload = function() {
                            try { window.AndroidBridge.onBlobFound(fr.result.split(',')[1], b.type); } catch (e) {}
                        };
                        fr.readAsDataURL(b);
                    }).catch(function() {});
                } catch (e) {}
            }
            function scanMedia() {
                var els = document.querySelectorAll('video,audio,source,track');
                for (var i = 0; i < els.length; i++) {
                    var src = els[i].currentSrc || els[i].src;
                    if (!src) continue;
                    if (src.indexOf('blob:') === 0) reportBlob(src); else report(src);
                }
            }
            // 中英文双语链接扫描：下载/播放/高清 与 download/play/stream
            var KEYWORDS = /(下载|播放|高清|音轨|视频|音频|附件|download|Download|DOWNLOAD|play|Play|stream|Stream|video|audio|media|file)/;
            function scanLinks() {
                try {
                    var as = document.querySelectorAll('a,button,[role="button"]');
                    for (var i = 0; i < as.length && i < 400; i++) {
                        var el = as[i];
                        var label = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || '');
                        if (!KEYWORDS.test(label)) continue;
                        var href = el.href || el.getAttribute('data-href') || el.getAttribute('data-url') || el.getAttribute('data-src');
                        if (href) report(href);
                    }
                } catch (e) {}
            }
            var lastScan = 0;
            function scanText() {
                if (Date.now() - lastScan < 3000) return;
                lastScan = Date.now();
                try {
                    var txt = document.documentElement.innerHTML || '';
                    var re = /https?:\/\/[^\s"'<>\\]+?\.(mp4|m3u8|mp3|m4a|mkv|webm|flv|mov|ts|zip|rar|apk|pdf)(?:[^\s"'<>\\]*)/gi;
                    var m, count = 0;
                    while ((m = re.exec(txt)) !== null && count < 80) { report(m[0]); count++; }
                } catch (e) {}
            }
            setInterval(function() { try { scanMedia(); scanText(); scanLinks(); } catch (e) {} }, 1500);
            setInterval(function() {
                try {
                    var list = performance.getEntriesByType('resource');
                    for (var i = 0; i < list.length; i++) report(list[i].name);
                } catch (e) {}
            }, 3000);
        })();
    """.trimIndent()
}
