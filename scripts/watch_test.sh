#!/bin/bash
# Watch监控测试脚本

set -e

# 配置
PACKAGE_NAME="com.koom.leak"
HEAP_THRESHOLD=100        # 堆内存阈值(MB)
THREAD_THRESHOLD=200      # 线程阈值
FD_THRESHOLD=300          # 文件句柄阈值
JAR_FILE="/home/dk/workspaces/koom/mem-monitor/build/libs/mem-monitor-1.0.0-all.jar"

echo "=========================================="
echo "   Watch监控测试"
echo "=========================================="
echo "包名: $PACKAGE_NAME"
echo "堆内存阈值: ${HEAP_THRESHOLD}MB"
echo "线程阈值: $THREAD_THRESHOLD"
echo "文件句柄阈值: $FD_THRESHOLD"
echo ""

# 检查JAR文件
if [ ! -f "$JAR_FILE" ]; then
    echo "错误: JAR文件不存在: $JAR_FILE"
    echo "请先构建项目: ./gradlew shadowJar"
    exit 1
fi

# 检查设备
echo "1. 检查设备..."
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "错误: 没有检测到Android设备"
    exit 1
fi
echo "   检测到 $DEVICES 台设备"

# 检查应用
echo ""
echo "2. 检查应用..."
if ! adb shell pm list packages | grep -q "$PACKAGE_NAME"; then
    echo "   应用未安装，正在安装..."
    adb install -r /home/dk/workspaces/koom/mem-monitor/demo/app/build/outputs/apk/debug/app-debug.apk
else
    echo "   应用已安装"
fi

# 启动应用
echo ""
echo "3. 启动应用..."
adb shell am start -n "$PACKAGE_NAME/.MainActivity"
sleep 2

# 开始监控
echo ""
echo "=========================================="
echo "   开始监控 (Ctrl+C 停止)"
echo "=========================================="
echo ""
echo "当应用超过阈值时，会自动:"
echo "  - dump堆内存到 /sdcard/Download/"
echo "  - 截图保存现场"
echo "  - 保存到 reports/目录"
echo ""

# 执行watch命令
java -jar "$JAR_FILE" watch \
    -p "$PACKAGE_NAME" \
    --heap-threshold "$HEAP_THRESHOLD" \
    --thread-threshold "$THREAD_THRESHOLD" \
    --fd-threshold "$FD_THRESHOLD" \
    -o ./reports

echo ""
echo "=========================================="
echo "   监控结束"
echo "=========================================="
