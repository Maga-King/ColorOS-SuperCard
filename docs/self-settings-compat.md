# 卡包自身设置适配

统一包不依赖框架把模块本身加入作用域。`PatchPlugin.java` 为原版 DEX 的 25 个明确方法保留 `supercardOriginal$` 实现，并在原入口调用 `SelfSettingsCompat`。

- 仅主进程 `dev.local.supercardhost` 执行静态适配。SystemUI 使用相同 DEX 时直接调用保留的原实现，继续由现有外部 Hook 适配。
- Vector 仍可发送自身加载回调；统一包的 `OriginalAppCompat` 会跳过重复 Hook。
- 薄桥、预览几何和默认值/迁移 helpers 不引用 Xposed API，避免官方 LSPosed 不加载自身时出现缺类错误。
- 反射原方法按 `Class<?>` 和方法名缓存，保留类加载器身份。构建遇到缺失或歧义入口会失败，而不是生成部分适配包。

覆盖原设置 Activity 初始化、去重复标题、手势默认值、隐藏已替代的锁屏/息屏选择器、浮标设置链接、运行状态与 PAY 设置入口、PAY/LENS 编辑文字颜色、预览几何、记忆/券编辑按钮隐藏、PAY 项过滤、LENS 默认模板/大师模式/已有配置迁移/能力查询/资源名，以及券可用性、首次默认位置和钱包管理点击。

券在原版设置列表使用静态缩略图，不实例化券插件。实际卡片插件、券数据刷新、相机启动及解锁继续由 SystemUI 的现有 Hook 负责；这些不因自身迁移而改成独立运行。

LENS 的 `processCameraFunctionFormat` 同步填充固定模式和启动信息，替代原版依赖相机 Provider 的异步格式化。已有选择、排序和背景保留；缺失条目从统一包内原版模板补齐，没有可用选择时恢复六个默认选择。SystemUI 和自身设置共用同一实现。

修改 DEX 补丁后先输出到新的临时目录检查 `self-settings-report.txt`，确认 25 个入口及 PAY 66 个密度调用断言通过，再更新 `app/src/main/assets/supercard-code.zip`。随后再次运行 `tools/rebuild_assets.py`，两份资产都应报告 `IDENTICAL DEX`。不要把只有宿主代码的中间 APK 安装到手机。

设备检查应包含原设置页、PAY 编辑、工具设置入口，以及 SystemUI 的 PAY 展开；检查 `SuperCardSelf` 启用日志和是否出现 `VerifyError` / `NoClassDefFoundError`。静态构建通过不能代替这些设备验证。
