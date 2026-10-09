# 混淆 App 的 Hook 定位与兼容边界

本文件根据当前工程源码审查，记录已实现的定位方式及薄弱点。它不是跨版本兼容承诺。系统应用升级、ROM 更换、厂商重新混淆或接口语义变化，均可能需要重新适配。

## 1. 当前策略概览

工程采用 libxposed API 102，由 Vector 在指定进程加载；DexKit 固定依赖为 `org.luckypray:dexkit:2.0.7`。固定且未混淆的厂商类名、平台接口可以保留。混淆入口优先走已知名称或结构反射，失败后再按 DEX 特征搜索。

已实际使用的方式有：

- 固定包名、类名与完整参数签名定位。
- 在已知类内按参数、返回值、修饰符或字段类型进行结构反射。
- 部分入口要求候选唯一；零匹配或多匹配均报错。
- 钱包回调枚举实际加载的 DEX，再根据所属 ViewModel 字段和回调签名筛选。
- `ObfuscationResolver.java` 统一打开目标 ClassLoader 的 DEX，业务 resolver 以字符串、调用关系、字段使用和完整签名定位，反射复核后只接受唯一候选。
- 内置 vivo APK/DEX 进行资源、实现类、共享接口与生命周期方法验证。
- 不支持的接口返回不可用，真实成功必须由原应用结果确认。

**方法名混淆变化、类名混淆变化、接口语义变化是三个不同问题。** DEX 特征可缓解前两项，但仍需要稳定业务锚点。参数与返回类型一致不能证明新版本方法的业务语义一致。

