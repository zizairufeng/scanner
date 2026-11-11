plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.scanstock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.scanstock"
        minSdk = 24
        targetSdk = 35
        versionCode = 21
        versionName = "1.8.8"
        // 本项目无任何 JVM 测试依赖，此处不配置 testInstrumentationRunner
    }

    buildTypes {
        release {
            // ML Kit 自带 consumer rules，开启混淆可显著瘦身
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")

        // 剔除 x86 / x86_64 的 native 库：ML Kit 的 libbarhopper_v3.so 四个 ABI
        // 全打进来要 19.5MB（未压缩），其中 x86 5.84MB + x86_64 5.64MB，
        // 手机（arm64-v8a / armeabi-v7a）永远用不到，是纯浪费。
        //
        // 注意：必须写在 android{} 的评估期。试过用 androidComponents.onVariants 里
        // 按 release 单独剔除，AGP 8.7 直接报 "It is too late to modify excludes"。
        // 代价：debug 包也会少这两个 ABI，若要在 x86 模拟器上调试，
        // 把下面这一行注释掉即可。
        jniLibs.excludes += setOf("lib/x86/**", "lib/x86_64/**")
    }

    testOptions {
        unitTests {
            // DedupeTracker 的 shouldAccept() 默认参数会碰 android.os.SystemClock。
            // 单测里我们一律显式传时间，但打开 returnDefaultValues 可让任何
            // 未 stub 的 Android API 返回默认值而不是抛 "not mocked"，避免测试被无关原因打断。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val cameraxVersion = "1.4.1"
    val lifecycleVersion = "2.8.7"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")

    // Compose BOM 统一版本，无需逐个写版本号
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // CameraX：camera-view 提供 PreviewView；camera-lifecycle 负责与生命周期绑定
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit 条码扫描「捆绑模型」版：模型打进 APK，端侧离线，无需网络与 Play 服务下载
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ZXing core（v1.7 第二识别引擎）：纯 Java、无 native、无 NDK，
    // 只在「全帧解不出 → 裁块 → ML Kit 仍解不出」时才走，见 camera/ZxingDecoder.kt。
    // 选 3.5.3 而不是 3.5.4：3.5.3 是各大镜像站长期稳定的一份，离线构建最省事。
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试：只覆盖 domain 层的纯逻辑（去重语义 + 复制格式化），
    // 不需要 Robolectric，也不需要真机 —— ./gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}
