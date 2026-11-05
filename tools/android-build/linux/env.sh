#!/bin/bash
# ScanStock 隔离构建环境（Linux/x86_64）
# 用法：  source $HOME/WorkBuddy/scan-app/androidbuild/env.sh
#
# 与源机器（Windows）的对应关系：
#   C:\Users\<you>\androidbuild\           ->  <BASE>
#   C:\Users\<you>\WorkBuddy\...\ScanStock ->  <PROJECT>（见 PROJECT 变量）

# 默认按 $HOME 推导；也可先 export AB_BASE / PROJECT 覆盖。
export AB_BASE="${AB_BASE:-$HOME/WorkBuddy/scan-app/androidbuild}"
export PROJECT="${PROJECT:-$HOME/WorkBuddy/scan-app/ScanStock}"

export JAVA_HOME="$AB_BASE/jdk/jdk17"
export ANDROID_HOME="$AB_BASE/sdk"
export ANDROID_SDK_ROOT="$AB_BASE/sdk"
export GRADLE_USER_HOME="$AB_BASE/gradle-home"

# 沙箱/容器里 /tmp 很小（10MB），把临时目录指到大盘上
export TMPDIR="$AB_BASE/dl"
mkdir -p "$TMPDIR"

export PATH="$JAVA_HOME/bin:$AB_BASE/gradle/gradle-8.9/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Gradle 富文本控制台在本环境会输出 [1A[K 之类的转义，日志不可读
export TERM=dumb

# 关键：sdkmanager / AGP 用 JVM 的 user.home 决定 .android 缓存目录，
# 默认 /root 在容器/沙箱里**不可写**（写 debug.keystore 会直接让 validateSigningDebug 失败）。
# 两种覆盖方式，各管一段：
#   1) JAVA_OPTS 里的 -Duser.home —— sdkmanager 走这条
#   2) ANDROID_USER_HOME 环境变量 —— AGP 的 AndroidLocation 优先读它（Gradle 走这条）
#      ⚠️ 只设 ANDROID_USER_HOME，不要同时设 ANDROID_PREFS_ROOT：
#      两个一起设时 AGP 8.7 会在 AndroidLocationsBuildService 阶段直接抛
#      "Could not create provider for value source ...AndroidDirectoryCreator"，
#      整个配置阶段就挂了。
export AB_ANDROID_HOME_DIR="$AB_BASE/dl/home"
export JAVA_OPTS="-Duser.home=$AB_ANDROID_HOME_DIR -Djava.io.tmpdir=$AB_BASE/dl"
export ANDROID_USER_HOME="$AB_ANDROID_HOME_DIR"
mkdir -p "$AB_ANDROID_HOME_DIR"
