#!/bin/bash
# Monkey测试脚本 - 用于触发Bitmap泄露

set -e

# 配置
PACKAGE_NAME="com.koom.leak"
MONKEY_EVENTS=5000
THROTTLE=200
OUTPUT_FILE="monkey_report.txt"

echo "=========================================="
echo "   Monkey测试 - Bitmap泄露触发"
echo "=========================================="
echo "包名: $PACKAGE_NAME"
echo "事件数: $MONKEY_EVENTS"
echo "延迟: ${THROTTLE}ms"
echo ""

# 检查设备连接
echo "1. 检查设备连接..."
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "错误: 没有检测到Android设备"
    echo "请确保:"
    echo "  - 设备已通过USB连接"
    echo "  - 已开启USB调试"
    echo "  - 已授权此电脑"
    exit 1
fi
echo "   检测到 $DEVICES 台设备"

# 检查应用是否安装
echo ""
echo "2. 检查应用是否安装..."
if ! adb shell pm list packages | grep -q "$PACKAGE_NAME"; then
    echo "   应用未安装，正在安装..."
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    adb install -r "$SCRIPT_DIR/../demo/app/build/outputs/apk/debug/app-debug.apk"
else
    echo "   应用已安装"
fi

# 启动应用
echo ""
echo "3. 启动应用..."
adb shell am start -n "$PACKAGE_NAME/.MainActivity"
sleep 2

# 运行Monkey测试
echo ""
echo "4. 运行Monkey测试..."
echo "   这会触发随机点击，包括创建Bitmap泄露的按钮"
echo ""

adb shell monkey -p "$PACKAGE_NAME" \
    --throttle $THROTTLE \
    --pct-touch 50 \
    --pct-motion 20 \
    --pct-nav 20 \
    --pct-appswitch 5 \
    --pct-anyevent 5 \
    -v $MONKEY_EVENTS > "$OUTPUT_FILE" 2>&1

# 统计结果
echo ""
echo "=========================================="
echo "   Monkey测试完成"
echo "=========================================="

# 分析结果
CRASH=$(grep -c "CRASH" "$OUTPUT_FILE" || echo "0")
ANR=$(grep -c "ANR" "$OUTPUT_FILE" || echo "0")

echo "崩溃次数: $CRASH"
echo "ANR次数: $ANR"
echo ""
echo "详细报告保存在: $OUTPUT_FILE"

# 显示应用是否还在运行
if adb shell pidof "$PACKAGE_NAME" > /dev/null; then
    echo "应用状态: 运行中 (PID: $(adb shell pidof $PACKAGE_NAME))"

    # 显示当前内存使用
    echo ""
    echo "当前内存使用:"
    adb shell dumpsys meminfo "$PACKAGE_NAME" | grep "Native Heap" | head -1
fi
