#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <zlib.h>
#include <cstring>
#include <string>
#include <vector>

#define TAG "ZeroCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_downloadsource_core_ZeroCore_nativeFallocate(JNIEnv* env, jobject, jstring path, jlong size) {
    if (path == nullptr) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_WRONLY | O_CREAT, 0644);
    env->ReleaseStringUTFChars(path, p);

    if (fd < 0) return JNI_FALSE;

    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size >= size) {
        close(fd);
        return JNI_TRUE;
    }

    int ret = posix_fallocate(fd, 0, (off_t)size);
    close(fd);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_downloadsource_core_ZeroCore_nativeMergeFiles(
        JNIEnv* env, jobject, jobjectArray paths, jstring output) {
    if (paths == nullptr || output == nullptr) return JNI_FALSE;

    const char* outPath = env->GetStringUTFChars(output, nullptr);
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    env->ReleaseStringUTFChars(output, outPath);

    if (outFd < 0) return JNI_FALSE;

    // 性能提升：2MB 超级大缓冲
    constexpr size_t BUF_SIZE = 2 * 1024 * 1024; 
    std::vector<char> buf(BUF_SIZE);
    jsize n = env->GetArrayLength(paths);
    bool ok = true;

    for (jsize i = 0; i < n && ok; ++i) {
        auto jp = (jstring) env->GetObjectArrayElement(paths, i);
        const char* p = env->GetStringUTFChars(jp, nullptr);
        int inFd = open(p, O_RDONLY);

        env->ReleaseStringUTFChars(jp, p);
        env->DeleteLocalRef(jp); 

        if (inFd < 0) { ok = false; break; }

        ssize_t r;
        while ((r = read(inFd, buf.data(), BUF_SIZE)) > 0) {
            ssize_t written = 0;
            while (written < r) {
                ssize_t w = write(outFd, buf.data() + written, r - written);
                if (w <= 0) { ok = false; break; }
                written += w;
            }
            if (!ok) break;
        }
        close(inFd);
    }

    if (ok) fsync(outFd);
    close(outFd);

    if (!ok) {
        const char* p = env->GetStringUTFChars(output, nullptr);
        unlink(p);
        env->ReleaseStringUTFChars(output, p);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_downloadsource_core_ZeroCore_nativeCrc32(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return -1;
    const char* p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDONLY);
    env->ReleaseStringUTFChars(path, p);

    if (fd < 0) return -1;

    uLong crc = crc32(0L, Z_NULL, 0);
    // 性能提升：1MB CRC Buffer
    constexpr size_t BUF_SIZE = 1024 * 1024;
    std::vector<char> buf(BUF_SIZE);
    ssize_t r;

    while ((r = read(fd, buf.data(), BUF_SIZE)) > 0) {
        crc = crc32(crc, (const Bytef*) buf.data(), (uInt) r);
    }
    close(fd);
    return (jlong) crc;
}


// ===== 原生格式嗅探（C 实现）：魔数 + 容器特征，供 Java 层兜底 =====
struct Sig { const char* ext; int off; const unsigned char* pat; int len; };

static const unsigned char P_PDF[]   = {0x25,0x50,0x44,0x46};
static const unsigned char P_RAR[]   = {0x52,0x61,0x72,0x21};
static const unsigned char P_7Z[]    = {0x37,0x7A,0xBC,0xAF};
static const unsigned char P_GZ[]    = {0x1F,0x8B};
static const unsigned char P_BZ2[]   = {0x42,0x5A,0x68};
static const unsigned char P_XZ[]    = {0xFD,0x37,0x7A,0x58};
static const unsigned char P_ZST[]   = {0x28,0xB5,0x2F,0xFD};
static const unsigned char P_ZIP[]   = {0x50,0x4B,0x03,0x04};
static const unsigned char P_XAR[]   = {0x78,0x61,0x72,0x21};  // .mpkg / .pkg (macOS)
static const unsigned char P_PNG[]   = {0x89,0x50,0x4E,0x47};
static const unsigned char P_JPG[]   = {0xFF,0xD8,0xFF};
static const unsigned char P_GIF[]   = {0x47,0x49,0x46,0x38};
static const unsigned char P_BMP[]   = {0x42,0x4D};
static const unsigned char P_TIFF1[] = {0x49,0x49,0x2A,0x00};
static const unsigned char P_TIFF2[] = {0x4D,0x4D,0x00,0x2A};
static const unsigned char P_ICO[]   = {0x00,0x00,0x01,0x00};
static const unsigned char P_PSD[]   = {0x38,0x42,0x50,0x53};
static const unsigned char P_ID3[]   = {0x49,0x44,0x33};
static const unsigned char P_FLAC[]  = {0x66,0x4C,0x61,0x43};
static const unsigned char P_OGG[]   = {0x4F,0x67,0x67,0x53};
static const unsigned char P_RIFF[]  = {0x52,0x49,0x46,0x46};
static const unsigned char P_MKV[]   = {0x1A,0x45,0xDF,0xA3};
static const unsigned char P_FLV[]   = {0x46,0x4C,0x56};
static const unsigned char P_ELF[]   = {0x7F,0x45,0x4C,0x46};
static const unsigned char P_CLASS[] = {0xCA,0xFE,0xBA,0xBE};
static const unsigned char P_SQLITE[]= {0x53,0x51,0x4C,0x69};
static const unsigned char P_TTF[]   = {0x00,0x01,0x00,0x00};
static const unsigned char P_OTF[]   = {0x4F,0x54,0x54,0x4F};
static const unsigned char P_TTC[]   = {0x74,0x74,0x63,0x66};
static const unsigned char P_PE[]    = {0x4D,0x5A};
static const unsigned char P_AMR[]   = {0x23,0x21,0x41,0x4D};
static const unsigned char P_ISO[]   = {0x43,0x44,0x30,0x30,0x31}; // CD001
static const unsigned char P_WASM[]  = {0x00,0x61,0x73,0x6D};
static const unsigned char P_DEX[]   = {0x64,0x65,0x78,0x0A};

extern "C" JNIEXPORT jstring JNICALL
Java_com_downloadsource_core_ZeroCore_nativeSniffExt(JNIEnv* env, jobject, jbyteArray head) {
    if (head == nullptr) return nullptr;
    jsize n = env->GetArrayLength(head);
    if (n < 8) return nullptr;
    std::vector<unsigned char> b((size_t)n);
    env->GetByteArrayRegion(head, 0, n, (jbyte*)b.data());

    auto match = [&](int off, const unsigned char* pat, int len) -> bool {
        if (off + len > n) return false;
        return memcmp(b.data() + off, pat, (size_t)len) == 0;
    };

    const char* ext = nullptr;
    // 先判 RIFF 子类型，再判通用魔数
    if (match(0, P_RIFF, 4)) {
        if (n >= 12 && b[8]==0x57 && b[9]==0x45 && b[10]==0x42 && b[11]==0x50) ext = "webp";
        else if (n >= 12 && b[8]==0x41 && b[9]==0x56) ext = "avi";
        else if (n >= 12 && b[8]==0x57 && b[9]==0x41) ext = "wav";
        else ext = "wav";
    }
    else if (match(0, P_XAR, 4)) ext = "mpkg";
    else if (match(0, P_7Z, 6)) ext = "7z";
    else if (match(0, P_ZIP, 4)) ext = "zip";
    else if (match(0, P_RAR, 4)) ext = "rar";
    else if (match(0, P_PDF, 4)) ext = "pdf";
    else if (match(0, P_GZ, 2)) ext = "gz";
    else if (match(0, P_BZ2, 3)) ext = "bz2";
    else if (match(0, P_XZ, 4)) ext = "xz";
    else if (match(0, P_ZST, 4)) ext = "zst";
    else if (match(0, P_PNG, 4)) ext = "png";
    else if (match(0, P_JPG, 3)) ext = "jpg";
    else if (match(0, P_GIF, 4)) ext = "gif";
    else if (match(0, P_BMP, 2)) ext = "bmp";
    else if (match(0, P_TIFF1, 4) || match(0, P_TIFF2, 4)) ext = "tiff";
    else if (match(0, P_ICO, 4)) ext = "ico";
    else if (match(0, P_PSD, 4)) ext = "psd";
    else if (match(0, P_ID3, 3)) ext = "mp3";
    else if (match(0, P_FLAC, 4)) ext = "flac";
    else if (match(0, P_OGG, 4)) ext = "ogg";
    else if (match(0, P_MKV, 4)) {
        // EBML：docType 里区分 webm / mkv
        ext = "mkv";
    }
    else if (match(0, P_FLV, 3)) ext = "flv";
    else if (match(0, P_ELF, 4)) ext = "elf";
    else if (match(0, P_CLASS, 4)) ext = "class";
    else if (match(0, P_DEX, 4)) ext = "dex";
    else if (match(0, P_SQLITE, 4)) ext = "sqlite";
    else if (match(0, P_TTF, 4)) ext = "ttf";
    else if (match(0, P_OTF, 4)) ext = "otf";
    else if (match(0, P_TTC, 4)) ext = "ttc";
    else if (match(0, P_PE, 2)) ext = "exe";
    else if (match(0, P_AMR, 4)) ext = "amr";
    else if (match(0, P_WASM, 4)) ext = "wasm";
    // ISO / UDF 镜像：CD001 在 0x8001，这里用 8 字节头兜底判断
    else if (n >= 8 && b[4]==0x66 && b[5]==0x74 && b[6]==0x79 && b[7]==0x70) {
        ext = "mp4"; // ftyp 盒
    }

    return ext ? env->NewStringUTF(ext) : nullptr;
}

// 容器内二次探测：读文件尾部中央目录，判断 zip 家族的真正类型
extern "C" JNIEXPORT jstring JNICALL
Java_com_downloadsource_core_ZeroCore_nativeProbeZipKind(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return nullptr;
    const char* p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDONLY);
    env->ReleaseStringUTFChars(path, p);
    if (fd < 0) return nullptr;

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 22) { close(fd); return nullptr; }

    // 扫尾部 128KB 找 EOCD，再读中央目录里的文件名做特征判断
    const off_t tail = 128 * 1024;
    off_t start = st.st_size > tail ? st.st_size - tail : 0;
    std::vector<unsigned char> buf((size_t)(st.st_size - start));
    lseek(fd, start, SEEK_SET);
    ssize_t r = read(fd, buf.data(), buf.size());
    lseek(fd, 0, SEEK_SET);
    close(fd);
    if (r <= 0) return nullptr;

    std::string data((char*)buf.data(), (size_t)r);
    std::string kind;
    if (data.find("AndroidManifest.xml") != std::string::npos) kind = "apk";
    else if (data.find("mimetype") != std::string::npos && data.find("application/epub+zip") != std::string::npos) kind = "epub";
    else if (data.find("word/document.xml") != std::string::npos) kind = "docx";
    else if (data.find("xl/workbook.xml") != std::string::npos) kind = "xlsx";
    else if (data.find("ppt/presentation.xml") != std::string::npos) kind = "pptx";
    else if (data.find("payload/") != std::string::npos || data.find("manifest.json") != std::string::npos) kind = "apkm";
    else if (data.find("package.json") != std::string::npos) kind = "mpkg";
    else if (data.find("comicbook.xml") != std::string::npos) kind = "cbz";

    return kind.empty() ? nullptr : env->NewStringUTF(kind.c_str());
}


