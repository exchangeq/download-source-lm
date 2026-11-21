## v10.0-kappa1.12
- 更新服务接入 PocketForge 的更新机制：releases/latest 优先、4 段 Range 并行下载（≥6MB）失败回退单流、进度对话框
- 安装前双校验：包名必须为 com.lm.downloader（Beta 为 .beta）、APK 签名摘要必须与已安装包一致，不符直接拒绝并删除
- 只接受 https 更新地址；下载走缓存目录，校验通过才拉起系统安装器
