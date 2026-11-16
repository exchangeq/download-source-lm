# ==========================================
# Download Source - Native Sniffer Rules
# ==========================================
# 此文件由 Kotlin 引擎解析，用于精准拦截网络请求
import re

# 需要放行的媒体资源正则
MEDIA_PATTERNS = [
    r".*\.mp4.*",
    r".*\.m3u8.*",
    r".*\.mp3.*",
    r".*\.apk.*",
    r".*\.zip.*",
    r".*\.ts.*"
]

# 需要拦截的广告/追踪域名
AD_BLOCK_PATTERNS = [
    r".*doubleclick\.net.*",
    r".*googlesyndication\.com.*",
    r".*cpro\.baidu\.com.*",
    r".*hm\.baidu\.com.*"
]

def should_block(url):
    for pattern in AD_BLOCK_PATTERNS:
        if re.match(pattern, url):
            return True
    return False

def should_sniff(url):
    for pattern in MEDIA_PATTERNS:
        if re.match(pattern, url):
            return True
    return False