// ===== C 渲染计算层：整页适配缩放 + RGB 像素变换 =====
extern "C" JNIEXPORT jint JNICALL
Java_com_downloadsource_core_ZeroCore_nativeFitScale(JNIEnv*, jobject, jint contentW, jint deviceW) {
    if (contentW <= 0 || deviceW <= 0) return 100;
    // 内容宽超出屏幕时，算出能整页塞进屏幕的缩放百分比（10%~100%）
    int scale = (int)((double)deviceW * 100.0 / (double)contentW);
    if (scale > 100) scale = 100;
    if (scale < 10) scale = 10;
    return scale;
}

// RGB565 位图压暗（网页强制深色在截图/长截图导出时用，纯 C 逐像素，避免 JS 重排）
extern "C" JNIEXPORT void JNICALL
Java_com_downloadsource_core_ZeroCore_nativeDarkenRgb565(JNIEnv* env, jobject, jshortArray pixels, jint count, jint factor) {
    if (pixels == nullptr || count <= 0) return;
    jshort* data = env->GetShortArrayElements(pixels, nullptr);
    if (data == nullptr) return;
    int f = factor < 0 ? 0 : (factor > 100 ? 100 : factor);
    for (int i = 0; i < count; i++) {
        unsigned short p = (unsigned short)data[i];
        int r = (p >> 11) & 0x1F;
        int g = (p >> 5) & 0x3F;
        int b = p & 0x1F;
        r = (r * f) / 100;
        g = (g * f) / 100;
        b = (b * f) / 100;
        data[i] = (jshort)((r << 11) | (g << 5) | b);
    }
    env->ReleaseShortArrayElements(pixels, data, 0);
}
