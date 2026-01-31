#!/bin/bash
# ============================================================================
# 部署设备端内存监控工具到 Android 设备
#
# 功能：
#   1. 将 device-watch.sh 推送到设备并设置权限
#   2. 可选：通过 package_list.txt 指定包名列表，生成并推送启动脚本
#
# 使用方法：
#   ./deploy-device-watch.sh [选项] [package_list.txt]
#
# 选项：
#   -s <序列号>    指定设备序列号（多设备时使用）
#   -f <文件>      包名列表文件（每行一个包名，# 开头为注释）
#   -h             显示帮助
#
# 示例：
#   ./deploy-device-watch.sh
#   ./deploy-device-watch.sh package_list.txt
#   ./deploy-device-watch.sh -f package_list.txt -s emulator-5554
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE_SCRIPT="$SCRIPT_DIR/device-watch.sh"
DEVICE_PATH="/data/local/tmp"
RUN_SCRIPT_NAME="run-device-watch.sh"
ADB_SERIAL=""
PACKAGE_LIST_FILE=""
HAS_RUN_SCRIPT=0

# 显示帮助
show_help() {
    echo "=============================================="
    echo "  部署设备端内存监控工具"
    echo "=============================================="
    echo ""
    echo "用法: $0 [选项] [package_list.txt]"
    echo ""
    echo "选项:"
    echo "  -s <序列号>    指定设备序列号"
    echo "  -f <文件>      包名列表文件（每行一个包名，# 为注释）"
    echo "  -h             显示帮助"
    echo ""
    echo "若提供 package_list.txt（或 -f 指定），将生成并推送启动脚本，"
    echo "设备上直接执行 run-device-watch.sh 即可按列表监控。"
    echo ""
    echo "示例:"
    echo "  $0"
    echo "  $0 package_list.txt"
    echo "  $0 -f package_list.txt -s emulator-5554"
    echo ""
    exit 0
}

# 解析参数
while getopts "s:f:h" opt; do
    case $opt in
        s) ADB_SERIAL="$OPTARG" ;;
        f) PACKAGE_LIST_FILE="$OPTARG" ;;
        h) show_help ;;
        *) show_help ;;
    esac
done
shift $((OPTIND - 1))
#  positional 包名列表文件
if [ -n "$1" ] && [ -z "$PACKAGE_LIST_FILE" ]; then
    PACKAGE_LIST_FILE="$1"
fi

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
$ADB_CMD shell mkdir -p "$DEVICE_PATH/mem-analyze"

# 若指定了包名列表文件，生成并推送启动脚本
if [ -n "$PACKAGE_LIST_FILE" ]; then
    if [ ! -f "$PACKAGE_LIST_FILE" ]; then
        echo "❌ 包名列表文件不存在: $PACKAGE_LIST_FILE"
        exit 1
    fi
    echo "📋 读取包名列表: $PACKAGE_LIST_FILE"
    PACKAGES=()
    while IFS= read -r line || [ -n "$line" ]; do
        line=$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        [ -z "$line" ] && continue
        [ "${line#\#}" != "$line" ] && continue
        PACKAGES+=("$line")
    done < "$PACKAGE_LIST_FILE"
    if [ ${#PACKAGES[@]} -eq 0 ]; then
        echo "❌ 包名列表为空或仅有注释/空行"
        exit 1
    fi
    echo "   共 ${#PACKAGES[@]} 个包名"
    # 生成设备端启动脚本（调用 device-watch.sh -p pkg1 -p pkg2 ...）
    RUN_SCRIPT_CONTENT="#!/system/bin/sh
# 由 deploy-device-watch.sh 根据 package_list.txt 生成，请勿手改
exec sh $DEVICE_PATH/device-watch.sh"
    for p in "${PACKAGES[@]}"; do
        RUN_SCRIPT_CONTENT="$RUN_SCRIPT_CONTENT -p $p"
    done
    RUN_SCRIPT_CONTENT="$RUN_SCRIPT_CONTENT \"\$@\"
"
    RUN_SCRIPT_TMP=$(mktemp)
    echo "$RUN_SCRIPT_CONTENT" > "$RUN_SCRIPT_TMP"
    echo "📤 推送启动脚本到设备 ($RUN_SCRIPT_NAME)..."
    $ADB_CMD push "$RUN_SCRIPT_TMP" "$DEVICE_PATH/$RUN_SCRIPT_NAME"
    $ADB_CMD shell chmod 755 "$DEVICE_PATH/$RUN_SCRIPT_NAME"
    rm -f "$RUN_SCRIPT_TMP"
    HAS_RUN_SCRIPT=1
fi

echo ""
echo "✅ 部署完成！"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📖 推荐用法（后台运行，可拔掉 USB）："
echo ""
if [ "$HAS_RUN_SCRIPT" -eq 1 ]; then
    echo "  已根据 $PACKAGE_LIST_FILE 生成启动脚本，直接执行即可："
    echo "    adb shell \"nohup sh $DEVICE_PATH/$RUN_SCRIPT_NAME > $DEVICE_PATH/watch.log 2>&1 &\""
    echo ""
    echo "  启动后可拔掉 USB，监控在设备上继续；需查看时再连 USB。"
    echo "  查看是否正常运行: adb shell cat $DEVICE_PATH/watch.log"
else
    echo "  单包名:"
    echo "    adb shell \"nohup sh $DEVICE_PATH/device-watch.sh -p <包名> > $DEVICE_PATH/watch.log 2>&1 &\""
    echo ""
    echo "  多包名:"
    echo "    adb shell \"nohup sh $DEVICE_PATH/device-watch.sh -p <包名1> -p <包名2> > $DEVICE_PATH/watch.log 2>&1 &\""
    echo ""
    echo "  或先准备 package_list.txt（每行一个包名），再执行："
    echo "    $0 package_list.txt"
    echo "  然后设备上执行: adb shell \"nohup sh $DEVICE_PATH/$RUN_SCRIPT_NAME > $DEVICE_PATH/watch.log 2>&1 &\""
    echo ""
    echo "  启动后可拔掉 USB，监控在设备上继续；需查看时再连 USB。"
    echo "  查看是否正常运行: adb shell cat $DEVICE_PATH/watch.log"
fi
echo ""
echo "  查看帮助: adb shell sh $DEVICE_PATH/device-watch.sh -h"
echo ""
echo "🛑 停止监控: adb shell \"pkill -f device-watch.sh\""
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📊 后续分析 hprof 文件："
echo ""
echo "  1. 拉取 hprof 文件:"
echo "     adb pull $DEVICE_PATH/mem-analyze/ ./"
echo ""
echo "  2. 分析 hprof:"
echo "     java -jar mem-analyze-1.0.0-all.jar analyze <hprof文件>"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
