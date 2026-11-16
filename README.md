# LM Downloader

基于 [Maxx-EX/download-source](https://github.com/Maxx-EX/download-source) 重构的独立衍生版，已获原作者授权。署名 **LM**，使用独立包名与自有签名，可与原版共存安装。

## 与上游的关系

- 上游版本：v6.4.0（Apache-2.0）
- 本仓库：独立维护的衍生分支，保留上游全部功能，逐条改动记录见 [CHANGELOG.md](CHANGELOG.md)
- 原作者署名与授权说明见 [NOTICE](NOTICE)，许可证见 [LICENSE](LICENSE)

## 功能

- 多线程分片下载 + Zero-core（NDK）原生合并，可暂停、断点续传
- 内置浏览器，自动过滤百度系跳转与常见广告跳转
- 自定义下载目录（SAF）
- GitHub 镜像加速（默认直连，仅在显式启用前缀型代理时改写）
- 任务持久化，前台服务保活，通知栏进度
- 六套渐变主题，深浅两态

## 安装包

- 正式通道：`com.lm.downloader`，应用名「LM 下载器」
- 内测通道：`com.lm.downloader.beta`，应用名「LM 下载器 Beta」

APK 由 GitHub Actions 使用本仓库密钥签名，"Releases" 页面可直接下载。

## 构建

```bash
# 在仓库根目录
gradle :app:assembleOmegaRelease
```

签名需要以下环境变量：`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
缺少 keystore 时仍可构建未签名的 release 用于校验。

## 说明

- 请遵守当地法律与目标站点条款，仅下载你有权获取的内容
- 本项目不含任何后端服务，纯本地客户端
