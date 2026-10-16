# PAY 启动用户

微信和支付宝独立选择启动用户，未配置时均为主用户（Android 用户 0）。入口为超级卡包 App 的「运行状态与 PAY 设置 → PAY 启动用户」。刷新会读取已安装的 ColorOS 分身，多个分身按实际用户编号区分；不把工作资料或普通次要用户列为分身，不假设分身编号一定是 999。

保存通过现有校验发送 UID 的配置广播交给 SystemUI，只有 `wechat` / `alipay` 两个固定应用和已发现的分身编号可写入。设置存放于 SystemUI 所用的 Secure 设置表，键为 `supercard_pay_user_wechat`、`supercard_pay_user_alipay`。UI 收到保存结果后核对实际值再显示成功；旧模块没有用户设置协议时禁用两个按钮，提示重新加载模块。创建、删除分身后可刷新；已选分身被删除时保留原选择并提示不可用，不静默改用主账号。

每次 PAY 启动捕获所选用户。启动使用 SystemUI 的 `startActivityAsUser(Intent, Bundle, UserHandle)`，复制 Intent，保留原始 flags、extras 和显式 ActivityOptions。无 options 的 SDK 启动补上当前虚拟屏；明确要求 display 0 的原版全屏/验证流程保留。ColorOS 使用 `OplusIntent.OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER`（2048），与原有 Oplus flags 做 OR；旧 API 使用 `setSkipMultiAppChooser`。不设置会强制跳到 999 的 4096 标志。

微信继续调用原版 `PayCardSDKLauncher`，不改变扫码、付款、小程序 ID/path。仅 SDK 收到按用户创建的 Context，PackageManager、ContentResolver 及 SDK application context 均对应同一用户；卡片的全局设置 Context 不变。跨用户 Context 使用已核对的 ContextImpl `createPackageContextAsUser("android", …)` 特殊分支，保留调用方身份，并避免要求克隆用户安装 SystemUI。乘车码的同步 SDK 工作段串行执行，每次创建新的 `wxBusApi` 并在结束时释放字段引用，避免切换用户后复用旧 resolver。已关闭或被下一次启动替换的卡片会拒绝旧 SDK 请求。

旧 `SwitcherUtil.openPayCardScene` 的 PAY 全屏/超时回退复用当前 SDK 和 Intent builder，同样指定选中的用户与 display 0，其他功能继续执行原实现。原有 `wx2_*` 项保持分身语义：选择了分身时使用该分身；设置为主用户时使用第一个可用分身，没有分身则报不可用。切换设置会释放旧 PAY 虚拟任务；任务观察器同时核对显示编号和用户编号。

离线验证：`python tools/test_pay_users.py` 使用假的 Android 系统服务运行实际 `PayUserSettings`、`PayUserRouting`，覆盖 57 项检查，包括默认值、独立保存、多分身、旧 API、删除/禁用分身、拒绝任意用户、Intent 边界、保留参数/显示、SDK resolver 与缓存切换、旧全屏回退和过期请求。接口签名另与捆绑 vivo DEX、ColorOS framework JAR 核对。

上述测试不执行真实 ROM IPC 或 libxposed 注入。ColorOS 的分身内部缓存、微信小程序在不同版本 ROM 上的落屏结果仍需实机测试；不能由离线通过推断所有 Play 版问题均已解决。
