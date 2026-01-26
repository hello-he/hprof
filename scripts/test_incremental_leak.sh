#!/bin/bash
#
# 递增式泄露测试脚本
# 用于测试 watch 功能的4种触发条件
# 1. 堆内存使用率超过阈值
# 2. 线程数超过阈值
# 3. 文件句柄数超过阈值
# 4. 重复线程名检测
#

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 配置
PACKAGE_NAME="com.koom.leak"
ACTIVITY_NAME="com.koom.leak.MainActivity"

# 检查设备连接
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ 未检测到Android设备${NC}"
    exit 1
fi

# 获取测试类型
TEST_TYPE=${1:-heap}

case "$TEST_TYPE" in
    heap)
        ACTION="com.koom.leak.action.HEAP_MEMORY_LEAK"
        TEST_NAME="堆内存泄露"
        WATCH_CMD="java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p $PACKAGE_NAME -t 0.15 -i 3"
        COUNT=10
        ;;
    thread)
        ACTION="com.koom.leak.action.THREAD_COUNT_LEAK"
        TEST_NAME="线程数泄露"
        WATCH_CMD="java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p $PACKAGE_NAME --thread-threshold 50 -i 3"
        COUNT=10
        ;;
    fd)
        ACTION="com.koom.leak.action.FD_COUNT_LEAK"
        TEST_NAME="文件句柄泄露"
        WATCH_CMD="java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p $PACKAGE_NAME --fd-threshold 50 -i 3"
        COUNT=10
        ;;
    duplicate)
        ACTION="com.koom.leak.action.DUPLICATE_THREAD_NAME_LEAK"
        TEST_NAME="重复线程名泄露"
        WATCH_CMD="java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p $PACKAGE_NAME -i 3"
        COUNT=5
        ;;
    *)
        echo "用法: $0 [heap|thread|fd|duplicate]"
        echo ""
        echo "测试类型:"
        echo "  heap      - 堆内存使用率超过阈值（默认）"
        echo "  thread    - 线程数超过阈值"
        echo "  fd        - 文件句柄数超过阈值"
        echo "  duplicate - 重复线程名检测"
        exit 1
        ;;
esac

# 启动应用
echo -e "${YELLOW}启动应用...${NC}"
adb shell am force-stop "$PACKAGE_NAME" 2>/dev/null
sleep 1
adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"
sleep 2

# 触发递增式泄露
echo -e "${YELLOW}开始触发${TEST_NAME}...${NC}"
echo -e "${GREEN}提示：请在另一个终端运行 watch 命令监控${NC}"
echo -e "${GREEN}命令：$WATCH_CMD${NC}"
echo ""

# 循环触发递增式泄露
for i in $(seq 1 $COUNT); do
    echo -e "${YELLOW}[$i/$COUNT] 触发${TEST_NAME}...${NC}"
    adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "$ACTION" > /dev/null 2>&1
    
    # 等待泄露创建完成
    sleep 2
    
    # 显示当前状态
    echo "   已触发 $i 次${TEST_NAME}"
    echo ""
    
    # 每次泄露后等待一段时间，让 watch 有机会检测
    sleep 3
done

echo -e "${GREEN}✅ ${TEST_NAME}测试完成${NC}"
echo -e "${YELLOW}请检查 watch 终端是否检测到并触发 dump${NC}"
