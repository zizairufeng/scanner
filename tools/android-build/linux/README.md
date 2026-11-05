# androidbuild · ScanStock 隔离 Android 构建环境（Linux / x86_64）

本目录是本机上**从零搭出来**的 Android 命令行构建工具链，不依赖 Android Studio、
不污染系统 `PATH` 与 `~/.gradle`。源机器（Windows）上对应的是 `C:\Users\czk\androidbuild\`。

## 目录布局

```
androidbuild/
├── dl/                 下载的原始压缩包 + 运行期临时目录（10MB 的 /tmp 不够用）
│   └── home/           sdkmanager 的 user.home（假 HOME，原 /root 不可写）
├── jdk/jdk17/          OpenJDK 17.0.2（华为云镜像）
├── gradle/gradle-8.9/  Gradle 8.9（腾讯云镜像）
├── sdk/                Android SDK 根目录（= ANDROID_HOME）
│   ├── cmdline-tools/latest/   sdkmanager 强制要求这一层目录名
│   ├── platforms/android-35/
│   ├── build-tools/35.0.0/
│   ├── platform-tools/
│   └── licenses/       已接受，避免 sdkmanager 交互式提问卡死
├── gradle-home/        隔离的 GRADLE_USER_HOME（依赖缓存）
├── out/                release 签名成品
├── logs/               每个阶段的完整日志
├── release.keystore    release 签名密钥（别名/口令均为 scanstock）
├── env.sh              环境变量（source 它即可）
├── build_debug.sh      出 debug APK
└── build_release.sh    出 release APK：assembleRelease + zipalign + apksigner
```

## 用法

```bash
source $HOME/WorkBuddy/scan-app/androidbuild/env.sh   # 导出 JAVA_HOME / ANDROID_HOME / ...
cd $HOME/WorkBuddy/scan-app/androidbuild
./build_debug.sh      # -> ScanStock/app/build/outputs/apk/debug/app-debug.apk
./build_release.sh    # -> androidbuild/out/ScanStock-release.apk（已签名、已对齐）
```

## 版本矩阵（与源机器一致，三者互相咬合，改一个要同时检查另外两个）

| 组件 | 版本 |
|---|---|
| JDK | 17.0.2（OpenJDK，华为云 `mirrors.huaweicloud.com/openjdk/`） |
| Gradle | 8.9（腾讯云 `mirrors.cloud.tencent.com/gradle/`） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Compose 编译器插件 | 2.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |
| Build-Tools | 35.0.0 |
| cmdline-tools | 11076708 (latest) |

> AGP 8.7.3 要求 Gradle ≥ 8.9 且 JDK ≥ 17。

## 换机器 / 换目录时要改的地方

1. `env.sh` 里的 `AB_BASE` 和 `PROJECT`（其余脚本都从这两个变量派生）。
2. `ScanStock/local.properties` 里的 `sdk.dir`（或直接重跑一次环境脚本）。
3. 项目 `gradle/wrapper/gradle-wrapper.properties` 指向 `services.gradle.org`；
   本环境用已装好的 Gradle 8.9，不走 wrapper，无需联网拉发行包。

## 本环境特有的坑（都是在这次搭建里实际踩到的）

| 坑 | 现象 | 处理 |
|---|---|---|
| `sdkmanager` 写 `/root/.android/cache` 失败 | `FileSystemException: 不允许的操作`，随后报 `Failed to download any source lists!` | JVM 的 `user.home` 来自 passwd，改 `$HOME` 无效；必须 `JAVA_OPTS=-Duser.home=<可写目录>` |
| AGP 写 `/root/.android/debug.keystore.lock` 失败 | `:app:validateSigningDebug FAILED`（配置都过不了，编到最后一公里才炸） | `ANDROID_USER_HOME=<可写目录>`。注意它指的是 **`.android` 目录本身**（debug.keystore 直接落在里面），不是它的父目录 |
| 同时设 `ANDROID_USER_HOME` + `ANDROID_PREFS_ROOT` | 配置阶段就挂：`Could not create provider for value source ...AndroidDirectoryCreator` | **只能设 ANDROID_USER_HOME**，两个一起设 AGP 8.7 直接抛异常 |
| `-Dorg.gradle.jvmargs=-Duser.home=...` 无效 | 仍去写 `/root/.android` | AGP 的 `AndroidLocation` 不读构建 JVM 的 `user.home`，必须用 `ANDROID_USER_HOME` |
| Gradle 需要 build-tools 34.0.0 | `Failed to find Build Tools revision 34.0.0`（AGP 自己会去下，但同样撞 `/root` 不可写） | 预先 `sdkmanager --install "build-tools;34.0.0"` |
| 先装包后接受许可 | `--install` 卡在 `Accept? (y/N)`，进程被信号杀掉（退出码 137） | 先 `yes \| sdkmanager --licenses`，许可会写到 `<sdk>/licenses/` |
| 用 zip 解包 Gradle / build-tools | `权限不够`，脚本不可执行 | 解压后统一 `chmod +x`（`gradle/bin/*`、`sdk/build-tools/35.0.0/*`、`jdk/bin/*`、`jdk/lib/jspawnhelper`） |
| `tar` 解 JDK 报 `Cannot change ownership to uid ...` | 用户命名空间内无法 chown，tar 退出码非 0 | 用 `tar --no-same-owner` |
| 容器里 `rm` 被 shim 掉 | `safe-bin/rm: 没有那个文件或目录` | 用 `/usr/bin/rm` |
| `/tmp`、`/var`、`/dev/shm` 各只有 10MB | Gradle / sdkmanager 写临时文件失败 | `TMPDIR` 指到 `androidbuild/dl` |
| Gradle 日志全是 `[1A[K` | 日志不可读 | `--console=plain` + `TERM=dumb` |
| mavenCentral 极慢（≈1.2MB/s） | 首次构建拉依赖要按小时算 | `$GRADLE_USER_HOME/init.d/mirrors.gradle` 换成阿里云镜像（≈19.5MB/s） |

## 交付口径

- APK 必须真的存在：`ls -la` + `sha256sum`。
- `BUILD SUCCESSFUL` 不能证明包是新的（脚本里已踩到「构建先于改动」的时序陷阱）——
  需要证明某段新代码进了包，就在 dex 里做**字节级子串搜索**。
- release 验证必须在构建**完全结束**后跑，`mapping.txt` 与 APK 必须来自同一次构建。
