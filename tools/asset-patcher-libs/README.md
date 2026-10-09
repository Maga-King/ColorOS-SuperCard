# 补丁再生成的固定依赖

这些文件从原来实际生成已验证资产的 WSL Ubuntu-26.04 环境只读复制，原文件没有改动。JAR 可在 Windows、Linux、macOS 的 JDK 上使用，不要求使用者安装 WSL 或 apktool。

默认命令：

```sh
python tools/rebuild_assets.py
```

JDK 17 或更新版本通过 `JAVA_HOME`/PATH 提供。脚本会先按 [manifest.json](manifest.json) 核对每个 JAR 的 SHA-256，再编译临时源码副本、生成临时资产，并比较现有资产的每个解压 DEX。默认不覆盖资产；只有全部 DEX 相同且显式 `--write` 时才允许替换。

## 固定版本与来源

| JAR | 来源包版本 | 字节数 |
| --- | --- | ---: |
| `dexlib2.jar` | `libsmali-java 2.5.2.git2771eae-4` | 1116212 |
| `smali-util.jar` | `libsmali-java 2.5.2.git2771eae-4` | 27447 |
| `guava.jar` | `libguava-java 32.0.1-1build1` | 3010670 |
| `jsr305.jar` | `libjsr305-java 0.1~+svn49-12` | 18503 |

4 个 JAR 合计 4172832 字节。它们只属于离线补丁工具依赖，不打入手机 APK。

原路径为 `/usr/share/apktool/{dexlib2,smali-util,guava}.jar` 和 `/usr/share/java/jsr305.jar`。完整 SHA-256、原包名和许可证链接均记录在 manifest 中。

## 许可证与版权材料

- smali/dexlib2：保留 [copyright-smali.txt](copyright-smali.txt)，上游 [NOTICE](https://github.com/JesusFreke/smali/blob/v2.5.2/NOTICE) 说明 BSD 条款及部分 Android 代码的 Apache 条款。
- Guava：保留 [copyright-guava.txt](copyright-guava.txt) 和 [Apache-2.0 全文](LICENSE-Apache-2.0.txt)；上游项目为 [google/guava](https://github.com/google/guava)，许可证正文为 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。
- JSR305：保留 [copyright-jsr305.txt](copyright-jsr305.txt)。文件包含 BSD 及部分并发注解的 CC 条款说明；对应 [Debian 原包版权记录](https://sources.debian.org/src/libjsr305-java/0.1~%2Bsvn49-12/debian/copyright/)。

## 验证结果

2026-10-09，在 Windows/JDK21 下使用本目录 4 个原库，卡包 4 个 DEX 和记忆 1 个 DEX 均与已提交资产 SHA-256 完全相同，Pay 的 66 次替换自检通过。原资产未覆盖。

`--backend jadx` 保留为实验比较路径。jadx 1.5.5 的新版 relocated dexlib2 输出 5 个 DEX 均与原库不同，因此不应用它替换已验证资产。默认 `bundled` 后端不需要 JADX JAR。