公共 resolver 使用短生命周期 `DexKitBridge`，通过 `AutoCloseable` 释放；先 `System.loadLibrary("dexkit")`，JNI 加载失败、无可读 DEX、零候选或多候选均报错。它不执行候选业务方法，不绕过登录、授权或保存确认，也不把定位结果当作写入许可。[DexKit 官方说明](https://github.com/LuckyPray/DexKit)、[2.0.7 发布记录](https://github.com/LuckyPray/DexKit/releases/tag/2.0.7)。

## 2. 作用域与加载时机

入口为 `app/src/main/java/dev/local/supercardhost/VectorEntry.java`：

| 目标 | 加载入口 | 当前用途 |
| --- | --- | --- |
| `com.finshell.wallet` | `onPackageLoaded` | 原钱包授权状态、票券发现与实时详情读取 |
| `com.oplus.aimemory` | `onPackageLoaded` | 原小布图片、文档、录音导入与保存确认 |
| `com.coloros.smartsidebar` | `onPackageReady` | 原生设置页扩展、卡包独立浮标设置模式 |
| `com.android.systemui` | Application 创建完成后启动 | 原 vivo 卡包与内置插件运行、窗口与手势兼容 |
| 宿主自身 | `onPackageLoaded` | 合包后的原 vivo 设置兼容 |

当前作用域见 `app/src/main/resources/META-INF/xposed/scope.list`。它没有直接 Hook 微信、支付宝或 ColorOS 相机进程；这几项功能主要通过原卡包启动流程、系统窗口能力、相机 Provider 与 Intent 对接。业务包身份与广播发送 UID 检查属于访问控制，不等同于目标 APK 的版本或代码指纹校验。

## 3. 钱包：反射快路径与 DexKit 唯一回退

### 3.1 全量票券发现

文件：`WalletTicketDiscovery.java`、`WalletDexResolver.java`，重点 `resolve()`、`inventory()`。

- 固定加载 `com.nearme.pay.business.cardpackage.tickets.TicketsRepo`。
- 根据唯一的非静态 `() -> io.reactivex.Observable` 方法取得原生发现流水线，避免依赖短方法名 `f`。
- 根据唯一的 `(CardTabTabRspVO) -> void` 方法识别原生列表整理/缓存写入边界。
- 结构反射失败时，发现方法以 `TICKET`、`flatMap(...)` 与完整签名定位；缓存写入边界以 `cardTabRspVO`、参数类型和 `CardPackageRspVo.setTicketList(List)` 调用关系定位。
- 校验响应 getter 与 Rx 方法返回类型；只对自己创建的 Repo 拦截缓存写入，原钱包 UI 实例继续原方法。
- 失败记录 `resolveFailure`，当前进程不重复安装部分 Hook。

限制：Repo、响应模型、Rx 类名与 getter 名仍固定。如果新版新增同签名方法，DexKit 可用业务特征进一步筛选；筛选后仍多候选则拒绝适配。

### 3.2 实时详情与二维码

文件：`WalletNativeTicketRefresh.java`，重点 `resolveNative()`、`observeCallback()`、`callbackCandidates()`。

- 固定以 `TicketOrderViewModel` 为锚点。
- 订单查询方法按唯一 `(LocationInfoEntity, boolean) -> void` 定位。
- 授权 `U(String)` 的快路径失败后，按 `authType`、完整签名及 `MovieTicketAuthRequest` 构造调用定位；订单查询的回退锚点是 `QueryDouyinOrderRequest` 构造调用。
- 订单 ID 字段按唯一的非静态、非 final `List` 字段定位。
- 字段结构不唯一时，检查已定位查询方法实际读取的字段，只接受唯一的 ViewModel 可变 `List` 实例字段。
- 成功回调按 `(int, int, Object, 原响应类型) -> void`，结合唯一的 ViewModel 类型实例字段定位所属对象。
- 当 APK 缺少 `InnerClasses` 注解，遍历 `BaseDexClassLoader.pathList.dexElements[].dexFile.entries()`；扫描范围限定到 ViewModel 所在包，使用 `Class.forName(..., false, loader)` 载入候选而不主动初始化。
- 枚举失败、未找到或候选不唯一时，DexKit 根据完整响应签名与 ViewModel 字段使用关系搜索回调；再校验唯一所属对象字段。不会依赖 JADX 显示的方法重命名。
- 候选多个即失败；仅拦截自己创建的 ViewModel。账号变化、取消及迟到回调通过请求归属与弱引用墓碑隔离。

仍固定的稳定锚点包括 ViewModel、请求/响应模型、模型 getter 和继承失败回调 `onTransactionFailedUI(...)`。回退打开当前目标 ClassLoader 的 DEX；这不保证任意热修复加载器、已剥离业务字符串或改变协议的新版本仍能定位。

### 3.3 模型转换及失配处理

文件：`WalletCouponBridge.java`、`WalletDexResolver.java`，重点 `NativeReader`、`DetachedTicketRow`、`login()`、`oid()`。

- 原 `TicketInfoDetail` 的完整 getter 类型校验后复制到模块本地 DTO；移除了读取路径中的 `t60.k/t60.d0/k20.a`、Room bean/DAO 转换与 companion 依赖。不向钱包数据库插入该 DTO。
- 登录回调接口从已校验 facade 方法参数推导，不再固定 `q7.d`。`com.finshell.accountservice.a.hasLoginAsync` 反射失败后，按对稳定 `AccountUtilsKt.hasLoginAsync` 的调用关系与回调接口契约定位 facade。账号变化监听复用该 facade 类型。
- `rr.a.j()` 反射失败后，按类内 `As-UtilsSp` 等日志特征及对 `key_sso_id_hash` 读取方法的调用关系定位账号 getter；读取值只用于当前账号隔离。
- CTA 使用稳定的 `SPreferenceCommonHelper.getCtaPass()`。它在原实现中已包含旧 `d.n()` 分支，因此移除了冗余混淆类依赖，未改变原授权语义。
- 原 DAO 修改监听仅用于触发重新读取，保留其稳定生成类名与完整边界校验；监听失配不会阻止每次呼出的原生库存查询。
- 失败返回明确不可用/不完整状态；不把请求失败当成“没有券”。授权后的原生单订单成功返回空码，与取码失败分别处理。

限制：当前分类、状态和字段语义来自已研究版本；新增票券类别不自动等于可展示，不能承诺未来所有钱包票券无需适配。`SHA-256` 在此主要用于账号/条目派生标识，**不是 APK 文件指纹**。

### 3.4 强制回退验证

`WalletDexResolver.probe(ClassLoader)` 强制执行 8 项 DexKit 查询，并做真实反射签名复核；不调用登录、账号 getter、网络、缓存写入或核销方法。桌面 `main(wallet.apk)` 只检查 APK 元数据候选唯一性，不能代替 Android ClassLoader/JNI 装载验证。

已对本地实际钱包 APK 使用官方 2.0.7 源码构建的 Linux JNI 扫描，账号 getter、登录 facade、授权、订单查询、库存发现、缓存写入边界、授权回调、订单回调 8 项均唯一命中。这验证当前 APK 的特征有效，不能外推为其他钱包版本已通过。发布 APK 仍需验证目标进程中的 `libdexkit.so` 加载与只读 `probe`。

2026-10-09 在手机上通过 root `app_process` 创建 `PathClassLoader`，读取已安装钱包 APK 并执行强制回退元数据探针：8 项唯一匹配，耗时 420ms。此验证发生在独立 root 进程，不是注入钱包进程的 JNI/强制 fallback 验证。另已安装的 v5 模块实际完成原生同步查询：3 张真实券中 2 张取得二维码，1 张为原服务合法无码结果；业务查询成功不等于已强制执行目标进程 DexKit 回退。

## 4. 小布记忆：按结构定位写入入口，成功依赖真实落库确认

文件：`MemoryImportBridge.java`、`MemoryDexResolver.java`；详细锚点见 [记忆混淆适配](记忆混淆适配.md)。

- `resolveImporters()` 从已知 `PortalImageUseCase`、`MemoryManualImport`、模型类开始。
- 图片请求构造器按完整参数类型寻找，重复候选会报错。
- Kotlin suspend 方法按参数前缀、`Object` 返回值，以及具有 `resumeWith(Object)` / `getContext()` 的 Continuation 接口定位。
- 单例、部分实例字段按类型唯一匹配。
- 隐私、服务、网络、存储和录音回调入口支持反射失败后的 DexKit 回退，并要求唯一候选：隐私/存储以稳定日志字符串定位；服务以开关键名和默认参数函数调用关系定位；网络以静态 volatile 状态字段、真实 getter 指令及 `NetworkCapabilities.hasCapability` 写源校验；录音回调以原录音调用链读取的 handler 字段、接口契约和单例结构定位。
- 网络 getter 排除合成访问器，避免读取同一字段的两种方法被误当成唯一候选。五类回退已对本地旧/新小布 APK 使用真实 JNI 离线扫描，均唯一命中。
- 原生序列化、图片构建、保存、录音结果处理等入口另有签名与返回类型验证。
- Provider `call/insert` 保留平台签名，并按本任务归属观察；真实记忆 ID、附件复制与文件结果参与成功确认。
- 图片/文档基础适配失败会记录 `installError`；录音适配单独记录 `audioError`，保留可用能力。写入超时或结果不确定不会因“接口可能改名”而盲目重放。

主要限制：

1. 业务类全名、Provider authority、模型构造器形状、部分 getter 仍固定。22 参数附件构造器等对模型升级敏感。
2. 仍依赖 `getDeclaredClasses()` 寻找部分请求类；这条路径尚未补全任意改名模型的 DEX 发现。
3. getter 优先使用 `getMemoryRepository/getMemory/getUri` 等稳定名称，缺失后按严格唯一签名定位，不再取首个同类型无参方法。原 `r.c/a/g` 等诊断入口仍未补全回退。
4. 安装 Hook 不是完整事务；某个后续步骤失败时，已安装的前序 Hook 不会自动全部回滚。当前靠任务归属、能力标志和不可用结果限制后续调用。

`MemoryDexResolver.probe(loader,true)` 及鉴权后的只读 `probe` 操作只解析和校验，不执行导入、不写数据库、不修改历史记录。扫描通过后仍需实际验证当前设备原服务、权限与保存结果。

2026-10-09 手机端 root `app_process` / `PathClassLoader` 元数据探针完成 5 类角色定位，耗时 320ms。尚未在注入的小布目标进程中强制执行 DexKit fallback；该结果不作为目标进程 JNI 装载或导入业务成功的证明。

## 5. ColorOS 相机：使用原生能力查询和 Intent，未 Hook 相机混淆类

文件：`ColorOsLensModes.java`，由 `LensRuntimeBridge.java` 接入原 LENS UI。

- 查询 `content://com.oplus.camera.entry/static_info` 的 `mode/rear/front` 64 位能力掩码。
- 只有当前 Provider 声明的模式才显示/启动；超广角另读 Camera2 元数据，无法唯一确定后摄时省略入口。
- 启动 `com.oplus.camera.Camera`，action 为 `com.oplus.camera.action.SHORTCUT_TYPE_MENU`，传入已研究的 `mode/rear/front/zoom`。
- Provider 缺失、列名变化或查询失败返回空能力列表；不按机型名称猜测模式。

限制：Provider authority、列名、模式编号及 Intent 参数是厂商接口，仍可能变化。能力位存在不能独立证明新版本启动语义完全相同；例如大师能力位与实际启动模式之间有特定映射，需要随相机版本验证。没有相机内部 DEX 特征搜索，也没有相机 APK 版本指纹匹配。

`EmbeddedMemoryCamera.java` 的小窗拍照使用 Camera2，不依赖 ColorOS 相机混淆类；它仍依赖设备 HAL 与公开相机能力的正确行为。

## 6. 智能侧边栏：短名回退与原生页面开放门槛

文件：`SidebarIntegration.java`、`NativeFloatSettings.java`、`SidebarDexResolver.java`。

复用目标 APK 自己的 Fragment、COUI Preference、资源与主题。完整签名的已知名称优先，失配后才建立短生命周期 DexKit 会话；只保存解析后的 Method/Field，不保留 native bridge。主要覆盖：

- 主页类 `i`：以 `EdgePanelSettingsFragment/key_root/key_toggle` 字符串和 Fragment 继承关系定位；`X(boolean)` 用 `updatePreferences/key_panel_category/key_style_category` 定位。
- Preference 查找、添加、摘要：用异常/日志字符串与完整签名定位；标题排除已定位的摘要方法，顺序从原添加方法的调用关系取得，可见性按 final 方法的完整签名定位。
- `s/y` 字段：失配后从原 find→getKey 的字段读取，以及 persistBoolean→shouldPersist→纯字段 getter 链定位。监听接口从真实 Preference 字段的唯一接口契约推导，回调不再依赖 `a/b` 名称。
- 配置代理：以真实 Secure key、public/static 修饰符、Context 参数及完整返回类型定位。透明度定位排除 private 的旧配置迁移方法；手势的普通 getter 与 check-and-reset getter 通过 setter 调用关系区分，后者也参与写隔离。
- 原生浮标页：重置用四个真实 Proxy setter 的组合定位；单选刷新用 permanent getter；底部提示和 alpha/gesture 可见性按日志、字段类型及调用组合定位，排除 initPreferences。
- 位置/长度滑块用初始化日志和各自 setter 调用定位；开始/结束来自父类抽象方法与原 seekbar 回调契约。透明度值、索引、绘制与变更用 getter/setter 和 setProgress 调用关系定位。原 alpha/bar 字段按唯一类型取得。
- Fragment 安装 `P` 与 `o/k/p/i` 事务链按原 Fragment factory 和真实调用图定位；不任取同形方法。生命周期方法继续使用平台保留名称。
- Preference enabled setter 用 boolean 字段与三个通知/读取调用的组合定位；COUI 动画取消从已定位 reset 内唯一的 SeekBar 层级 `void()` 调用取得，支持继承方法改名。接口声明不参与继承方法候选，DEX 构造器不转换为 Method。

原生卡包浮标页进入前会完整解析关键配置/重置拦截和 UI 反射边界，安装全部必要 Hook 成功后才设 `NativeFloatSettings.isReady()`。Hook 只在带 `CARD_FLOATBAR_MODE` 的 Activity 上作用，普通 OPPO 设置保留原逻辑；卡包 Hook 异常不会回落到原 OEM 写入。

跨进程入口不读取设置进程的静态 readiness，而显式启动原 Activity 并传 mode。目标侧 Activity 的 `onCreate` 守门先于混淆解析安装：未 ready 时只调用 AppCompat 祖先生命周期，跳到模块 `GestureAreaActivity` 并 finish，不运行 OEM Fragment 初始化。侧边栏内部入口也会在未 ready 时直接转独立页。安装失败仍可能留下前序 Hook，当前依靠守门和 READY 阻止进入，未实现完整 Hook 事务回滚。

`SidebarDexResolver.probe(loader,true)` 只解析和反射校验，不调用原业务、不安装 Hook、不写设置。返回 `status/error/nativeReady/resolvedCount/mainFragment`；这里的 nativeReady 仅表示结构预检通过，不表示 Hook 已安装。桌面 `main(SmartSideBar.apk)` 对真实本地 APK 的 **36 项** 字符串/字段/调用组合已逐项唯一命中；这不能代替 Android 目标 ClassLoader 和原生页实际验证。

2026-10-09 在 ColorOS 17 手机上，由独立 root `app_process` 创建 `PathClassLoader`，读取已安装智能侧边栏 `17.9.8`（`versionCode=170009008`）并执行强制回退元数据探针：`status=ok`、`nativeReady=true`、`resolvedCount=76`，耗时 383ms。该结果证明 root 探针环境中的解析与签名复核通过，**不表示已在注入的侧边栏目标进程中验证 DexKit JNI 装载或强制 fallback**。当前原生页面由普通反射路径运行，用户已确认设置链接跳转正常；UI 预览和保存能力需分别以实际操作验证。本地离线扫描目标另为 `17.9.2`（`versionCode=170009002`），不与实机版本混同，也不据此推断其他版本可用。

明确限制：厂商 Fragment/Widget/Proxy 稳定全名、资源 key、布局 rootView、原生构造器仍固定；已支持的方法回退依赖当前业务结构保留，结构变化不按名字相近推测。任何必需项不存在或存在多个候选都会拒绝原生卡包页面，使用独立设置页。未声称全 ROM 通用。

## 7. 内置 vivo 插件与系统隐藏 API

内置代码的来源固定在发布资产里，相比可自行升级的外部应用更可控，但更换资产后仍要验证：

- `MemoryPluginLoader.java` / `MemoryArchiveResources.java`：资源 APK 与 patched `memory-code.zip` 分离；共享 `MemoryCardPlugin` 接口，准备成功后才加载。
- `CouponPluginLoader.java`：解析 APK manifest，检查包名、原布局、实现类及 `StagingCardPlugin` 共享接口，初始化 adapter 后才覆盖可用性。
- `MemoryRuntimeBridge.java`：在固定边界类 `a6.d` 内按完整结构唯一寻找操作、保存、绑定入口，不再单凭 `m/i/b` 名称；边界类与其他模型类型名仍固定。
- `CouponRuntimeBridge.java`：`qe/te/se/ne`、栈 `p/q/i/j`、`e0/I0/G0` 等依赖随内置插件锁定；更新插件时必须同步复核。
- `PayRuntimeBridge.java`：对原 getter 的 DEX 名称与 JADX getter 名称有有限候选回退；这不是微信/支付宝全部版本兼容方案。

平台边界另见 `SystemUiBridge.java`、`RootDisplayClient.java`：SystemUI `Dependency.sDependency/getDependencyInner`、`DisplayManagerGlobal.createVirtualDisplayWrapper` 等固定隐藏入口仍依赖 ROM/Android 版本。HiddenApiBypass 只能解决访问限制，不能补回已经删除或改变签名的 API。

## 8. 版本失配降级的实际边界

当前较完善的是钱包发现/详情的签名唯一校验、请求归属隔离和进程内失败缓存；小布有能力分支和真实保存确认；侧边栏原生卡包页有完整预检、安装成功门槛和 Activity 降级守门。其他部分 UI 安装仍以日志和逐项捕获为主。

目前尚未实现以下统一机制：

- 按目标 APK `versionCode`、签名证书和 DEX/APK 摘要选择独立 adapter profile。
- 每个目标应用统一的“结构预检 → 原子安装 → 能力发布”流程。
- 目标升级后自动失效的持久化 DEX 定位缓存。
- 所有原生 UI 的强制安全降级页或完整 Hook 回滚。

因此“没有崩溃”不等于“全部入口适配成功”；发布时应检查能力、日志、原应用副作用与实际功能。

## 9. 后续改进与发布验证建议

1. 验证侧边栏完整预检/可用性门槛：制造必需结构失配时，所有卡包 mode 入口应转独立页，原 OPPO 配置不能被误写；继续完善完整 Hook 回滚。
2. 将外部应用固定短名集中到版本 profile，并记录公开版本号与结构摘要；未知版本先验证结构，不能默认套用。
3. 扩展 DexKit 时继续优先稳定业务特征，例如请求路径、Provider 操作字符串、响应模型和调用关系；定位后仍做唯一性、完整签名与只读行为验证。新增版本用只读强制回退 probe 检查；DexKit 结果不能直接授权写入。
4. 钱包需验证冷启动、原生未登录、授权关闭、合法无码券、读取失败、账号切换、迟到回调、新购券和部分不支持类别。
5. 小布需验证服务关闭、模型/Provider 失配、真实附件存在、保存确认、超时晚回及不确定结果不重放。
6. 相机需验证 Provider 返回能力、每个模式的真实启动结果；侧边栏需验证普通 OPPO 页面不变、卡包独立配置、恢复默认和预览结束。
7. 对发布 APK 做完整构建与资产匹配检查；记录“已验证的 ROM/Android/目标应用版本”，不写“全版本通用”。诊断仅记录版本、结构、错误类型和脱敏计数，不收集账号、设备串号、券码或原始响应内容。

以上改进项是审查建议，不代表已经在当前业务代码中实现。
