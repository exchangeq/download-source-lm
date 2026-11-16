package com.downloadsource.web

/** 浏览器额外注入脚本 */
object WebScripts {
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
