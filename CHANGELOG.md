## v10.0-kappa1.7
- 新增 C/NDK 渲染计算层：nativeFitScale 算整页适配缩放、nativeDarkenRgb565 逐像素压暗
- 页面加载后检测内容宽超出屏宽 >15% 时，用 C 层算出的比例 setInitialScale 并重载一次，彻底解决右半截裁切
- Java 降级实现与 C 层同算法，NDK 加载失败也不影响功能
