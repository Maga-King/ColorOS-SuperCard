# Play 版微信 8.0.72 与 ColorOS 分身选择器

2026-10-15 使用用户提供的 APKS 离线检查。base APK 的 versionCode 为 3085，SHA-256 为 `59cfc54474ed23ff7276d1eea36ea779db7e6893d7b2cb925f28a0f3301784be`。

- `ShortCutDispatchActivity` 存在、exported=true，接受 `com.tencent.mm.ui.ShortCutDispatchAction`。扫码的 `launch_type_scan_qrcode`、旧布尔参数 `LauncherUI.From.Scaner.Shortcut`、付款的 `launch_type_offline_wallet` 均仍被实际实现处理。
- 该 Activity 是 singleInstance。扫码转到 `BaseScanUI`，付款按钱包地区转到 `WalletOfflineEntranceUI` 或另一地区实现；这些内部跳转没有显式指定屏幕。不能仅凭此声明证明反馈中的跳转问题，也不能将该属性当作已确认的 Play 独有差异。
- `WXCommProvider` 及 `com.tencent.mm.sdk.comm.provider` 仍为 exported；这只能证明小程序 SDK 通道存在，不能证明乘车码请求成功。
- vivo 原版 `PayCardSDKLauncher` 使用同样的扫码、付款参数。乘车码使用 `WXLaunchMiniProgram`，本项目保留其参数，通过 `PluginContext` 给 SDK 的微信启动补上当前虚拟屏编号。已检查的启动方法没有 Play 版专用分支。
- 最初的 LSPosed 压缩日志缺少失败点击链路。随后用户提供的 `1.txt` 包含 21:43:41–52 的扫码、付款失败过程：`wx_scan` 的虚拟屏 10、`wx_pay` 的虚拟屏 11 均创建成功，启动已指定对应虚拟屏；ColorOS 随即用 `com.oplus.multiapp/.chooser.MultiAppResolverActivity` 替换微信入口。卡片未收到微信页面到达通知，四秒后触发原版回退并释放虚拟屏。

这份日志的已确认阻断点是 ColorOS 分身选择器，不是 Play 版移除了扫码/付款入口。0.2.10 增加微信、支付宝各自的启动用户，默认用户 0；启动时显式传递 UserHandle、保留显示参数并使用 ColorOS 的跳过分身选择器标记。小程序 SDK 的 PackageManager、ContentResolver 和缓存也按用户隔离，旧全屏回退入口复用同一选择。详见 [PAY 用户选择](pay-users.md)。国内版使用相同的原版微信参数，不做 Play 专用入口替换。

生产包保留微信实际进入原版四秒超时回退时的 `SuperCardPayBridge: WeChat launch timeout` 记录，包含功能、微信版本、后端状态、请求屏幕和最多六个微信任务的顶层 Activity/屏幕。用户路由仅记录安装、实际启动的应用/用户编号和限频异常，不输出 Intent、二维码、账户信息，不在轮询中打印。测试入口仍未编入。

设备功能验证由用户进行：这次只安装统一 APK，不启动卡包、微信或相机，也不重启设备。离线测试不能证明厂商系统最终的任务分配与小程序页面显示；Play 微信各功能仍待反馈设备验证。
