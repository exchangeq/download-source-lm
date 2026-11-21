package com.downloadsource.web

/** 浏览器额外注入脚本 */
object WebScripts {
    /** 补 viewport：桌面页/老页面没有 meta viewport 时，页面会按 980px 排版导致右半截被裁切 */
    val ensureViewport = """
        (function(){
            try {
                var m = document.querySelector('meta[name=viewport]');
                var wide = (document.documentElement.scrollWidth || 0) > Math.max(window.innerWidth * 1.35, 400);
                if (!m && wide) {
                    m = document.createElement('meta');
                    m.name = 'viewport';
                    m.content = 'width=device-width, initial-scale=1.0';
                    document.head.appendChild(m);
                }
                // 小功能①：双击页面空白处回到顶部（移动端双击不缩放，随手一按就能回顶）
                if (!window.__lmDblClickTop) {
                    window.__lmDblClickTop = true;
                    document.addEventListener('dblclick', function(ev){
                        try {
                            var t = ev.target;
                            if (t && /^(A|BUTTON|INPUT|TEXTAREA|VIDEO|SELECT)$/.test(t.tagName)) return;
                            window.scrollTo({ top: 0, behavior: 'smooth' });
                        } catch (e) {}
                    }, true);
                }
            } catch (e) {}
        })();
    """.trimIndent()


    /**
     * 网页深色模式（JS 实现，替代系统 forceDark）
     * 系统 forceDark 会把已经是深色主题的站点二次反色，结果整页黑成一块、文字全看不见。
     * 这里先测页面自身背景亮度：本来就是深色页面直接跳过；浅色页面再反色，图片/视频二次反色还原。
     * 应用后再自检一次，异常就回滚并通过 AndroidBridge.onDarkFailed(host) 让原生层永久豁免该站点。
     */
    fun darkMode(enabled: Boolean): String = """
        (function(on){
            try {
                var ID = '__lm_dark_style__';
                var old = document.getElementById(ID);
                if (old && old.parentNode) old.parentNode.removeChild(old);
                var root = document.documentElement;
                try { root.style.removeProperty('filter'); root.style.removeProperty('background'); } catch (e) {}
                window.__lmDarkOn = false;
                if (!on) return;
                var body = document.body || root;
                function lum(c) {
                    var m = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+))?/.exec(c || '');
                    if (!m) return null;
                    if (m[4] !== undefined && parseFloat(m[4]) < 0.5) return null;
                    return (0.299 * (+m[1]) + 0.587 * (+m[2]) + 0.114 * (+m[3])) / 255;
                }
                var bgL = lum(getComputedStyle(body).backgroundColor);
                if (bgL === null) bgL = lum(getComputedStyle(root).backgroundColor);
                // 页面本身就是深色主题：不再反转（否则深底深字，整页全黑）
                if (bgL !== null && bgL < 0.42) return;
                var st = document.createElement('style');
                st.id = ID;
                st.textContent =
                    'html{filter:invert(0.92) hue-rotate(180deg) !important;background:#101010 !important}' +
                    'body{background:#121212 !important;color:#e9e9e9 !important}' +
                    'img,video,canvas,svg,picture,iframe,embed,object{filter:invert(1) hue-rotate(180deg) !important}' +
                    'input,textarea,select{background:#1c1c1c !important;color:#eee !important}' +
                    'a{color:#7fb6ff !important}';
                (document.head || root).appendChild(st);
                window.__lmDarkOn = true;
                setTimeout(function(){
                    try {
                        var txt = (body.innerText || '').trim().length;
                        var media = body.querySelectorAll('img,video,canvas').length;
                        // 反色后既没有文字也没有图片：大概率是被反色搞坏的空壳，回滚并上报豁免
                        if (txt === 0 && media === 0) {
                            if (st.parentNode) st.parentNode.removeChild(st);
                            window.__lmDarkOn = false;
                            try { window.AndroidBridge.onDarkFailed(location.hostname); } catch (e) {}
                        }
                    } catch (e) {}
                }, 500);
            } catch (e) {}
        })(__ON__);
    """.trimIndent().replace("__ON__", if (enabled) "true" else "false")

    /** 阅读模式：剥离导航/侧栏/广告，正文聚焦排版；再执行一次即还原 */
    val readerMode = """
        (function() {
            if (window.__lmReader) { window.__lmReader = false; location.reload(); return; }
            window.__lmReader = true;
            var drop = ['nav','aside','footer','header','form','iframe'];
            for (var i = 0; i < drop.length; i++) {
                var els = document.querySelectorAll(drop[i]);
                for (var j = 0; j < els.length; j++) els[j].style.display = 'none';
            }
            document.body.style.background = '#f5f5f5';
            var best = null, bestSize = 0;
            var nodes = document.querySelectorAll('article, main, .content, #content, .post, div');
            for (var k = 0; k < nodes.length; k++) {
                var t = nodes[k].innerText ? nodes[k].innerText.length : 0;
                if (t > bestSize) { bestSize = t; best = nodes[k]; }
            }
            if (best && bestSize > 200) {
                var html = best.innerHTML;
                document.body.innerHTML = '<div style="max-width:720px;margin:0 auto;padding:24px;line-height:1.9;font-size:18px;color:#222;">' + html + '</div>';
            }
            document.body.style.fontSize = '18px';
        })();
    """.trimIndent()
}
