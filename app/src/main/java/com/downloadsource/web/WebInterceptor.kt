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
            var EXT = /(\.mp4|\.m3u8|\.mp3|\.m4a|\.aac|\.flac|\.wav|\.mkv|\.webm|\.flv|\.mov|\.ts|\.zip|\.rar|\.7z|\.apk|\.pdf)(\?|#|$)/i;
            var seen = {};
            function report(u, mime) {
                if (!u || typeof u !== 'string') return;
                if (u.indexOf('data:') === 0) return;
                if (!EXT.test(u) && !(mime && /^(video|audio)\//.test(mime))) return;
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
            setInterval(function() { try { scanMedia(); scanText(); } catch (e) {} }, 1500);
            setInterval(function() {
                try {
                    var list = performance.getEntriesByType('resource');
                    for (var i = 0; i < list.length; i++) report(list[i].name);
                } catch (e) {}
            }, 3000);
        })();
    """.trimIndent()
}
