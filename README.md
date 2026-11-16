# LM Downloader

基于 [Maxx-EX/download-source](https://github.com/Maxx-EX/download-source) 重构的独立衍生版，已获原作者授权。署名 **LM**，独立包名与自有签名，可与原版共存安装。

## 与上游的关系

- 上游基线：v7.0.3（Apache-2.0）
- 本仓库为独立维护的衍生分支，保留上游全部功能，逐条改动见 [CHANGELOG.md](CHANGELOG.md)
- 原作者署名与授权说明见 [NOTICE](NOTICE)，许可证见 [LICENSE](LICENSE)

## 功能

- 多线程分片直写 + 三重长度探测 + 流式兜底，可暂停续传
- Zero-core（NDK）原生合并，Java 降级兜底
- 内置浏览器，过滤百度系跳转与常见广告跳转，资源嗅探
- 自定义下载目录（SAF）
- GitHub 镜像加速（默认直连）
- 任务持久化、前台服务保活、通知栏进度
- 六套渐变主题，深浅两态

## 安装包

- 正式通道：`com.lm.downloader`，应用名「LM 下载器」
- 内测通道：`com.lm.downloader.beta`，应用名「LM 下载器 Beta」

APK 由 GitHub Actions 使用仓库密钥签名，Releases 页面直接下载。

## 构建

```bash
gradle :app:assembleOmegaRelease
```

签名需要环境变量：`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

## 说明

- 请遵守当地法律与目标站点条款，仅下载你有权获取的内容
- 纯本地客户端，不含任何后端服务
