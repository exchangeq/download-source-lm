# ==========================================
# Download Source - Native Sniffer Rules (v2)
# 由 Kotlin 引擎解析（PyRuleEngine），只负责两件事：
#   1) 判断哪些 URL 属于可下载媒体/文件 → 进嗅探列表
#   2) 判断哪些 URL 属于广告/追踪 → 直接拦掉
# 规则为完整匹配（引擎内部按大小写不敏感处理）
# ==========================================
import re

# 需要放行的媒体资源正则
MEDIA_PATTERNS = [
    r".*\.mp4(\?.*)?$",
    r".*\.m4v(\?.*)?$",
    r".*\.mkv(\?.*)?$",
    r".*\.webm(\?.*)?$",
    r".*\.mov(\?.*)?$",
    r".*\.flv(\?.*)?$",
    r".*\.avi(\?.*)?$",
    r".*\.wmv(\?.*)?$",
    r".*\.3gp(\?.*)?$",
    r".*\.m2ts(\?.*)?$",
    r".*\.mts(\?.*)?$",
    r".*\.rmvb(\?.*)?$",
    r".*\.f4v(\?.*)?$",
    r".*\.m3u8(\?.*)?$",
    r".*\.m3u(\?.*)?$",
    r".*\.mpd(\?.*)?$",
    r".*\.m4s(\?.*)?$",
    r".*\.ts(\?.*)?$",
    r".*\.mp3(\?.*)?$",
    r".*\.m4a(\?.*)?$",
    r".*\.aac(\?.*)?$",
    r".*\.flac(\?.*)?$",
    r".*\.wav(\?.*)?$",
    r".*\.ogg(\?.*)?$",
    r".*\.opus(\?.*)?$",
    r".*\.ape(\?.*)?$",
    r".*\.wma(\?.*)?$",
    r".*\.mka(\?.*)?$",
    r".*\.zip(\?.*)?$",
    r".*\.rar(\?.*)?$",
    r".*\.7z(\?.*)?$",
    r".*\.tar(\?.*)?$",
    r".*\.gz(\?.*)?$",
    r".*\.xz(\?.*)?$",
    r".*\.apk(\?.*)?$",
    r".*\.apks(\?.*)?$",
    r".*\.xapk(\?.*)?$",
    r".*\.ipa(\?.*)?$",
    r".*\.exe(\?.*)?$",
    r".*\.msi(\?.*)?$",
    r".*\.dmg(\?.*)?$",
    r".*\.iso(\?.*)?$",
    r".*\.pdf(\?.*)?$",
    r".*\.epub(\?.*)?$",
    r".*\.docx?(\?.*)?$",
    r".*\.xlsx?(\?.*)?$",
    r".*\.pptx?(\?.*)?$",
    r".*m3u8.*",
    r".*\.m3u8.*",
    r".*\.mp4.*",
    r".*\.ts.*"
]

# 常见「无扩展名但有媒体特征」的直链（CDN 常见）
MEDIA_HINT_PATTERNS = [
    r".*(/|\.)vod[-_/].*",
    r".*(/|\.)video[/_-].*",
    r".*(/|\.)hls[/_-].*",
    r".*playlist.*\.(m3u8|mpd).*",
    r".*\/\d{3,}p\/.*\.(mp4|ts).*",
    r".*\.(m3u8|mpd|mp4|ts)\?.*token=.*"
]

# 需要拦截的广告/追踪域名
AD_BLOCK_PATTERNS = [
    r".*doubleclick\.net.*",
    r".*googlesyndication\.com.*",
    r".*googleadservices\.com.*",
    r".*cpro\.baidu\.com.*",
    r".*hm\.baidu\.com.*",
    r".*pos\.baidu\.com.*",
    r".*eclick\.baidu\.com.*",
    r".*tanx\.com.*",
    r".*alimama\.com.*",
    r".*umeng\.com.*",
    r".*cnzz\.com.*",
    r".*app-measurement\.com.*",
    r".*firebase-settings.*",
    r".*adsystem.*",
    r".*adservice.*",
    r".*\/adserver\/.*",
    r".*\/advertise\/.*",
    r".*adx\.*"
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
    for pattern in MEDIA_HINT_PATTERNS:
        if re.match(pattern, url):
            return True
    return False
