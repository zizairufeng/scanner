# ScanStock

Android 盘点扫描工具。单手持机连续扫码，结果可单条复制、批量复制或导出。

## 功能

- 七种码制：EAN-13、EAN-8、UPC-A、Code-128、Code-39、QR、Data Matrix，逐个显式开启
- 连续扫描按 1.5 秒时间窗去重，主屏可切换严格去重 / 计数聚合 / 允许重复三种口径
- 金属蚀刻码（DPM Data Matrix）：反色 + 直方图二值化 + 尺寸阶梯补扫
- 中心区放大补扫 + ZXing 第二引擎，小码和浅色码也能读
- 补救链控制在 220ms 总预算内，难扫场景不拖慢预览帧率
- 复制格式支持纯文本（每行一个码值，默认）、序号 + Tab、CSV；单条复制始终只复制码值本身
- 取景画面只保留取景框和中心准星，重复码、撤销、失焦等提示都在画面下方的固定状态栏

全部识别在端侧完成，模型打包进 APK，应用不申请网络权限，飞行模式下功能完整。

技术栈：Kotlin 2.0.21 + Jetpack Compose + CameraX 1.4.1 + ML Kit Barcode Scanning 17.3.0 + ZXing core 3.5.3。
minSdk 24 / targetSdk 35 / compileSdk 35，AGP 8.7.3，JDK 17。

## 安装

APK 从 GitHub Releases 下载：

https://github.com/zizairufeng/scanner/releases/latest

要求 Android 7.0 及以上。应用只声明 `CAMERA` 和 `VIBRATE` 两个权限。

## 构建

用 Android Studio Ladybug 或更新版本打开本目录即可。工程含 `gradle/wrapper/gradle-wrapper.properties`；
若 IDE 提示缺少 `gradlew` / `gradle-wrapper.jar`，让它同步一次会自动补全，或手动执行
`gradle wrapper --gradle-version 8.9`。

命令行构建：

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

跑单元测试（纯 JVM，不需要真机）：

```bash
./gradlew :app:testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

签名密钥需要自己生成：

```bash
keytool -genkeypair -keystore release.jks -alias scanstock ...
```

口令通过环境变量或本机配置传入，不要写进任何会被提交的文件。签名密钥泄露意味着别人可以签出同签名
APK 覆盖安装，且无法通过换签补救。

## 目录结构

```
ScanStock/
├── LICENSE                    Apache-2.0
├── .gitignore                 密钥 / 产物 / 本机配置
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro     ML Kit 反射注册的 keep 规则
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── res/drawable/ic_launcher_{background,foreground,monochrome}.xml
│   │   ├── res/mipmap-*/ic_launcher{,_round}.png
│   │   └── java/com/example/scanstock/
│   │       ├── MainActivity.kt
│   │       ├── domain/        Models / DedupeTracker / CodeAdmission / CopyFormatter
│   │       ├── camera/        BarcodeFormats / BarcodeAnalyzer / ImageDecoder
│   │       ├── feedback/      FeedbackPlayer（震动 + 提示音）
│   │       ├── util/          TaskAwait
│   │       └── ui/            ScanUiState / ScanViewModel / ScanScreen / components / theme
│   └── src/test/java/com/example/scanstock/
├── tools/                     Python 标准库写的验证脚本
│   ├── audit_imports.py       包名 / import / 跨文件引用审计
│   ├── verify_dedupe.py       去重算法仿真
│   └── verify_formatter.py    复制输出交叉验证
└── docs/                      设计与验收文档，从 01 开始读
```

## 实现要点

预览与分析分离。`Preview` 和 `ImageAnalysis` 是两个独立 UseCase。分析流用
`STRATEGY_KEEP_ONLY_LATEST` 丢中间帧，`imageProxy.close` 放在 ML Kit 的 `addOnCompleteListener`
里当背压闸门，因此预览不掉帧，也不会多帧并发解析。

两级去重。`DedupeTracker.shouldAccept` 无论是否计入都刷新时间戳，只要码还在画面里就进不了
「距上次上报满 1.5 秒」的状态，对着同一个码扫 10 秒只产生 1 条。判定入口收在 `CodeAdmission.admit`：
先做同帧去重（一帧里多个相同码只记第一条，不受开关影响），再做跨帧时间窗去重（受主屏开关控制）。
关掉后逐帧留痕，20fps 对准一个码 10 秒约 189 条，属预期行为，所以开关在关闭态用警示配色。

权限拒绝不留白屏。未申请 / 可再问 / 永久拒绝三态处理，顶栏「相册」入口始终可用，
没有相机权限也能靠 Photo Picker 完成盘点。

## 验证情况

已完成：Gradle 8.9 + JDK 17.0.2 + Android SDK 35 真实构建通过，Kotlin 零 warning；单元测试全部通过
（去重语义、复制格式、准入判定、默认值回归）；`aapt2` / `apksigner` / `zipalign` 校验结构与签名；
`tools/` 下的静态审计与仿真脚本通过；release 包 R8 混淆后符号生存性核查见 `docs/08`。

构建过程中实际修掉的问题：

- ML Kit 依赖往合并清单注入了 `INTERNET` 和 `ACCESS_NETWORK_STATE`，与「端侧离线」冲突，已用
  `tools:node="remove"` 移除。
- `BarcodeAnalyzer.kt` 里的 `@OptIn` 解析到了 Kotlin 版而非 androidx 版，注解实际不生效，已修。

未验证（需要真机）：CameraX 实际出图、ML Kit 真实识别率、连续扫描帧率与功耗、震动与提示音、
剪贴板、权限拒绝降级页、横竖屏旋转、release 包混淆后是否有反射问题。编译通过只说明代码写对了，
清单见 `docs/06-验证报告.md` 第 5 节。

## 已知事项

| 事项 | 说明 |
|---|---|
| 额外声明 `VIBRATE` | 调用 `Vibrator` / `VibratorManager` 必须持有该权限，否则震动静默失效。normal 级权限，安装即授予，不弹窗。 |
| 使用了 `enableAllPotentialBarcodes` | 打开的是 ML Kit「检测到条码但解码失败」的信号，用于暗光或模糊时提示靠近。这些结果只做提示，不进列表。 |

## 未实现

- 历史记录持久化：列表只存在 ViewModel 里，App 被系统回收即丢失
- 文件导出与后端上报：需要额外存储 / 网络权限，与「仅 CAMERA」的约束冲突
- 同商品分组计件：当前是同码多次扫描记多条
- 二维码生成、账号体系、iOS 版本

以上如果需要，`docs/01-假设确认.md` 的「待确认」小节里给了每项的最低成本实现路径。

## 许可

Apache-2.0，见 `LICENSE`。
