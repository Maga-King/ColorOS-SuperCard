# PAY 虚拟屏离线测试

正式 CI 虽然使用 `assembleDebug`，仍默认选用空的 `PayDiagnostics` 实现，不包含测试入口或测试应用。
仅显式传入 `-PpayProbe=true` 才编译测试实现，并启用 `:payProbe` 工程。

## 构建与安装

先配置 JDK 21、Android SDK 和 `PYTHONUTF8=1`，按常规流程校验本地构建输入。

```powershell
./gradlew.bat :app:assembleDebug :payProbe:assembleDebug -PpayProbe=true --offline --console plain *> pay-probe-build.log
# VERSION_CODE 必须高于目标机当前安装版本；不要直接安装 app-debug.apk。
python tools/unified-prototype/build.py --version-code VERSION_CODE --version-name VERSION_NAME-pay-probe --sign
adb -s TEST_SERIAL install -r tools/pay-probe-app/build/outputs/apk/debug/payProbe-debug.apk
adb -s TEST_SERIAL install -r tools/unified-prototype/out/supercard-unified-prototype.apk
```

安装后需让 LSPosed 加载新模块；若只有旧的 observer 日志而无 `PAY probe entry installed`，不要把它当成新代码已生效。整机重启后重新确认日志。

## 验证

- 解锁后打开卡包，在 PAY 功能列表点「虚拟屏测试」。不要从桌面打开测试应用来判断卡片是否成功。
- 测试项在 ViewModel 接收功能列表时加入运行时数据流，显示与点击使用同一份列表，不写入 `card_setting_data`。
- 入口沿用原版点击处理、后端就绪等待、虚拟屏启动、任务观察与超时回退。仅补充测试包的 Intent、名称、目标页面识别和触摸透传。
- 测试应用不需要权限、联网或登录。它开启 `FLAG_SECURE`，显示实际 Display ID，提供点击计数和第二页。
- 将测试页 Display ID 与 `Original secure payment display ready id=...` 对照；`0` 表示主屏。检查第二页仍在同一副屏，点击计数是否增加，关闭卡片后是否停止延迟启动。
- 后端未就绪时，生产代码会等待或重置卡片，不能用此情形测试原版 4 秒全屏回退。`Original 4s fallback timer fired` 仅表示定时器进入；需结合 `Original fullscreen path invoked` 和应用实际屏幕判断结果。
- 小窗、分屏等也可能走原版全屏分支，出现全屏不等于定时器超时。
- 基础测试通过只能验证通用 PAY 显示链路，不能代替微信 SDK、小程序、支付宝页面及应用锁的业务测试。

诊断标签：`SuperCardPayProbe`、`SuperCardProbe`、`SuperCardPayBridge`、`SuperCardPayReady`、`SuperCardRootDisplay`。

## 回到正式构建

去掉 `-PpayProbe=true`，重新编译宿主再合包。标准构建会选择 `src/noPayProbe`，不注册测试 Hook，也不把测试包识别为支付包。测试 APK 单独卸载即可，不需要清理 PAY 保存配置。
