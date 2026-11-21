package com.downloadsource.web

import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class WebInterceptor {
    /** 深色注入自检失败回调（页面被反色反成空壳），由原生层把该站点加入豁免 */
    var onDarkFailed: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onDarkFailed(host: String?) {
        val h = host ?: return
        if (h.isBlank()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { onDarkFailed?.invoke(h) } }
    }

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

    /** 播放器上报时长（毫秒字符串，避免 JS number 类型转换问题） */
    @JavascriptInterface
    fun onMediaMeta(url: String?, durationMs: String?) {
        val u = url ?: return
        val d = durationMs?.toLongOrNull() ?: return
        com.downloadsource.core.MediaProbe.rememberDuration(u, d)
        _sniffedData.tryEmit(SniffedMedia(u, "video/*", u.startsWith("blob:")))
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
            var EXT = /(\.mp4|\.m4v|\.mkv|\.webm|\.mov|\.flv|\.avi|\.wmv|\.ogv|\.3gp|\.m2ts|\.mts|\.vob|\.rmvb|\.rm|\.asf|\.f4v|\.mpkg|\.pkg|\.apkm|\.apks|\.xapk|\.wgt|\.msix|\.dmg|\.deb|\.rpm|\.iso|\.img|\.torrent|\.zst|\.lz4|\.azw3?|\.mobi|\.epub|\.djvu|\.chm|\.srt|\.ass|\.vtt|\.m3u8|\.mpd|\.ts|\.mp3|\.m4a|\.m4b|\.aac|\.flac|\.wav|\.ogg|\.opus|\.wma|\.ape|\.ac3|\.amr|\.mka|\.tta|\.zip|\.rar|\.7z|\.tar|\.gz|\.xz|\.bz2|\.zst|\.lz4|\.cab|\.apk|\.xapk|\.apks|\.apkm|\.ipa|\.pdf|\.epub|\.mobi|\.azw3?|\.docx?|\.xlsx?|\.pptx?|\.csv|\.rtf|\.torrent|\.iso|\.img|\.dmg|\.exe|\.msi|\.deb|\.rpm|\.crx|\.bin)(\?|#|$)/i;
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
            // 一边播放一边上报：play / loadedmetadata / seeking 全部挂钩，
            // 很多站点的真实地址是无扩展名的接口，只靠正则永远抓不到，必须靠播放事件。
            function fire(el) {
                try {
                    var src = el.currentSrc || el.src || '';
                    if (!src) return;
                    if (src.indexOf('blob:') === 0) reportBlob(src);
                    else report(src, 'video/mp4');
                    var len = el.duration;
                    if (len && isFinite(len)) {
                        try { window.AndroidBridge.onMediaMeta(src, String(Math.round(len * 1000))); } catch (e) {}
                    }
                } catch (e) {}
            }
            function hookOne(el) {
                if (!el || el.__lmHooked) return;
                el.__lmHooked = 1;
                ['loadedmetadata', 'play', 'playing', 'seeking', 'durationchange', 'emptied', 'ended'].forEach(function(ev) {
                    el.addEventListener(ev, function() { fire(el); }, true);
                });
                fire(el);
            }
            function scanMedia() {
                var els = document.querySelectorAll('video,audio,source,track');
                for (var i = 0; i < els.length; i++) {
                    var src = els[i].currentSrc || els[i].src;
                    if (!src) continue;
                    if (src.indexOf('blob:') === 0) reportBlob(src); else report(src, 'video/mp4');
                    if (els[i].tagName === 'VIDEO' || els[i].tagName === 'AUDIO') hookOne(els[i]);
                }
            }
            // 页面中途插入的视频（懒加载 / 切换剧集）也要第一时间抓到
            try {
                document.addEventListener('play', function(ev) {
                    var t = ev.target;
                    if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) { hookOne(t); fire(t); }
                }, true);
                var mo = new MutationObserver(function(muts) {
                    for (var i = 0; i < muts.length; i++) {
                        var added = muts[i].addedNodes || [];
                        for (var j = 0; j < added.length; j++) {
                            var n = added[j];
                            if (!n || n.nodeType !== 1) continue;
                            if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO') hookOne(n);
                            if (n.querySelectorAll) {
                                var vs = n.querySelectorAll('video,audio');
                                for (var k = 0; k < vs.length; k++) hookOne(vs[k]);
                            }
                        }
                    }
                });
                mo.observe(document.documentElement, { childList: true, subtree: true });
            } catch (e) {}
            // 中英文双语链接扫描：下载/播放/高清 与 download/play/stream
            var KEYWORDS = /(下载|下载链接|立即下载|免费下载|播放|在线观看|高清|蓝光|音轨|字幕|视频|音频|附件|磁力|网盘|资源|download|Download|DOWNLOAD|play|Play|stream|Stream|video|Video|audio|Audio|media|Media|file|File|attachment|Attachment|episode|Episode|track|Track|save|Save)/;
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
            // 通用属性扫描：很多站点把真实地址放在 data-* / srcset / poster 上
            function scanAttrs() {
                try {
                    var els = document.querySelectorAll('[data-src],[data-url],[data-href],[data-video],[data-file],[data-play],[data-stream],[data-mp4],[data-m3u8],[data-download],[poster]');
                    for (var i = 0; i < els.length && i < 600; i++) {
                        var el = els[i];
                        ['data-src','data-url','data-href','data-video','data-file','data-play','data-stream','data-mp4','data-m3u8','data-download','poster','src'].forEach(function(a){
                            var v = el.getAttribute && el.getAttribute(a);
                            if (v && v.length > 6) report(v);
                        });
                    }
                } catch (e) {}
            }
            // 元信息扫描：og:video / twitter:player / itemprop contentUrl / JSON-LD
            function scanMeta() {
                try {
                    var metas = document.querySelectorAll('meta,link');
                    for (var i = 0; i < metas.length; i++) {
                        var m = metas[i];
                        var k = (m.getAttribute('property') || m.getAttribute('name') || m.getAttribute('itemprop') || m.getAttribute('rel') || '').toLowerCase();
                        if (k.indexOf('video') < 0 && k.indexOf('audio') < 0 && k.indexOf('stream') < 0 && k.indexOf('contenturl') < 0 && k.indexOf('embedurl') < 0 && k.indexOf('video_src') < 0) continue;
                        var v = m.getAttribute('content') || m.getAttribute('href');
                        if (v) report(v);
                    }
                    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
                    for (var j = 0; j < scripts.length; j++) {
                        try {
                            var o = JSON.parse(scripts[j].textContent || '{}');
                            var arr = Array.isArray(o) ? o : [o];
                            for (var a = 0; a < arr.length; a++) {
                                var it = arr[a] || {};
                                if (it.contentUrl) report(String(it.contentUrl));
                                if (it.embedUrl) report(String(it.embedUrl));
                            }
                        } catch (e) {}
                    }
                } catch (e) {}
            }
            var lastScan = 0;
            function scanText() {
                if (Date.now() - lastScan < 1200) return;
                lastScan = Date.now();
                try {
                    var txt = document.documentElement.innerHTML || '';
                    var re = /https?:\/\/[^\s"'<>\\]+?\.(mp4|m4v|mkv|webm|mov|flv|avi|m3u8|mpd|ts|mp3|m4a|aac|flac|wav|ogg|opus|zip|rar|7z|tar|gz|apk|xapk|apks|ipa|pdf|epub|mobi|docx|doc|xlsx|xls|pptx|ppt|torrent|iso|img|exe|msi)(?:[^\s"'<>\\]*)/gi;
                    var m, count = 0;
                    while ((m = re.exec(txt)) !== null && count < 80) { report(m[0]); count++; }
                } catch (e) {}
            }
            setInterval(function() { try { scanMedia(); scanText(); scanLinks(); scanAttrs(); scanMeta(); } catch (e) {} }, 1500);
            setInterval(function() {
                try {
                    var list = performance.getEntriesByType('resource');
                    for (var i = 0; i < list.length; i++) report(list[i].name);
                } catch (e) {}
            }, 3000);
        })();
    """.trimIndent()
}
