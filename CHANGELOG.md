## v10.0-kappa1.5
- 浏览器全检：广告拦截不再命中主文档（此前会整页白屏）；新增白屏看门狗，正文为空自动重载一次，仍空则给出「重试/兼容渲染」面板
- target=_blank / window.open 改为 onPageStarted + shouldOverride 双路接管，并用原子标记防重复建标签
- 嗅探下载补齐会话头：Cookie、Referer、当前 UA，修复站点只给片段或 403
