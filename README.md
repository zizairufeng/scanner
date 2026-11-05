# 盘点扫描（ScanStock）

仓库 / 门店盘点用的 Android 扫码 App。单手持机、连续快速扫描、结果一键复制或导出。

- **技术栈**：Kotlin + Jetpack Compose + CameraX 1.4.1 + ML Kit Barcode Scanning 17.3.0 + ZXing core 3.5.3（双引擎、全部端侧离线）
- **minSdk 24 / targetSdk 35 / compileSdk 35**，Kotlin 2.0.21，AGP 8.7.3，JDK 17
- **零网络依赖**：识别模型打包进 APK，飞行模式下功能完整
- **7 种码制**：EAN-13 / EAN-8 / UPC-A / Code-128 / Code-39 / QR / Data Matrix，逐个显式开启
- **连续扫描不刷屏**：1.5s 滑动窗口去重；主屏可一键切换三种重复码口径（严格去重 / 计数聚合 / 允许重复）
- **难扫不卡顿（v1.8.2）**：补救链加了 220ms 总预算 + 「有候选框就跳过 DPM 阶梯」 + DPM 阶梯轮转扫描；最坏单帧从 400~700ms 降到约 220ms，难扫场景帧率约 1.5→4.5fps
- **中心对准点（v1.8.2）**：取景框中心画准星、下方一行「方码压在中心 · 四边留白」，对应 ML Kit 的硬约束（Data Matrix 必须压住图像中心才会被识别）
- **自诊断角标（v1.8.2）**：识别帧率掉到 10fps 以下或单帧超 150ms 时，预览左上角显示「识别 6.2 fps · 单帧 178ms」，恢复 3 秒后自动消失
- **复制失效修复（v1.8.7）**：状态栏提示改为 App 自己持有状态 + 显式定时器，不再借用 `SnackbarHostState`（它的超时逻辑长在已删除的 `SnackbarHost` 里，导致事件流永久挂起）；事件处理加异常隔离
- **提示移出画面（v1.8.6）**：取景画面里只剩取景框（四角 + 中心准星），重复码/撤销/失焦/回执全部进画面下方固定 52dp 状态栏，按优先级轮流显示；画面不再被任何浮层遮挡
- **应用图标（v1.8.5）**：深绿渐变底 + 白色四角取景框 + 亮绿条码；矢量三层（背景 / 前景 / 主题单色）+ 五密度位图，覆盖 Android 7 到 15
- **提示统一规范（v1.8.4）**：扫描画面 8 处浮层收敛成 3 个固定落点、3 种语义色、2 档时长；「方码压在中心」不再常驻，识别成功值与重复码提示合并到同一个顶部槽位
- **提示位置统一（v1.8.3）**：删除后的「撤销」和重复码轻提示现在都从屏幕顶部同一条槽位出现，圆角/配色/边距一致；底部不再有浮层压住「复制全部 / 导出 / 清空」
- **重复码轻提示（v1.8.1）**：扫到列表里已有的码，屏幕顶部浮一条两行小提示（第几行、第几次、算没算），2.5 秒自动消失；不拦截触摸、不推挤布局，连扫不受影响
- **小码识别率（v1.7）**：中心区放大补扫 + 对比度拉伸二次尝试 + ZXing 第二引擎 + 点击对焦
- **金属蚀刻码（v1.8）**：DPM（激光蚀刻）Data Matrix 的尺寸阶梯补扫，反色 + 全局直方图二值化，专治 CPU 顶盖那种浅色码
- **复制全部默认纯文本**（每行一个码值），另可选「序号+Tab」「CSV」；单条复制永远只复制码值本身

## 直接安装（最快路径）

> **构建产物不入库。** APK 体积大，`dist/` 与签名密钥 `keystore/` 都已列入 `.gitignore`。
> 现成安装包请从下方发布页获取，或按「从源码构建」自己出包。

现成安装包：<https://scanstock-apk.app.workbuddy.host/>

- **release**：11.36 MB，R8 混淆 + 资源压缩，日常安装用这个
- **debug**：21.1 MB，未混淆，排查问题时更易看堆栈

两者功能完全相同，同包名不同签名，**不能共存**，切换前先卸载另一个。

构建步骤、SHA256 校验方法、签名配置见
[`docs/07-APK构建与安装.md`](docs/07-APK构建与安装.md)。

> ⚠️ **签名密钥需自行生成**：`keytool -genkeypair -keystore release.jks -alias scanstock ...`。
> 口令通过环境变量或本机配置提供，**不要写进任何会被提交的文件**。签名密钥一旦泄露，
> 别人可以签出同签名 APK 覆盖安装你设备上的应用，且无法通过换签补救。

## 从源码构建

```
Android Studio Ladybug 或更新版本 → Open → 选择本目录（ScanStock）
```

工程已包含 `gradle/wrapper/gradle-wrapper.properties`。若 Android Studio 提示缺少 `gradlew` / `gradle-wrapper.jar`，直接让它自动同步一次即可（Studio 会补全 wrapper），或在终端执行 `gradle wrapper --gradle-version 8.9`。

