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

    // 注入的终极极限嗅探脚本
    val injectionScript = """
        (function() {
            // 1. 劫持 fetch
            var originalFetch = window.fetch;
            window.fetch = function() {
                var url = arguments[0];
                if (typeof url === 'string' && (url.includes('.mp4') || url.includes('.m3u8') || url.includes('.mp3') || url.includes('stream'))) {
                    window.AndroidBridge.onMediaFound(url, null);
                }
                return originalFetch.apply(this, arguments);
            };

            // 2. 劫持 XHR
            var originalXHR = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                if (typeof url === 'string' && (url.includes('.mp4') || url.includes('.m3u8') || url.includes('.mp3') || url.includes('stream'))) {
                    window.AndroidBridge.onMediaFound(url, null);
                }
                return originalXHR.apply(this, arguments);
            };

            // 3. 动态扫描 DOM (包含动态加载的 video/audio)
            setInterval(function() {
                var mediaTags = document.querySelectorAll('video, audio, source');
                for (var i = 0; i < mediaTags.length; i++) {
                    var src = mediaTags[i].src || mediaTags[i].currentSrc;
                    if (src) {
                        if (src.startsWith('blob:')) {
                            fetch(src).then(r => r.blob()).then(b => {
                                var reader = new FileReader();
                                reader.onload = function() {
                                    window.AndroidBridge.onBlobFound(reader.result.split(',')[1], b.type);
                                };
                                reader.readAsDataURL(b);
                            }).catch(e => {});
                        } else {
                            window.AndroidBridge.onMediaFound(src, null);
                        }
                    }
                }
            }, 2000);
        })();
    """.trimIndent()
}
