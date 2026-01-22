#!/bin/bash
#
# TDD驱动的内存泄露测试脚本
# 用于自动化测试demo app的泄露检测功能
#

set -e  # 遇到错误立即退出

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 配置
PACKAGE_NAME="com.koom.leak"
ACTIVITY_NAME="com.koom.leak.MainActivity"
JAR_PATH="/home/dk/workspaces/koom/mem-monitor/build/libs/mem-monitor-1.0.0-all.jar"
TEST_OUTPUT_DIR="/tmp/mem-monitor-test"
DEVICE_HPROF_DIR="/data/local/tmp"

# 创建测试输出目录
mkdir -p "$TEST_OUTPUT_DIR"

# 打印带颜色的消息
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}➜ $1${NC}"
}

print_test() {
    echo ""
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}  测试: $1${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# 检查设备连接
check_device() {
    print_info "检查设备连接..."
    if ! adb devices | grep -q "device$"; then
        print_error "未检测到Android设备"
        exit 1
    fi
    print_success "设备已连接"
}

# 启动app并触发泄露
trigger_leak() {
    local action=$1
    local test_name=$2

    print_info "启动app并触发: $test_name"

    # 先强制停止app
    adb shell am force-stop "$PACKAGE_NAME"

    # 等待进程完全停止
    sleep 1

    # 启动app并触发特定泄露
    adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "$action"

    # 等待泄露创建完成
    sleep 3
}

# 执行dumpheap
dump_heap() {
    local hprof_name=$1

    print_info "执行dumpheap: $hprof_name"

    # 删除旧文件（如果存在）
    adb shell rm -f "$DEVICE_HPROF_DIR/$hprof_name"

    # 执行dumpheap
    adb shell am dumpheap "$PACKAGE_NAME" "$DEVICE_HPROF_DIR/$hprof_name"

    # 等待文件写入完成
    sleep 2

    # 拉取hprof文件
    mkdir -p "$TEST_OUTPUT_DIR"
    adb pull "$DEVICE_HPROF_DIR/$hprof_name" "$TEST_OUTPUT_DIR/$hprof_name"

    # 删除设备上的文件
    adb shell rm -f "$DEVICE_HPROF_DIR/$hprof_name"

    print_success "hprof文件已保存到: $TEST_OUTPUT_DIR/$hprof_name"
}

# 分析hprof文件
analyze_heap() {
    local hprof_file=$1
    local expected_leaks=$2

    print_info "分析hprof文件..."

    # 执行分析
    local output_file="$TEST_OUTPUT_DIR/analysis_result.txt"
    java -jar "$JAR_PATH" analyze -f="$hprof_file" -o "$TEST_OUTPUT_DIR/analysis_output" 2>&1 | tee "$output_file"

    # 验证结果
    verify_leaks "$output_file" "$expected_leaks"
}

# 验证泄露检测结果
verify_leaks() {
    local output_file=$1
    local expected_leaks=$2

    print_info "验证泄露检测结果..."

    local passed=0
    local failed=0

    # 检查Bitmap泄露
    if [[ "$expected_leaks" == *"large_bitmap"* ]]; then
        if grep -q "大Bitmap" "$output_file"; then
            print_success "检测到大Bitmap泄露"
            ((passed++))
        else
            print_error "未检测到大Bitmap泄露"
            ((failed++))
        fi
    fi

    # 检查ByteArray泄露
    if [[ "$expected_leaks" == *"large_bytearray"* ]]; then
        if grep -q "大ByteArray" "$output_file"; then
            print_success "检测到大ByteArray泄露"
            ((passed++))
        else
            print_error "未检测到大ByteArray泄露"
            ((failed++))
        fi
    fi

    # 检查Activity泄露
    if [[ "$expected_leaks" == *"activity"* ]]; then
        if grep -q "com.koom.leak.MainActivity" "$output_file" && grep -q "泄露" "$output_file"; then
            print_success "检测到Activity泄露"
            ((passed++))
        else
            print_error "未检测到Activity泄露"
            ((failed++))
        fi
    fi

    # 检查重复线程名
    if [[ "$expected_leaks" == *"duplicate_threads"* ]]; then
        local thread_count=$(grep -oP "WorkerThread: \K\d+" "$output_file" || echo "0")
        if [ "$thread_count" -ge 20 ]; then
            print_success "检测到重复线程名 (WorkerThread: $thread_count 个)"
            ((passed++))
        else
            print_error "未检测到足够的重复线程 (WorkerThread: $thread_count 个，期望>=20)"
            ((failed++))
        fi
    fi

    # 检查多组重复线程
    if [[ "$expected_leaks" == *"multiple_thread_groups"* ]]; then
        local group_count=$(grep -c "种 x" "$output_file" || echo "0")
        if [ "$group_count" -ge 1 ]; then
            print_success "检测到多组重复线程"
            ((passed++))
        else
            print_error "未检测到多组重复线程"
            ((failed++))
        fi
    fi

    # 总结
    if [ $failed -eq 0 ]; then
        print_success "测试通过 ($passed/$((passed+failed)))"
        return 0
    else
        print_error "测试失败 ($passed 通过, $failed 失败)"
        return 1
    fi
}

# 测试用例：Bitmap泄露
test_bitmap_leak() {
    print_test "Bitmap泄露检测"

    trigger_leak "com.koom.leak.action.BITMAP_LEAK" "Bitmap泄露"
    dump_heap "test_bitmap_leak.hprof"
    analyze_heap "$TEST_OUTPUT_DIR/test_bitmap_leak.hprof" "large_bitmap"
}

# 测试用例：多个Bitmap泄露
test_multiple_bitmap_leak() {
    print_test "多个Bitmap泄露检测"

    trigger_leak "com.koom.leak.action.MULTIPLE_BITMAP_LEAK" "多个Bitmap泄露"
    dump_heap "test_multiple_bitmap_leak.hprof"
    analyze_heap "$TEST_OUTPUT_DIR/test_multiple_bitmap_leak.hprof" "large_bitmap"
}

# 测试用例：超大Bitmap泄露
test_huge_bitmap_leak() {
    print_test "超大Bitmap泄露检测"

    trigger_leak "com.koom.leak.action.HUGE_BITMAP_LEAK" "超大Bitmap泄露"
    dump_heap "test_huge_bitmap_leak.hprof"
    analyze_heap "$TEST_OUTPUT_DIR/test_huge_bitmap_leak.hprof" "large_bitmap"
}

# 测试用例：ByteArray泄露
test_bytearray_leak() {
    print_test "ByteArray泄露检测"

    trigger_leak "com.koom.leak.action.BYTEARRAY_LEAK" "ByteArray泄露"
    dump_heap "test_bytearray_leak.hprof"
    analyze_heap "$TEST_OUTPUT_DIR/test_bytearray_leak.hprof" "large_bytearray"
}

# 测试用例：重复线程名泄露
test_duplicate_thread_leak() {
    print_test "重复线程名检测"

    trigger_leak "com.koom.leak.action.DUPLICATE_THREAD_LEAK" "重复线程名泄露"
    dump_heap "test_duplicate_thread_leak.hprof"
    analyze_heap "$TEST_OUTPUT_DIR/test_duplicate_thread_leak.hprof" "duplicate_threads"
}

# 测试用例：多组重复线程泄露
test_multiple_duplicate_thread_leak() {
    print_test "多组重复线程检测"

    trigger_leak "com.koom.leak.action.MULTIPLE_DUPLICATE_THREAD_LEAK" "多组重复线程泄露"
    dump_heap "test_multiple_duplicate_thread_leak.hprof"
    analyze_heap "$TEST_OUTPUT_DIR/test_multiple_duplicate_thread_leak.hprof" "duplicate_threads;multiple_thread_groups"
}

# 测试用例：Activity泄露
test_activity_leak() {
    print_test "Activity泄露检测"

    print_info "启动app并触发Activity泄露..."

    # 先强制停止app
    adb shell am force-stop "$PACKAGE_NAME"
    sleep 1

    # 启动app并触发Activity泄露（会退出app）
    adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "com.koom.leak.action.ACTIVITY_LEAK_AND_EXIT"

    # 等待app退出并重新启动
    sleep 3

    # 重新启动app（创建新的MainActivity实例）
    adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"
    sleep 2

    dump_heap "test_activity_leak.hprof"
    analyze_heap "$TEST_OUTPUT_DIR/test_activity_leak.hprof" "activity"
}

# 运行所有测试
run_all_tests() {
    print_info "开始运行所有测试..."

    local total=0
    local passed=0
    local failed=0

    # 运行每个测试
    local tests=(
        "test_bitmap_leak"
        "test_multiple_bitmap_leak"
        "test_huge_bitmap_leak"
        "test_bytearray_leak"
        "test_duplicate_thread_leak"
        "test_multiple_duplicate_thread_leak"
        "test_activity_leak"
    )

    for test in "${tests[@]}"; do
        ((total++))
        if $test; then
            ((passed++))
        else
            ((failed++))
        fi
    done

    # 打印总结
    echo ""
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}  测试总结${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo "总测试数: $total"
    echo -e "${GREEN}通过: $passed${NC}"
    if [ $failed -gt 0 ]; then
        echo -e "${RED}失败: $failed${NC}"
    else
        echo "失败: $failed"
    fi
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

    if [ $failed -eq 0 ]; then
        print_success "所有测试通过！"
        exit 0
    else
        print_error "部分测试失败"
        exit 1
    fi
}

# 主函数
main() {
    check_device

    if [ $# -eq 0 ]; then
        run_all_tests
    else
        case "$1" in
            bitmap)
                test_bitmap_leak
                ;;
            multiple_bitmap)
                test_multiple_bitmap_leak
                ;;
            huge_bitmap)
                test_huge_bitmap_leak
                ;;
            bytearray)
                test_bytearray_leak
                ;;
            duplicate_threads)
                test_duplicate_thread_leak
                ;;
            multiple_threads)
                test_multiple_duplicate_thread_leak
                ;;
            activity)
                test_activity_leak
                ;;
            all)
                run_all_tests
                ;;
            *)
                echo "用法: $0 [bitmap|multiple_bitmap|huge_bitmap|bytearray|duplicate_threads|multiple_threads|activity|all]"
                exit 1
                ;;
        esac
    fi
}

main "$@"