命令行构建（已实测：首次含依赖下载 11 分 03 秒，增量 43 秒）：

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本项目交付时的实际构建环境是命令行工具链（JDK 17 + Gradle 8.9 + Android SDK 35），
> 而非 wrapper。上面 `./gradlew` 的写法在 Android Studio 里首次同步后即可用。

跑单元测试（44 个用例，纯 JVM，不需要真机）：

```bash
./gradlew :app:testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

## 验证状态（诚实版）

**已实测的（本机真跑了）**：

| 验证 | 手段 | 结果 |
|---|---|---|
| **编译** | Gradle 8.9 + JDK 17.0.2 + Android SDK 35 真实构建 | **`BUILD SUCCESSFUL`，Kotlin 编译器零 warning、零 error** |
| **单元测试** | `gradle :app:testDebugUnitTest` | **44/44 通过**（14 去重 + 14 格式化 + 12 准入判定 + 4 默认值），耗时 0.109s |
| **APK 产物** | `app/build-tools/35.0.0` 下的 `aapt2` / `apksigner` / `zipalign` 逐项校验 | 结构、清单、权限、签名、对齐**全部通过** |
| 包名 / import / 跨文件引用 | `python tools/audit_imports.py app/src/main/java` | 17 文件 / 255 条 import，**三类全通过** |
| 去重语义 | `python tools/verify_dedupe.py` | 8 场景 × 3 策略 × 4 组帧率。本方案 **7/8**；「固定窗口」写法 5/8、「不去重」0/8 |
| 复制输出 | `python tools/verify_formatter.py` | 与单测期望字面量交叉验证 **18/18 通过** |
| **R8 混淆安全性** | `python tools/android-build/deep_verify.py` 等 4 个脚本 | **通过**：8 个「字符串引用类」原名保留、17 个「代码引用类」全部存在、12 条调用链完整。详见 `docs/08` |

编译实测抓出的两个真问题（静态检查永远发现不了）：

- **权限溢出**：ML Kit 依赖往合并清单里注入了 `INTERNET` + `ACCESS_NETWORK_STATE`，
  与「仅声明 CAMERA / 端侧离线」直接冲突。已用 `tools:node="remove"` 移除。
- **`@OptIn` 语义失效**：`BarcodeAnalyzer.kt` 里 `@OptIn` 解析到了 Kotlin 版而非
  androidx 版，那句注解实际不生效。已修，修后零 warning。

最能说明问题的两个数字（`tools/verify_dedupe.py` 仿真，20fps）：

- **固定对准同一个码 10 秒**：本方案写入 **1 条**；「只在计入时刷新」的固定窗口写法写入 **7 条**；完全不去重写入 **189 条**。
- **1500ms 窗口下，间隔 300ms 的快速补扫同款会被吞掉**——把滑杆调到 300ms 可解决，且仍不刷屏。

**未验证（需要真机）**：CameraX 实际出图、ML Kit 真实识别率、连续扫描帧率与功耗、
震动/提示音/剪贴板、权限拒绝降级页、横竖屏旋转、release 包混淆后是否有反射问题。
编译通过说明「代码写对了」，不代表「功能都对」，清单见 `docs/06-验证报告.md` 第 5 节。

## 目录结构

```
ScanStock/
├── LICENSE                              Apache-2.0
├── .gitignore                           密钥 / 产物 / 本机配置均在此排除
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts                 依赖清单（含取舍说明注释）
│   ├── proguard-rules.pro               ML Kit 反射注册的 keep 规则
│   ├── src/main/
│       ├── AndroidManifest.xml          CAMERA + VIBRATE，无存储权限
│       ├── res/values/{strings,themes}.xml
│       ├── res/drawable/ic_launcher_{background,foreground,monochrome}.xml  自适应图标三层（矢量）
│       ├── res/mipmap-*/ic_launcher{,_round}.png                          五密度位图（Android 7 兜底）
│       └── java/com/example/scanstock/
│           ├── MainActivity.kt                      edge-to-edge 宿主
│           ├── domain/
│           │   ├── Models.kt                        ScanItem / DetectedCode / CopyFormat
│           │   ├── DedupeTracker.kt                 ★ 1.5s 滑动窗口去重（只管时间窗）
│           │   ├── CodeAdmission.kt                 ★ 帧级准入：同帧去重 + 可开关的跨帧去重
│           │   └── CopyFormatter.kt                 ★ 三种复制格式 + CSV 转义
│           ├── camera/
│           │   ├── BarcodeFormats.kt                码制白名单 + 扫描器工厂
│           │   ├── BarcodeAnalyzer.kt               ★ 分析器 + 背压闸门
│           │   └── ImageDecoder.kt                  相册图片解码
│           ├── feedback/FeedbackPlayer.kt           震动 30ms + 提示音
│           ├── util/TaskAwait.kt                    Task → 挂起函数
│           └── ui/
│               ├── ScanUiState.kt                   唯一状态源 + 一次性事件
│               ├── ScanViewModel.kt                 ★ 状态容器
│               ├── ScanScreen.kt                    权限流 + 剪贴板 + 装配
│               ├── components/
│               │   ├── CameraPreviewCard.kt         相机绑定 / 闪光灯 / 双击缩放 / 取景框
│               │   ├── ResultListPanel.kt           结果列表
│               │   ├── SettingsSheet.kt             设置面板
│               │   └── PermissionPanel.kt           权限降级页
│               └── theme/Theme.kt                   固定配色（关闭动态色）
│   └── src/test/java/com/example/scanstock/
│       ├── domain/DedupeTrackerTest.kt              14 个用例（去重语义 + 边界）
│       ├── domain/CopyFormatterTest.kt              14 个用例（三种格式 + CSV 转义）
│       ├── domain/CodeAdmissionTest.kt              12 个用例（去重开关 + 同帧去重）
│       └── ui/ScanUiStateTest.kt                     4 个用例（两个默认值回归）
├── tools/                              无需 JDK 的验证脚本（Python 标准库）
│   ├── audit_imports.py                包名 / import / 跨文件引用静态审计
│   ├── verify_dedupe.py                去重算法仿真（8 场景 × 3 策略 × 4 组帧率）
│   └── verify_formatter.py             复制输出交叉验证（18 项）
└── docs/
    ├── 01-假设确认.md          ★ 先读这个
    ├── 02-架构设计.md          数据流 / 模块划分 / ASCII 架构图
    ├── 03-关键实现-上.md       权限、相机绑定、图像分析
    ├── 03-关键实现-下.md       去重、状态容器、列表、复制
    ├── 04-边界处理.md          10 类边界场景的处理方式
    ├── 05-验收清单.md          真机逐条勾选
    ├── 06-验证报告.md          ★ 仿真数据 + 编译/测试实测结果 + 未验证项
    ├── 07-APK构建与安装.md     ★ APK 位置 / 安装方式 / 可复现的构建过程
    └── 08-R8混淆安全核查.md    ★ release 包混淆后符号生存性核查（含工具自审）
