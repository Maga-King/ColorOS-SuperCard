# 虚拟屏 DPI 与跨设备限制核对

## vivo 原版参数链路

依据本地 `originos-supercard-analysis/decompiled/SuperCard/sources`，并核对对应 Smali：

1. `com/vivo/card/utils/CardUtil.java` 的 `getNewConfigurationContext()`（2491 行起）取屏幕短边，创建卡包自己的资源配置：

   | 屏幕短边（px） | 卡包 densityDpi |
   | --- | --- |
   | 720 | 320 |
   | 1080 | 480 |
   | 1260 | 560 |
   | 1440 | 640 |
   | 其他 | `DisplayMetrics.DENSITY_DEVICE_STABLE` |

2. `com/vivo/card/service/CardServiceImpl.java` 的 `onCreate()`（228、246 行）将这个适配后的 Context 保存到 `CardPerfContext`。
3. `com/vivo/card/framework/virtual/VirtualViewManager.java`（245–250 行）读取该 Context 的 `densityDpi`，无有效值时回退到 480。PAY 的配置 Builder 也设置了 480，但实际创建使用上述资源值。
4. 同一方法的容器模式按 `虚拟高度 = 容器高度 × 虚拟宽度 ÷ 容器宽度` 计算尺寸；付款码在原版判定为放大显示时可将宽度乘以 1.1。
5. `VirtualDisplayWrapper.java`（40–46 行）直接调用 `DisplayManager.createVirtualDisplay(name, width, height, density, surface, flags)`，没有比较主屏 DPI 或长边的上限。`resize()` 也直接传递参数。

`DisplayUtils.getNewConfigurationContext()` 另有 1216→540 和 VOS 折叠换算分支；卡包服务上面的入口调用的是 `CardUtil`，不能将这两个相似函数混为同一条路径。

## 本次反馈及修复

反馈日志中系统资源为 474 DPI，卡包按 1080 短边请求 480 DPI。原 root 后端缓存主屏参数后，因 `480 > maximumDensity` 在调用 Android 创建接口前拒绝了请求。日志有 16 次相同拒绝，后端仍在线，微信扫码/付款及支付宝乘车码均受影响。

0.2.12 删除我们额外添加的 DPI 上限、宽高不得超过主屏长边的上限，以及启动时读取这些上限的依赖。原版计算出的正数参数原样传递给系统，避免较低 DPI、显示分辨率变化和折叠状态变化导致误拦截。权限、会话、Surface、安全显示标志及显示生命周期检查继续执行。

删除误拦截不代表已验证所有 ROM 的系统接口。离线构建完成后，仍需在反馈设备验证创建成功、页面显示、点击和释放。
