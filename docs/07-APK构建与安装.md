# 07 · APK 构建与安装

本文件记录真实构建出来的可安装 APK：它在哪、怎么装、怎么来的、以及哪些环节
仍未在真机上验证过。

---

## 1. 成品在哪里

| 文件 | 大小 | 用途 |
|---|---|---|
| `dist/ScanStock-1.3-release.apk` | **11.04 MB** | **推荐安装这个**。R8 混淆 + 资源压缩，性能最好、无调试标记 |
| `dist/ScanStock-1.3-debug.apk` | 20.60 MB | 备选。未混淆，排查问题时更容易看堆栈 |

两个包的**功能完全相同**，差别只在编译优化。同包名不同签名，不能同时装在一台手机上，
切换时需要先卸载另一个。

校验值（用于确认传输过程没损坏）：

```
SHA256  ScanStock-1.3-release.apk
        2b8d9c3f2d61aff1ac5ecbc32c222fa9496ee636454458a53a4e41c00b92f539
        （2026-09-17 更新：补充 barhopper 混淆保护规则后重新构建，
          包体积未变，详见 08-R8混淆安全核查.md §5）

SHA256  ScanStock-1.3-debug.apk
        a818250e4236607cd8544e371f70f01a308fe0047aaf59f76e9ccaa3c2d6f250
```

---

## 2. 怎么装到手机上

### 方式一：直接拷贝安装（最省事）

1. 把 `ScanStock-1.3-release.apk` 传到手机（微信文件传输 / USB 拷贝 / 网盘均可）
2. 在手机上点开这个文件
3. 系统会提示「未知来源应用」→ 选择允许来自此来源的应用
4. 确认安装 → 桌面出现「盘点扫描」图标

### 方式二：adb 安装（可看安装日志）

```bash
adb install -r ScanStock-1.3-release.apk
# 若提示 INSTALL_FAILED_UPDATE_INCOMPATIBLE，说明手机上已有同包名不同签名的版本：
adb uninstall com.example.scanstock
adb install ScanStock-1.3-release.apk
```

### 首次打开会要相机权限

这是本 App **唯一**会向用户申请的运行时权限。拒绝后会进入降级页——**仍然可以用
「从相册识别」功能**，不会白屏也不会闪退。

---

## 3. 这个 APK 是怎么构建出来的

构建环境是本轮**从零搭建**的（本机原本没有 Java / Kotlin / Gradle / Android SDK）。
全过程可复现，脚本都在 `C:\Users\czk\androidbuild\`。

### 3.1 工具链版本（实测通过的最小组合）

| 组件 | 版本 | 来源 |
|---|---|---|
| JDK | Oracle OpenJDK **17.0.2** | 华为云镜像 `mirrors.huaweicloud.com/openjdk` |
| Gradle | **8.9** | 腾讯云镜像 `mirrors.cloud.tencent.com/gradle` |
| Android SDK cmdline-tools | 11076708 (latest) | `dl.google.com` |
| Android SDK Platform | **android-35** | `dl.google.com` |
| Android Build-Tools | **35.0.0** | `dl.google.com` |
| AGP | 8.7.3 | Google Maven |
| Kotlin | 2.0.21（含 Compose 编译插件） | Maven Central |

> AGP 在首次构建时自动补装了 `Build-Tools 34.0.0`，这是它自身的依赖，属正常行为。

### 3.2 构建步骤

```bash
# 1. 下载三个大件（并行，约 450MB）
python C:/Users/czk/androidbuild/download_all.py

# 2. 解压并按 SDK 要求的目录布局摆放
python C:/Users/czk/androidbuild/unpack.py

# 3. 安装 SDK 组件（platform-tools / platforms;android-35 / build-tools;35.0.0）
python C:/Users/czk/androidbuild/setup_sdk.py

# 4a. 构建 debug 包
python C:/Users/czk/androidbuild/build_apk.py all

# 4b. 构建 + 签名 release 包
python C:/Users/czk/androidbuild/build_release.py

