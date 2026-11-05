# android-build · 无需 Android Studio 的命令行出包工具链

这套脚本用于在一台没有装 Android Studio、没有 JDK、没有 Android SDK 的 Windows 机器上，
从零搭出完整构建环境并产出可安装的 APK。本项目 `dist/` 下的两个 APK 就是这套脚本跑出来的。

## 目录与路径约定

所有脚本顶部都有一个 `BASE` 常量，默认是：

```python
BASE = r"C:\Users\czk\androidbuild"
```

它会被展开成这样的布局：

```
C:\Users\czk\androidbuild\
├── dl\                      下载的原始压缩包（JDK / Gradle / cmdline-tools）
├── jdk\jdk17\               解压后的 JDK 17.0.2
├── gradle\gradle-8.9\       解压后的 Gradle 8.9
├── sdk\                     Android SDK 根目录
│   ├── cmdline-tools\latest\    ← sdkmanager 强制要求在这个位置
│   ├── platforms\android-35\
│   ├── build-tools\35.0.0\
│   ├── platform-tools\
│   └── licenses\            预置的 license 哈希，避免交互式提问卡死
├── gradle-home\             隔离的 GRADLE_USER_HOME，不污染 ~/.gradle
├── out\                     release 签名产物
├── logs\                    每个阶段的完整日志
└── release.keystore         release 签名密钥
```

换机器时改各脚本顶部的 `BASE` / `PROJECT` 两个常量即可。

## 执行顺序

```bash
# 0) 可选：先测各下载源速度，挑最快的组合（国内网络环境差异很大）
python probe_sources.py

# 1) 并行下载三个大件（约 450MB，实测峰值 28MB/s）
python download_all.py

# 2) 解压并摆好目录结构
python unpack.py

# 3) 安装 SDK 组件（platform-tools / platforms;android-35 / build-tools;35.0.0）
python setup_sdk.py

# 4) 构建
python build_apk.py all        # debug：自检 + 写 local.properties + assembleDebug
python build_release.py        # release：assembleRelease + zipalign + apksigner 签名

# 5) 验证（APK 元数据层）
python run_tests.py                              # 跑 JVM 单测并解析 JUnit XML
python verify_apk.py <apk 路径>                   # 结构/清单/权限/签名/对齐 机械校验

# 6) 验证（R8 混淆安全层，release 包专用）
python deep_verify.py                            # 综合深度验收：A/B/C 三类判据 + 解析器自检
python check_r8_strings.py                       # 专项：native 库硬编码类名 + AAR consumer 规则
python check_callchain.py                        # 专项：关键调用链 + mapping 反查改名
python check_renamed.py                          # 专项：被改名类的存续性聚焦核查
```

> 第 6 组的判据设计与结论见 `docs/08-R8混淆安全核查.md`。
> 简言之：「字符串引用」的类必须原名保留，「代码引用」的类允许改名，方法允许被内联——
> 三者混为一谈会产生大量误报。

## 这些脚本踩过并绕开的坑

写下来是因为每一个都会让人卡住半小时以上：

| 坑 | 现象 | 处理 |
|---|---|---|
| cmdline-tools 的目录名 | `sdkmanager` 报错或找不到包 | 必须解压到 `<sdk>/cmdline-tools/latest/bin/`，zip 内层的 `cmdline-tools/` 前缀要剥掉 |
| sdkmanager 交互式提问 | 脚本卡死不动 | 预先写好 `<sdk>/licenses/android-sdk-license` 等文件，把 license 哈希放进去 |
| Git Bash 路径转换 | `keytool` 报 `\c\Users\... 系统找不到指定的路径` | 给原生 exe 传 `C:/Users/...` 正斜杠路径，不要传 `/c/Users/...` |
| **`.bat` 不能直接 spawn** | `subprocess` 抛 `WinError 193` | 一律用 `["cmd", "/c", 脚本路径, ...]` 调用 |
| Gradle 控制台转义 | 日志里全是 `[1A[K` 之类的乱码 | 加 `--console=plain` 并把 `TERM` 设为 `dumb` |
| **`--no-daemon` 仍 fork 进程** | 日志里出现 "single-use Daemon process will be forked" | 正常行为，因为 `gradle.properties` 设了 `org.gradle.jvmargs` |
| AGP 自动补装 build-tools | 日志里突然多出 Build-Tools 34 的安装 | 正常，是 AGP 自身依赖，不用管 |
| **依赖库偷偷加权限** | 清单里出现 `INTERNET` / `ACCESS_NETWORK_STATE` | 在 `AndroidManifest.xml` 用 `tools:node="remove"` 移除 |
| dex header 偏移记错 | 自研 dex 解析脚本把 `method_ids` 当成偏移 80/84（实际那是 `field_ids`），跑出「全部 21 条调用链丢失」的荒谬结论 | 正确偏移是 **88/92**（`method_ids_size/off`）。务必加**解析器自检**：先查 `java.lang.Object.<init>` 这类必然存在的方法能否解析到，查不到就拒绝输出结论——否则工具 bug 会伪装成产品缺陷 |
| Kotlin 常量折叠 | 拿 `"序号,码制,内容,时间"` 去 dex 字符串池精确匹配，报「特征字符串丢失」 | dex 里实际是 `"序号,码制,内容,时间\n"`（`const val EOL = "\n"` 被编译期合并进字面量），改用**子串包含**判定 |
| 判据里写了不存在的类 | 报「`BarcodeScannerImpl` 被 R8 删除」，但该类从来就不存在 | 判据顺序改为：先查 debug（未混淆全集）里有没有，再谈 release 里丢没丢 |
| **验证与重建并发** | release 重跑时 `mapping.txt` 被重写，与旧 APK 不匹配，产出一批假 FAIL | 验证必须在构建**完全结束**后跑；`mapping.txt` 与 APK 必须来自同一次构建 |

## 版本组合（实测通过）

| 组件 | 版本 |
|---|---|
| JDK | 17.0.2（Oracle OpenJDK，华为云镜像） |
| Gradle | 8.9（腾讯云镜像） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |
| Build-Tools | 35.0.0 |
| cmdline-tools | 11076708 (latest) |

> AGP 8.7.3 要求 Gradle ≥ 8.9、JDK ≥ 17。这三者的版本是互相咬合的，改一个要同时检查另外两个。
