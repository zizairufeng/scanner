// 顶层构建脚本：只声明插件版本，不在这里配置模块
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 版本走，必须单独应用该插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
