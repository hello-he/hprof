#!/bin/bash
# ============================================================================
# 部署设备端内存监控工具到 Android 设备
#
# 功能：将 device-watch.sh 推送到设备并设置权限
#
# 使用方法：
#   ./deploy-device-watch.sh [选项]
#
# 选项：
#   -s <序列号>    指定设备序列号（多设备时使用）
#   -h             显示帮助
#
# 示例：
#   ./deploy-device-watch.sh
#   ./deploy-device-watch.sh -s emulator-5554
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE_SCRIPT="$SCRIPT_DIR/device-watch.sh"
DEVICE_PATH="/data/local/tmp"
ADB_SERIAL=""

# 显示帮助
show_help() {
    echo "=============================================="
    echo "  部署设备端内存监控工具"
    echo "=============================================="
    echo ""
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -s <序列号>    指定设备序列号"
    echo "  -h             显示帮助"
    echo ""
    echo "示例:"
    echo "  $0"
    echo "  $0 -s emulator-5554"
    echo ""
    exit 0
}

# 解析参数
while getopts "s:h" opt; do
    case $opt in
        s) ADB_SERIAL="$OPTARG" ;;
        h) show_help ;;
        *) show_help ;;
    esac
done

# 构建 adb 命令
ADB_CMD="adb"
if [ -n "$ADB_SERIAL" ]; then
    ADB_CMD="adb -s $ADB_SERIAL"
fi

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║      部署设备端内存监控工具                                  ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# 检查设备连接
echo "📱 检查设备连接..."
if ! $ADB_CMD devices | grep -q "device$"; then
    echo "❌ 未检测到已连接的设备"
    echo ""
    echo "请确保："
    echo "  1. 设备已通过 USB 连接"
    echo "  2. USB 调试已启用"
    echo "  3. 已授权此电脑的调试请求"
    echo ""
    exit 1
fi

DEVICE_MODEL=$($ADB_CMD shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_VERSION=$($ADB_CMD shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "   设备: $DEVICE_MODEL (Android $ANDROID_VERSION)"
echo ""

# 检查脚本文件
if [ ! -f "$DEVICE_SCRIPT" ]; then
    echo "❌ 找不到设备端脚本: $DEVICE_SCRIPT"
    exit 1
fi

# 推送脚本到设备
echo "📤 推送脚本到设备..."
$ADB_CMD push "$DEVICE_SCRIPT" "$DEVICE_PATH/device-watch.sh"

# 设置执行权限
echo "🔧 设置执行权限..."
$ADB_CMD shell chmod 755 "$DEVICE_PATH/device-watch.sh"

# 创建输出目录
echo "📁 创建输出目录..."
$ADB_CMD shell mkdir -p "$DEVICE_PATH/mem-monitor"

echo ""
echo "✅ 部署完成！"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📖 使用方法："
echo ""
echo "  方式1: 直接通过 adb 启动监控"
echo "    adb shell sh $DEVICE_PATH/device-watch.sh -p <包名>"
echo ""
echo "  方式2: 进入 shell 后启动"
echo "    adb shell"
echo "    sh $DEVICE_PATH/device-watch.sh -p <包名>"
echo ""
echo "  示例:"
echo "    adb shell sh $DEVICE_PATH/device-watch.sh -p com.example.app -t 70 -g"
echo ""
echo "  查看帮助:"
echo "    adb shell sh $DEVICE_PATH/device-watch.sh -h"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📊 后续分析 hprof 文件："
echo ""
echo "  1. 拉取 hprof 文件:"
echo "     adb pull $DEVICE_PATH/mem-monitor/ ./"
echo ""
echo "  2. 分析 hprof:"
echo "     java -jar mem-monitor-1.0.0-all.jar analyze <hprof文件>"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
