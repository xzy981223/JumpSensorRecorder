#!/bin/bash

# 出错立即退出
set -e

# 你的手机和手表的 adb ID
PHONE_ID="ZY32825Q3T"
WATCH_ID=$(adb devices | grep 133.2.130.208 | grep device | awk '{print $1}')

# 包名
PHONE_APP_ID="com.example.jumpsensorrecorder"
WATCH_APP_ID="com.example.jumpsensorrecorder.wear"

echo "🔄 卸载旧版本..."
adb -s $PHONE_ID uninstall $PHONE_APP_ID || true
adb -s $WATCH_ID uninstall $WATCH_APP_ID || true

echo "🧹 清理工程..."
./gradlew clean

echo "📦 编译手机 APK..."
./gradlew :app:assembleDebug

echo "📦 编译手表 APK..."
./gradlew :wear:assembleDebug

echo "📲 安装到手机..."
adb -s $PHONE_ID install -r app/build/outputs/apk/debug/app-debug.apk

echo "⌚ 安装到手表..."
adb -s $WATCH_ID install -r wear/build/outputs/apk/debug/wear-debug.apk

echo "🚀 启动手机 MainActivity..."
adb -s $PHONE_ID shell am start -n $PHONE_APP_ID/.presentation.MainActivity

echo "📜 监控 HrReceiver 日志..."
adb -s $PHONE_ID logcat | grep HrReceiver

