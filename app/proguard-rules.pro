# ── ML Kit ──
# 内部通过反射 / 动态注册表加载扫描器实现，混淆会破坏注册表
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode.**

# ── barhopper（离线条码解析引擎） ──
# 这一个包必须显式 keep，理由不是猜的：
#   实测 libbarhopper_v3.so 的字符串常量池里硬编码了这几个类描述符 ——
#     Lcom/google/android/libraries/barhopper/MultiScaleDecodingOptions;
#     Lcom/google/android/libraries/barhopper/MultiScaleDetectionOptions;
#     Lcom/google/android/libraries/barhopper/OnedRecognitionOptions;
#   native 层是按「字符串类名」去 FindClass 的，一旦 R8 改了名，JNI 直接崩溃，
#   而且崩在 native 层，日志里看不到 Java 堆栈，极难排查。
#
# 现状说明：即便没有下面这行，实测这 5 个类也能保住 ——
#   官方 barcode-scanning-17.3.0 AAR 自带一条 consumer rule：
#       -keepclasseswithmembernames class * { native <methods>; }
#   恰好覆盖了它们。但那是「间接效果」：靠的是 ML Kit 内部恰好声明了 native 方法。
#   显式写出来，是为了在将来升级 ML Kit 版本、其内部实现变化时，
#   不至于因为依赖链的旁枝变化而静默失效。
-keep class com.google.android.libraries.barhopper.** { *; }

# ── ZXing core（v1.7 第二识别引擎）──
# 纯 Java、无 native、无反射注册表，理论上可以安全混淆。
# 但它是一条**只在解不出时才走**的兜底路径：一旦 R8 把某个 Reader 类优化掉或改名，
# 现场表现就是「另一个引擎永远是 0 命中」，没有任何报错，极难发现。
# 因此对这个小体量（约 500KB）的纯算法库不做裁剪，换取「兜底路径确定可用」。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