```

## 四个最关键的实现点

1. **连续扫描不暂停**：`Preview` 与 `ImageAnalysis` 是两个独立 UseCase。分析流用
   `STRATEGY_KEEP_ONLY_LATEST` 丢中间帧，`imageProxy.close()` 放在 ML Kit 的
   `addOnCompleteListener` 里当背压闸门 —— 预览永不掉帧，且不会出现多帧并发解析。

2. **两级去重，其中跨帧那级可关**：`DedupeTracker.shouldAccept()` 里**无论是否计入都刷新时间戳**，
   只要码持续在画面里就永远进不了「距上次上报满 1.5 秒」的状态，所以固定对着同一个码扫 10 秒
   只产生 1 条。判定入口收在 `CodeAdmission.admit()`：先做**同帧去重**（一帧里多个相同码只记
   第一条，不受开关影响），再做**跨帧时间窗去重**（受主屏「去重 开/关」chip 控制）。关闭后逐帧
   留痕（20fps 下对准一个码 10 秒约 189 条），属预期行为，因此开关在关闭态用警示配色高亮。

3. **复制全部默认纯文本**：`CopyFormat.PLAIN` 排在枚举首位（枚举顺序即设置面板显示顺序），
   `ScanUiState.copyFormat` 默认取它，当前格式直接显示在底部按钮上。单条复制与格式设置**无关**，
   永远只有码值本身，所以它的 Snackbar 提示语单独写、不并列格式名。这三个默认值由
   `ScanUiStateTest` + `CodeAdmissionTest` 锁住，防止回归。

4. **权限拒绝不留白屏**：三态处理（未申请 / 可再问 / 永久拒绝），且顶栏「相册」入口
   始终可用 —— 没有相机权限也能靠 Photo Picker 完成盘点。

## 与需求的两处显式偏离

| 偏离 | 原因 |
|---|---|
| 额外声明了 `VIBRATE` 权限 | 需求写「仅声明 CAMERA」，但 Android 规定调用 `Vibrator` / `VibratorManager` 必须持有 `VIBRATE`，否则震动静默失效。它是 normal 级权限，安装即授予、不弹窗、不涉及隐私授权。详见 `docs/01-假设确认.md` 第 2 条。 |
| 使用了 `enableAllPotentialBarcodes()` | 不是「多开码制」，而是打开 ML Kit 的「检测到条码但解码失败」信号，用于暗光/模糊时提示用户靠近。这些结果**只做提示，绝不进列表**。 |

## 未实现（需求明确排除 / 需确认后再做）

- 历史记录持久化：列表只存于 ViewModel，App 被系统回收即丢失。
- 文件导出（xlsx / csv 文件）与后端上报：需要新增存储/网络权限，与「仅 CAMERA」约束冲突。
- 同商品分组计件（同一码聚合 + 数量字段）：当前是「同码多次扫描 = 多条记录」。
- 二维码生成、账号体系、iOS 版本：明确排除。

以上若需要，见 `docs/01-假设确认.md` 的「待确认」小节，那里给了每项的最低成本实现路径。
