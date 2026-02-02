#!/bin/bash
# 综合测试脚本 - 使用设备端 device-watch 监控 + Monkey 测试
# 监控在设备上运行，无需 PC 持续连接 adb

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MEM_ANALYZE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEVICE_WATCH_DIR="$MEM_ANALYZE_DIR/device-watch"
PACKAGE_NAME="com.koom.leak"
MONKEY_EVENTS=3000

echo "=========================================="
echo "   泄露综合测试 (device-watch + Monkey)"
echo "=========================================="
echo ""

# 检查 device-watch 目录
if [ ! -f "$DEVICE_WATCH_DIR/device-watch.sh" ]; then
    echo "错误: device-watch 目录或 device-watch.sh 不存在"
    exit 1
fi

# 检查 JAR（用于后续分析 hprof）
JAR_FILE="$MEM_ANALYZE_DIR/build/libs/mem-analyze-1.0.0-all.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "提示: JAR 不存在，如需分析 dump 的 hprof 请先执行: cd $MEM_ANALYZE_DIR && ./gradlew shadowJar"
fi

# 检查 APK
APK_FILE="$MEM_ANALYZE_DIR/demo/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_FILE" ]; then
    echo "错误: APK 不存在，正在构建..."
    cd "$MEM_MONITOR_DIR/demo"
    ./gradlew assembleDebug
fi

# 检查设备
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "错误: 没有检测到 Android 设备"
    exit 1
fi
echo "设备已连接: $DEVICES 台"

# 安装应用
echo "正在安装/更新应用..."
adb install -r "$APK_FILE" 2>/dev/null || true

# 部署 device-watch 到设备
echo "正在部署 device-watch 到设备..."
cd "$DEVICE_WATCH_DIR"
echo "$PACKAGE_NAME" > /tmp/package_list_run.txt
./deploy-device-watch.sh /tmp/package_list_run.txt 2>/dev/null || ./deploy-device-watch.sh

# 启动设备端监控（后台，可拔掉 USB）
echo ""
echo "正在设备上启动监控 (可拔掉 USB)..."
adb shell "nohup sh /data/local/tmp/run-device-watch.sh > /data/local/tmp/watch.log 2>&1 &" 2>/dev/null || \
adb shell "nohup sh /data/local/tmp/device-watch.sh -p $PACKAGE_NAME > /data/local/tmp/watch.log 2>&1 &"
sleep 2

# 运行 Monkey
echo "正在运行 Monkey ($MONKEY_EVENTS 事件)..."
adb shell monkey -p "$PACKAGE_NAME" \
    --throttle 200 \
    --pct-touch 60 --pct-motion 20 --pct-nav 15 --pct-appswitch 5 \
    -v "$MONKEY_EVENTS"

echo ""
echo "=========================================="
echo "   测试结束"
echo "=========================================="
echo "查看设备端监控日志: adb shell cat /data/local/tmp/watch.log"
echo "拉取 dump 的 hprof: adb pull /sdcard/... (见 device-watch 输出路径)"
echo "分析 hprof: java -jar $JAR_FILE -f <hprof路径>"
echo ""
