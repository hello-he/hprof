#!/bin/bash
# ============================================================================
# 部署设备端内存监控工具到 Android 设备
#
# 功能：
#   1. 将 device-watch.sh 推送到设备并设置权限
#   2. 可选：根据 package_list.txt 生成并推送 run-device-watch.sh
#
# 说明：本脚本只负责部署，不会自动启动监控。
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
    echo "本脚本只部署，不启动监控。"
    echo "若提供 package_list.txt（或 -f），会额外生成 $RUN_SCRIPT_NAME，"
    echo "部署完成后按提示执行 adb shell 命令即可启动。"
    echo ""
    echo "示例:"
    echo "  $0"
    echo "  $0 package_list.txt"
    echo "  $0 -f package_list.txt -s emulator-5554"
    echo ""
    exit 0
}

while getopts "s:f:h" opt; do
    case $opt in
        s) ADB_SERIAL="$OPTARG" ;;
        f) PACKAGE_LIST_FILE="$OPTARG" ;;
        h) show_help ;;
        *) show_help ;;
    esac
done
shift $((OPTIND - 1))

if [ -n "$1" ] && [ -z "$PACKAGE_LIST_FILE" ]; then
    PACKAGE_LIST_FILE="$1"
fi

ADB_CMD="adb"
if [ -n "$ADB_SERIAL" ]; then
    ADB_CMD="adb -s $ADB_SERIAL"
fi

echo ""
echo "=============================================="
echo "  部署设备端内存监控工具"
echo "=============================================="
echo ""

echo "检查设备连接..."
if ! $ADB_CMD devices | grep -q "device$"; then
    echo "错误: 未检测到已连接的设备"
    echo ""
    echo "请确保:"
    echo "  1. 设备已通过 USB 连接"
    echo "  2. USB 调试已启用"
    echo "  3. 已授权此电脑的调试请求"
    echo ""
    exit 1
fi

DEVICE_MODEL=$($ADB_CMD shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_VERSION=$($ADB_CMD shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "设备: $DEVICE_MODEL (Android $ANDROID_VERSION)"
echo ""

if [ ! -f "$DEVICE_SCRIPT" ]; then
    echo "错误: 找不到设备端脚本: $DEVICE_SCRIPT"
    exit 1
fi

echo "[1/3] 推送 device-watch.sh ..."
$ADB_CMD push "$DEVICE_SCRIPT" "$DEVICE_PATH/device-watch.sh"

echo "[2/3] 设置执行权限并创建输出目录 ..."
$ADB_CMD shell chmod 755 "$DEVICE_PATH/device-watch.sh"
$ADB_CMD shell mkdir -p "$DEVICE_PATH/mem-analyze"

if [ -n "$PACKAGE_LIST_FILE" ]; then
    if [ ! -f "$PACKAGE_LIST_FILE" ]; then
        echo "错误: 包名列表文件不存在: $PACKAGE_LIST_FILE"
        exit 1
    fi
    echo "[3/3] 根据 $PACKAGE_LIST_FILE 生成并推送 $RUN_SCRIPT_NAME ..."
    PACKAGES=()
    while IFS= read -r line || [ -n "$line" ]; do
        line=$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        [ -z "$line" ] && continue
        [ "${line#\#}" != "$line" ] && continue
        PACKAGES+=("$line")
    done < "$PACKAGE_LIST_FILE"
    if [ ${#PACKAGES[@]} -eq 0 ]; then
        echo "错误: 包名列表为空或仅有注释/空行"
        exit 1
    fi
    echo "      共 ${#PACKAGES[@]} 个包名"

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
    $ADB_CMD push "$RUN_SCRIPT_TMP" "$DEVICE_PATH/$RUN_SCRIPT_NAME"
    $ADB_CMD shell chmod 755 "$DEVICE_PATH/$RUN_SCRIPT_NAME"
    rm -f "$RUN_SCRIPT_TMP"
    HAS_RUN_SCRIPT=1
else
    echo "[3/3] 未指定包名列表，跳过生成 $RUN_SCRIPT_NAME"
fi

echo ""
echo "部署完成（监控尚未启动）。"
echo ""
echo "----------------------------------------------"
echo "启动监控（后台运行，启动后可拔掉 USB）"
echo "----------------------------------------------"
if [ "$HAS_RUN_SCRIPT" -eq 1 ]; then
    echo ""
    echo "  adb shell \"nohup sh $DEVICE_PATH/$RUN_SCRIPT_NAME > $DEVICE_PATH/watch.log 2>&1 &\""
else
    echo ""
    echo "  单包:"
    echo "    adb shell \"nohup sh $DEVICE_PATH/device-watch.sh -p <包名> > $DEVICE_PATH/watch.log 2>&1 &\""
    echo ""
    echo "  多包:"
    echo "    adb shell \"nohup sh $DEVICE_PATH/device-watch.sh -p <包名1> -p <包名2> > $DEVICE_PATH/watch.log 2>&1 &\""
    echo ""
    echo "  若希望自动生成启动脚本，请带包名列表重新部署:"
    echo "    $0 package_list.txt"
fi
echo ""
echo "查看运行日志:"
echo "  adb shell cat $DEVICE_PATH/watch.log"
echo ""
echo "查看帮助:"
echo "  adb shell sh $DEVICE_PATH/device-watch.sh -h"
echo ""
echo "停止监控:"
echo "  adb shell \"kill \\\$(ps -ef | grep device-watch.sh | grep -v grep | awk '{print \\\$2}')\""
echo ""
echo "----------------------------------------------"
echo "拉取并分析 hprof"
echo "----------------------------------------------"
echo ""
echo "  adb pull $DEVICE_PATH/mem-analyze/ ./"
echo "  java -jar mem-analyze-1.0.0-all.jar -f <hprof文件>"
echo ""
