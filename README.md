# ColorOS 超级卡包

将 vivo 超级卡包的原版界面接入 ColorOS，保留原卡片资源与交互，通过兼容模块连接 ColorOS 的系统能力。目前面向 ColorOS 17；需要 root 与支持 libxposed API 102 的 Vector。

## 已接入的功能

- **Pay**：微信、支付宝入口与安全虚拟显示，按设备当前 density 适配卡片。
- **记忆**：接入小布记忆，支持识屏、拍照、语音和混合附件；语音入口对应小布随口记。
- **券码**：每次展开整个卡包都会后台预热钱包、同步当前账号的原生票券，再逐单补查二维码。查询失败最多尝试三轮；合法无码团购券显示“抖音未提供券码，需在抖音内使用”。
- **LENS**：查询 ColorOS 相机的实际模式能力，提供相机快捷入口，包括支持设备上的大师模式。
- **侧边栏**：接入智能侧边栏设置，提供卡包独立开关、浮标位置、长度、透明度、自动隐藏与预览。卡包详细设置中的蓝色链接可直接打开超级卡包浮标设置。

默认卡片顺序为 Pay、记忆、券码、LENS。原子工作台尚未接入。

## 安装

1. 从 [Releases](https://github.com/Maga-King/ColorOS-SuperCard/releases) 安装统一 APK。包名为 `dev.local.supercardhost`，原卡包界面、插件和兼容模块已合并。
2. 在支持 libxposed API 102 的 [Vector](https://github.com/JingMatrix/Vector) 中启用模块，勾选：系统界面、超级卡包自身、智能侧边栏、小布记忆、钱包。
3. 在 root 管理器中安装同版本 `SuperCard-Backend-*.zip`，然后重启。后端用于安全虚拟显示；它是 root 模块，不是第二个桌面 App。
4. 在智能侧边栏设置中打开超级卡包并调节呼出位置。记忆、支付与票券功能需要对应原应用安装并完成自身的登录、协议和授权设置。

本项目不会替用户开通钱包服务、登录账号、购买或核销票券。票券的发现与内容由原钱包负责，原应用不给出的二维码不会被伪造。

新安装与升级时请保留签名一致性。自行编译或 fork 的 APK 默认使用自己的调试证书，不能直接覆盖官方 Release。此前测试阶段的独立 `com.vivo.card`、`com.vivo.memory.card` 已由统一包替代。

## 构建

仓库包含当前统一包所需的宿主源码、原版资源与插件输入、补丁 DEX、DEX 补丁源码、合包工具、root 服务脚本和 Gradle Wrapper。构建不依赖作者电脑上的反编译分析目录。

推荐 Windows，使用与 Actions 相同的环境：

| 工具 | 固定版本 |
| --- | --- |
| JDK | Temurin 21.0.9+10 |
| Gradle Wrapper | 9.7.0，校验 distribution SHA-256 |
| Android Gradle Plugin | 9.4.0 |
| Android SDK Platform | android-36 |
| Android Build Tools | 36.0.0 |
| Python | 3.12.10 |
| jadx 合包依赖 | 1.5.5，脚本自动下载并验证 SHA-256 |
| DexKit | 2.0.7 |

设置 `JAVA_HOME` 与 `ANDROID_HOME` 后，在仓库目录执行：

```powershell
# 初次构建或修改代码后，不要求产物等于仓库内旧基准。
./ci/build.ps1 -SkipReference
```

产物在 `dist/`。正常 Gradle 的 `app-debug.apk` 只是中间产物，不能作为完整统一包发布。完整流程会编译宿主、合并原 DEX 和资源、检查 Kotlin 链接、执行 16 KiB 对齐并验签。

默认从 `~/.android/debug.keystore` 取签名；不存在时由 Gradle 创建。原版输入及已验证补丁资产的哈希保存在 `vendor/inputs.sha256.json`。

如果需要从原包重新生成补丁 DEX，执行：

```powershell
python tools/rebuild_assets.py
```

该命令在临时目录重建并逐 DEX 比较现有资产，不修改仓库。所需原版补丁库已包含在 `tools/asset-patcher-libs/`，无需 WSL。详见 [构建依赖审计](docs/构建依赖审计.md)。

## Actions 与发布

[构建工作流](.github/workflows/build.yml) 使用固定工具链构建完整统一包，比较 `ci/reference-apk.json` 中的本地基准，并额外核对官方签名证书。所有普通 ZIP 项都会按解压后的内容比较，包括 DEX、资源、插件和 native 库；只排除 APK 签名元数据。详见 [产物一致性](docs/产物一致性.md)。

官方签名密钥保存在 Actions Secret `SUPERCARD_KEYSTORE_BASE64`，不会提交到仓库。PR 构建和 fork 不会取得官方签名。

修改源码后，应先完成本地验证，再更新 `ci/reference-apk.json`。Actions 通过后下载其产物，与同一提交的本地产物再次核对，再发布 Release。构建成功不能替代真机功能验证。

## 混淆与兼容范围

未混淆的厂商模型、公开生命周期和接口保留固定名称。混淆入口优先采用完整签名与结构反射，定位失败时由 DexKit 按业务字符串及调用关系查找，并再次检查候选唯一性与类型。无法确定的入口返回不可用，不能以“第一个匹配”猜测原生写入方法。

具体覆盖与限制记录在 [混淆 Hook 策略](docs/混淆Hook策略.md)。目标 App 更新后仍可能改变接口语义，不能仅凭方法成功找到就认定所有功能兼容。

票券已实测团购券的新增同步、真实二维码及合法无码分支。电影票等模型已接入，但没有将未实购的类型宣称为已实测。部分锁屏界面可能与系统锁屏手势竞争，机型差异需实际核对。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java` | 兼容层、运行时桥接与 root 后端源码 |
| `app/src/main/assets` | 原包资源、内置插件、已验证的补丁 DEX |
| `vendor` | 原始 Manifest、构建输入哈希与来源说明 |
| `tools` | DEX 补丁、统一包封装、产物对比工具 |
| `ci` | 版本、构建入口、本地产物基准 |
| `root-backend` | root 后端模块服务脚本 |
| `docs` | 中文实现审计与构建说明 |

原厂 APK、资源与第三方组件的权利归各自权利人；它们不因进入本仓库而获得新的开源授权。参见 [第三方组件说明](THIRD_PARTY.md)。
