#!/bin/bash

# 生成测试用的 hprof 文件脚本
# 使用方法: ./scripts/generate_test_hprof.sh <leak_type>
# leak_type: activity, fragment, view, viewmodel, service, dialog, handler_message, 
#            broadcast_receiver, animator, bitmap, bytearray, all

set -e

PACKAGE_NAME="com.koom.leak"
ACTIVITY_NAME="com.koom.leak.MainActivity"
HPROF_DIR="$HOME/tmp/hprof"
DEVICE_HPROF_PATH="/sdcard"

# 创建目录
mkdir -p "$HPROF_DIR"

# 检查 adb 是否可用
if ! command -v adb &> /dev/null; then
    echo "❌ 错误: adb 未找到，请安装 Android SDK"
    exit 1
fi

# 检查设备是否连接
if ! adb devices | grep -q "device$"; then
    echo "❌ 错误: 未找到已连接的设备"
    exit 1
fi

# 检查 APK 是否已安装
if ! adb shell pm list packages | grep -q "$PACKAGE_NAME"; then
    echo "❌ 错误: $PACKAGE_NAME 未安装"
    echo "   请先运行: cd demo && ./gradlew installDebug"
    exit 1
fi

LEAK_TYPE=$1

if [ -z "$LEAK_TYPE" ]; then
    echo "用法: $0 <leak_type>"
    echo ""
    echo "支持的泄露类型:"
    echo "  activity           - Activity 泄露"
    echo "  fragment           - Fragment 泄露"
    echo "  view               - View 泄露"
    echo "  viewmodel          - ViewModel 泄露"
    echo "  service            - Service 泄露"
    echo "  dialog             - Dialog 泄露"
    echo "  handler_message    - Handler/Message 泄露"
    echo "  broadcast_receiver - BroadcastReceiver 泄露"
    echo "  animator           - Animator 泄露"
    echo "  bitmap             - Bitmap 泄露"
    echo "  bytearray          - ByteArray 泄露"
    echo "  all                - 所有泄露类型（综合测试）"
    exit 1
fi

HPROF_FILE="${LEAK_TYPE}_leak.hprof"
DEVICE_FILE="$DEVICE_HPROF_PATH/$HPROF_FILE"
LOCAL_FILE="$HPROF_DIR/$HPROF_FILE"

echo "📱 生成 $LEAK_TYPE 泄露测试文件..."
echo "   目标文件: $LOCAL_FILE"

# 启动应用
echo "1. 启动应用..."
adb shell am start -n "$ACTIVITY_NAME"
sleep 2

# 根据泄露类型触发对应的操作
case $LEAK_TYPE in
    activity)
        echo "2. 触发 Activity 泄露..."
        echo "   提示: 应用会自动退出，请等待重新打开"
        # 使用 Intent 触发 Activity 泄露
        adb shell am start -a "com.koom.leak.action.ACTIVITY_LEAK_AND_EXIT" -n "$ACTIVITY_NAME"
        sleep 3
        # 重新打开应用
        adb shell am start -n "$ACTIVITY_NAME"
        sleep 2
        ;;
    fragment)
        echo "2. 触发 Fragment 泄露..."
        adb shell am start -a "com.koom.leak.action.FRAGMENT_LEAK" -n "$ACTIVITY_NAME"
        sleep 2
        ;;
    view)
        echo "2. 触发 View 泄露..."
        echo "   提示: 需要手动点击 'View泄露' 按钮，然后退出应用"
        read -p "   按回车键继续（点击按钮后）..."
        adb shell am force-stop "$PACKAGE_NAME"
        sleep 1
        adb shell am start -n "$ACTIVITY_NAME"
        sleep 2
        ;;
    viewmodel|service|dialog|handler_message|broadcast_receiver|animator|bitmap|bytearray)
        echo "2. 触发 ${LEAK_TYPE} 泄露..."
        echo "   提示: 需要手动点击对应的泄露按钮"
        read -p "   按回车键继续（点击按钮后）..."
        sleep 2
        ;;
    all)
        echo "2. 触发所有泄露类型..."
        echo "   提示: 需要手动依次点击所有泄露按钮"
        read -p "   按回车键继续（点击完所有按钮后）..."
        sleep 3
        ;;
    *)
        echo "❌ 错误: 未知的泄露类型: $LEAK_TYPE"
        exit 1
        ;;
esac

# Dump hprof
echo "3. Dump hprof..."
adb shell am dumpheap "$PACKAGE_NAME" "$DEVICE_FILE"
sleep 3

# 检查文件是否生成
if ! adb shell test -f "$DEVICE_FILE"; then
    echo "❌ 错误: hprof 文件未生成"
    exit 1
fi

# 拉取文件
echo "4. 拉取文件..."
adb pull "$DEVICE_FILE" "$LOCAL_FILE"

# 删除设备上的文件
adb shell rm "$DEVICE_FILE"

# 验证文件
if [ -f "$LOCAL_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$LOCAL_FILE" 2>/dev/null || stat -c%s "$LOCAL_FILE" 2>/dev/null)
    echo "✅ 成功生成测试文件: $LOCAL_FILE"
    echo "   文件大小: $(numfmt --to=iec-i --suffix=B $FILE_SIZE 2>/dev/null || echo "${FILE_SIZE} bytes")"
else
    echo "❌ 错误: 文件拉取失败"
    exit 1
fi

echo ""
echo "📝 下一步: 运行测试验证"
echo "   ./gradlew test --tests HprofAnalyzerLeakDetectionTest.test${LEAK_TYPE^}LeakDetection"