# 5. 机械验收（结构 / 清单 / 权限 / 签名 / 对齐）
python C:/Users/czk/androidbuild/verify_apk.py <apk 路径>
```

### 3.3 实际耗时与结果

| 阶段 | 耗时 |
|---|---|
| 下载工具链（并行 3 线程） | 约 3 分钟（峰值 28 MB/s） |
| 解压 | 约 20 秒 |
| 安装 SDK 组件 | 1 分 11 秒 |
| 构建 debug（首次，含依赖下载） | 11 分 03 秒 |
| 构建 debug（增量，改一行后） | 43 秒 |
| 构建 release（含 R8） | 5 分 12 秒 |

编译结果：`BUILD SUCCESSFUL`，Kotlin 编译器零 warning、零 error。

---

## 4. 构建过程发现并修掉的两个真问题

这两个都不是"理论上可能"，是编译和验收实测撞出来的。

### 4.1 权限溢出：APK 里混进了网络权限

首次构建后跑验收，发现合并清单里的权限不是 2 条而是 **5 条**：

```
android.permission.CAMERA                      ← 我们要的
android.permission.VIBRATE                     ← 我们要的（系统强制）
android.permission.INTERNET                    ← ML Kit 依赖注入，多余
android.permission.ACCESS_NETWORK_STATE        ← ML Kit 依赖注入，多余
com.example.scanstock.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION  ← AndroidX 注入
```

`com.google.mlkit:barcode-scanning` 的清单里带了两条网络权限，只为它的**统计上报**服务，
和条码识别功能无关。而本项目用的是**捆绑模型**版本（模型已打进 APK，见
`lib/*/libbarhopper_v3.so` 四个 ABI），识别 100% 端侧完成。

需求写的是「仅声明 CAMERA」「端侧离线，不依赖网络」——多一个联网权限是直接违背承诺的。
已在 `AndroidManifest.xml` 中显式移除：

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

重新构建后验收确认：**网络权限已消失**，只剩 CAMERA + VIBRATE + AndroidX 那条内部权限。

第三条 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` **必须保留**：它由 `androidx.core`
在合并清单时自动注入，是 signature 级自定义权限（不向用户申请、不弹窗），用于
Android 13+ 内部动态注册广播接收器的安全隔离。删掉会让 `registerReceiver` 抛异常。

### 4.2 `@OptIn` 注解语义失效

Kotlin 编译器给出唯一一条 warning：

```
w: BarcodeAnalyzer.kt:34:5 Annotation 'androidx.camera.core.ExperimentalGetImage'
   is not annotated with '@RequiresOptIn'. '@OptIn' has no effect.
```

原因：CameraX 的 `ExperimentalGetImage` 用的是 androidx 版的 `@RequiresOptIn`，
而代码里 `@OptIn` 解析到的是 Kotlin 标准库版本，两者不通用，所以那句 `@OptIn`
形同虚设。已改为显式导入 `androidx.annotation.OptIn`。修复后重建，
编译器零 warning。

---

## 5. APK 验收结果（机械校验，非目测）

| 检查项 | 结果 |
|---|---|
| ZIP 完整性 | 通过 |
| `AndroidManifest.xml` / `resources.arsc` | 存在 |
| dex 文件 | release 1 个（R8 已合并）、debug 6 个 |
| 包名 | `com.example.scanstock` |
| minSdk / targetSdk | **24 / 35** 符合需求 |
| 权限 | **3 条**（CAMERA + VIBRATE + AndroidX 内部签名级权限） |
| 危险权限（INTERNET / 存储 / 定位等） | **0 条** |
| 启动 Activity | `com.example.scanstock.MainActivity` |
| 声明的 native ABI | arm64-v8a、armeabi-v7a、x86、x86_64 |
| ML Kit 离线模型 | `libbarhopper_v3.so` 四个 ABI 全部打包 |
| 签名 | release：v2 + v3 通过；debug：v2 通过 |
| zipalign 4 字节对齐 | 通过 |

---

## 6. 签名信息

release 包用项目自带 keystore 签名（密钥材料在 `keystore/scanstock-release.jks`）：

| 项 | 值 |
|---|---|
| keystore 文件 | `keystore/scanstock-release.jks`（JKS 格式） |
| store password | `scanstock` |
| key alias | `scanstock` |
| key password | `scanstock` |
| 证书指纹 (SHA-256) | `66:56:73:95:CA:C3:F4:71:D8:C0:60:BF:3F:06:9A:2F:70:83:D3:2C:68:AB:2F:8D:D9:14:EC:81:79:F6:EF:92` |
| 有效期 | 10950 天（约 30 年） |

> 注意：这是为你自用生成的密钥，密码是公开的弱口令。**如果要上架应用商店或分发给他人，
> 必须重新生成一个强密码的 keystore 并妥善保管**——更换签名后，旧版本无法覆盖升级，
> 用户需要先卸载。
>
> 另：keystore 未提交到任何公共仓库，仅在本地项目目录中。

---

## 7. 本轮仍未验证的部分（诚实清单）

编译通过 ≠ 功能正确。以下项目本机**确实做不到**，需要你在真机上确认：

| 未验证项 | 为什么做不到 | 怎么验证 |
|---|---|---|
| 相机预览实际出图 | 无设备、无摄像头 | 装上后打开，画面应实时流畅 |
| ML Kit 真实识别率 | 无实物条码 | 见 `05-验收清单.md` A~G 组 |
| 连续扫描的帧率与发热 | 无设备 | 连续扫 200 个商品，观察是否掉帧 |
| 震动与提示音 | 无振动马达 | 扫成功后应有 30ms 震动 + 短音 |
| 剪贴板复制 | 无系统剪贴板 | 复制全部后粘贴到备忘录核对 |
| 相册识别 | 无相册 | 选一张有条码的照片 |
| 权限拒绝降级页 | 无系统权限弹窗 | 首次拒绝权限，应进降级页而非白屏 |
| 移除网络权限后是否影响功能 | 无设备 | **重点验证**：开飞行模式，冷启动 App，完整扫一遍（见 4.1 节） |
| 横竖屏切换后列表是否保留 | 无设备 | 旋转 10 次，列表条数应不变 |
| release 包混淆后是否有反射问题 | 无设备 | release 包完整跑一遍主链路 |

### 体积构成与 v1.2 的瘦身（已做）

v1.1 的 release 包是 **22.51 MB**，其中 `lib/` 下的 native 库占 19.47MB（86.5%）——
四个 ABI 各打了一份 ML Kit 的 `libbarhopper_v3.so`：

| ABI | 未压缩大小 | 手机用得到吗 |
|---|---|---|
| x86 | 5.84 MB | （只有模拟器/少数 x86 平板） |
| x86_64 | 5.64 MB | |
| arm64-v8a | 4.76 MB | 现在绝大多数手机 |
| armeabi-v7a | 3.12 MB | 老款 32 位机型 |

v1.2 在 `app/build.gradle.kts` 的 `packaging` 里剔除了 x86 / x86_64：

```kotlin
packaging {
    // 必须写在 android{} 评估期。试过 androidComponents.onVariants 里按 release 单独剔除，
    // AGP 8.7 直接报 "It is too late to modify excludes"。
    jniLibs.excludes += setOf("lib/x86/**", "lib/x86_64/**")
}
```

效果：**release 22.51 MB → 11.04 MB（-51.7%）**，debug 32.19 MB → 20.60 MB。
代价只有一条：在 x86 模拟器上 debug 会加载不到 .so 而崩溃，
届时把上面那一行注释掉即可。

没再继续砍的部分（都验证过不划算或有风险）：

- `assets/mlkit_barcode_models/*.tflite`（0.84MB）是 ML Kit 条码识别的模型文件，删了会直接失效；
- 分析分辨率保持 1280×720：降到 640×480 确实更省，但远距离解 1D 码的识别距离会明显缩短。

---

## 8. 如果改代码后要重新出包

```bash
# debug
python C:/Users/czk/androidbuild/build_apk.py build

# release（自动签名）
python C:/Users/czk/androidbuild/build_release.py

# 验收
python C:/Users/czk/androidbuild/verify_apk.py <apk 路径>
```

工具链环境已就绪，第二次构建不需要重新下载任何东西。实测耗时：debug 增量 25~116 秒；release 3 分 27 秒。

> **release 构建脚本里有一处刻意的取舍：带了 `-x lintVitalRelease -x lintVitalAnalyzeRelease`。**
> AGP 在 `assembleRelease` 时会顺带跑一次全量 lint 分析（`lintVital`），实测在本机耗时约 12 分钟
> ——整轮 release 由 **3 分 27 秒**涨到 17 分 41 秒。它检查的是「上架前必须修的问题」，
> 与本项目「出一个可安装的包」的目标无关，且逻辑正确性已由 R8 深度核查（`deep_verify.py`）
> 与 44 个单测覆盖，故跳过。确实需要 lint 时单独执行：
>
> ```bash
> gradle :app:lintVitalRelease
> ```

注意 `local.properties` 里记录了本机 SDK 路径，换成 Android Studio 打开工程时
IDE 会自动改写它。

---

## 内网下载（纯 Node，零依赖）

不想插数据线时，用 `tools/serve-apk.js` 起一个局域网下载服务，手机在同一 Wi-Fi 下直接下载：

```bash
node tools/serve-apk.js                        # 默认 8080，绑定本机所有外部网卡
node tools/serve-apk.js --port 9000            # 换端口（端口被占用时会明确提示）
node tools/serve-apk.js --host 192.168.2.150   # 只绑指定地址（可重复、可逗号分隔）
node tools/serve-apk.js --host 0.0.0.0         # 绑所有网卡（含回环）
```

启动后控制台会**按通路分组**打印可访问地址：

```
  同一 Wi-Fi 的手机（手机连的是家里/办公室的 Wi-Fi）
    http://192.168.2.150:8080/

  Tailscale 设备（装了 Tailscale 并加入同一 tailnet，任意网络都能下）
    http://100.85.243.81:8080/
```

打开是一个索引页，列出两个包并显示各自的 SHA256，便于在手机上核对文件完整性。

防火墙（重要，已实测确认）：

- 本机 Windows 防火墙的域 / 专用 / 公用三个配置文件全部处于「关闭」状态，
  因此入站连接不会被拦，**不需要**额外添加放行规则。
- 本机账号**非管理员**，`netsh advfirewall firewall add rule ...` 会返回
  「请求的操作需要提升」，加不了规则——当前不需要也就无所谓；
  哪天防火墙被打开，需要用管理员权限执行：

  ```cmd
  netsh advfirewall firewall add rule name="ScanStock-APK" dir=in action=allow protocol=TCP localport=8080
  ```

- 服务默认为每个外部网卡各起一个 listener（同一端口、同一份内容），
  但**不绑回环**——用 `127.0.0.1` 是访问不到的（探测存活请用 `netstat`，别用连通性测试）。
  本机有两块外部网卡时两条路都通：同 Wi-Fi 的手机走 `192.168.2.150`，
  装了 Tailscale 的设备走 `100.85.243.81`（在外面也能下）。
  要收窄暴露面就用 `--host <地址>` 只绑一个。
- 只提供 `dist/` 下的两个 APK，路径白名单命中不了的一律 404，不列目录、不接受目录穿越。
- 若手机打不开：先确认电脑与手机在同一 Wi-Fi 子网，再排查路由器是否开了
  AP 隔离 / 客户端隔离（开了的话设备之间无法互访，需到路由器后台关掉）。
  如果手机装了 Tailscale 并与电脑同一 tailnet，可以直接用 `http://100.85.243.81:8080/`
  绕过全部局域网问题（不依赖同一 Wi-Fi、不受 AP 隔离影响）。

已知实现坑（改这个脚本时注意）：`shaOf` 是 `Map`，遍历键必须写 `[...shaOf.keys]`；
写成 `Object.keys(shaOf)` 会得到空数组，表现为「首页正常、文件全 404」。

---

## 运行时性能与内存优化（v1.2）

体积之外，这一版还改了 4 处运行时热点：

| 优化 | 改前 | 改后 | 收益 |
|---|---|---|---|
| 去重查询用的「已录入码值集合」 | 每次识别回调都把 `items` 拷成一个 HashSet | ViewModel 里增量维护 `HashMap<String,Int>`（value→次数），把 keySet 视图传给 `CodeAdmission` | 20fps 下每秒少掉约 20 次 O(n) 遍历 + 20 次临时 HashSet 分配，GC 抖动显著降低（列表越长收益越大） |
| 时间格式化 | 每次识别 `new SimpleDateFormat(...)` | 复用一个实例（只在主线程/ML Kit 主线程回调里调用，无并发） | SimpleDateFormat 构造很贵（内部带 Calendar + DecimalFormat），消除每次识别的固定开销 |
| 「疑似条码，再靠近一点」提示 | 每帧回调都 `potentialTick + 1` | 节流到 300ms 一次 | 空闲（对着没码的画面）时 Compose 重组次数从约 20 次/秒 降到 最多 3 次/秒 |
| 相册图片识别 | `InputImage.fromFilePath` 直接解码整张原图 | 先 `inJustDecodeBounds` 读尺寸 → `ImageSampling.inSampleSize` 算采样率 → 解码后最长边 ≤1600；EXIF 方向改用 `InputImage.fromBitmap(bmp, degrees)` 传给 ML Kit（不再生成旋转副本），用完 `recycle` | 1200 万像素照片的 Bitmap 从约 **46MB** 降到约 **7.7MB**，这是本 App 最大的单点内存开销；同时不再有旋转大图副本 |

采样率是纯函数，已用 7 个单测锁住行为（`ImageSamplingTest`）：必须是 2 的幂、
缩放后最长边不超上限、非法尺寸返回 1、极端长条图不会死循环。

没有做的优化及原因：ImageAnalysis 分辨率保持 1280×720（降分辨率省 CPU/内存，
但远距离解 1D 码会明显变难）；列表已是 `LazyColumn + key`，无需改。

---

## v1.6.0 产物与下载服务（2026-10-16，Linux 机器）

这一版是搬到新机器（Linux）之后重新构建并复现的。构建环境的搭建过程见
`tools/android-build/linux/README.md`；本次改动的说明见 `docs/17-v1.6-闪退日志与序号重排.md`。

### 产物

| 文件 | 大小 | SHA256（前 16 位） |
|---|---|---|
| `dist/ScanStock-1.6.0-release.apk` | 11,609,848 B | `451d8dc9e17c2451` |
| `dist/ScanStock-1.6.0-debug.apk` | 21,663,593 B | `627c9f7a14342dba` |

versionCode = 11，与 v1.5.0（versionCode 10）同一 release.keystore，可直接覆盖安装。

构建（隔离环境，不依赖 Android Studio）：

```bash
source $HOME/WorkBuddy/scan-app/androidbuild/env.sh
cd $HOME/WorkBuddy/scan-app/androidbuild
./build_debug.sh      # -> ScanStock/app/build/outputs/apk/debug/app-debug.apk
./build_release.sh    # -> androidbuild/out/ScanStock-release.apk（已签名、已对齐）
```

### 起下载服务

```bash
bash tools/serve-apk.sh                      # 前台跑，Ctrl+C 停
nohup bash tools/serve-apk.sh > /tmp/serve-apk.log 2>&1 &   # 后台常驻
grep -A6 监听 /tmp/serve-apk.log                # 看该用哪个地址
pkill -f serve-apk.js                           # 停掉
```

`serve-apk.sh` 是个薄壳：找到 node（PATH 里的、WorkBuddy 托管目录里的、或仓库自带的便携版），
然后把 `tools/serve-apk.js` 拉起来。之所以不直接敲 `node tools/serve-apk.js`，是因为换机器后
node 不一定在 PATH 里，而且容器/沙箱里还会被注入一个指向宿主 App 的 `NODE_OPTIONS`
（那个 preload 模块在容器里不存在，Node 会在启动阶段就 `MODULE_NOT_FOUND` 退出，
报错看着像脚本坏了，其实跟脚本无关）。

默认绑所有外部网卡 + 回环，启动报告里分组打出来：

```
同一 Wi-Fi 的手机（手机连的是家里/办公室的 Wi-Fi）
  http://192.168.2.233:8080/          ← 手机用这个

仅本机自测（手机打不开这个地址）
  http://127.0.0.1:8080/              ← 电脑自己的浏览器用这个，验证服务是否正常
```

浏览器打开首页会列出 `dist/` 下所有 `ScanStock-*.apk`（含 SHA256 与下载链接），
点「下载」手机即可安装。

> 回环是 v1.6 才默认绑上的。以前只绑外部地址，于是在电脑本机敲
> `http://127.0.0.1:8080/` 必然失败——而这会被直接误判成「服务没起来」，
> 白白去查一堆无关的东西。现在本机自测立刻可通，报告里也标注了「手机打不开这个地址」。

> v1.6 起这个脚本**不再硬编码版本号**：启动时扫描 `dist/` 里匹配 `ScanStock-*.apk`
> 的文件。以前写死文件名，出了新版之后服务照样正常、下载也成功，只是下到的是旧版
> ——这是最难发现的一类故障。发新版本时只要把 APK 放进 `dist/` 即可，不用改脚本。

#### 手机打不开时，按这个顺序查

启动报告里也印了同一份清单：

1. **窗口还开着吗** —— 服务是前台进程，关掉终端/按了 Ctrl+C 就停了。
   这是最常见的原因（「昨天还能下，今天打不开」基本都是这个）。
   要长期挂着就用上面那条 `nohup`，或做成 systemd 服务。
2. 手机和电脑在同一个 Wi-Fi 吗 —— 电脑当前在 `192.168.2.233/24`，
   手机热点连着、或者连了另一个频段的访客网络，都不通。
3. 路由器开了「AP 隔离 / 客户端隔离」吗 —— 开了之后同一 Wi-Fi 的设备之间互相看不见，
   属于服务完全正常但谁都连不上。要到路由器后台关掉。
4. 电脑防火墙放行了吗 —— `sudo ufw status`，没放行就 `sudo ufw allow 8080/tcp`。
5. **地址有没有敲全** —— 要含 `http://` 和端口号：`http://192.168.2.233:8080/`，
   只敲 IP 或者敲成 `https://` 都不行。

本机自测（第 1 条最快）：在电脑浏览器打开 `http://127.0.0.1:8080/`。
- 打得开 → 服务没问题，问题在 2/3/4，属于网络与防火墙。
- 打不开 → 服务真没起来，看启动时的报错（多半是端口被占，换 `PORT=9000`）。

#### 最快的定位手段：看服务自己的访问日志

服务每收到一次请求都会打一行 `[时间] 来源IP 访问首页 / 下载 xxx`，
这就是判断「手机到底有没有连上」的铁证：

- 日志里出现了手机的 IP → 请求到了，问题在下载/安装那一侧（比如微信内置浏览器）。
- 反复试都没有手机的 IP → 请求根本没到达这台机器，属于网络可达性问题，
  按上面 1~4 条查，别再折腾 App 和脚本了。

（2026-10-16 就是靠这条把范围锁死的：服务端一切正常，但日志里从头到尾只有本机自己的
访问记录，说明手机压根没连进来。）

### 局域网走不通时的备选：发布成公网链接

如果确认是虚拟机网络模式（NAT / 仅主机）或者路由器 AP 隔离导致的不可达，
而这些又要动宿主机的虚拟机设置才能改，那更省事的做法是**绕开局域网**：
把下载页作为一个静态站点发布出去，手机点链接就能下。

本项目当前不提供自建下载页，安装包统一走 GitHub Releases：

```
https://github.com/zizairufeng/scanner/releases/latest
```

（2026-11-06 更新：原先的 `scanstock-apk.app.workbuddy.host` 下载页已下线，站点目录
`scan-app/apk-site/` 已删除，APK 改由 Release 附件分发。）
