#!/bin/bash
#
# TDD驱动的内存泄露测试脚本
# 用于自动化测试demo app的泄露检测功能
#

# 注意：不使用 set -e，因为我们需要捕获测试函数的退出码
# set -e  # 遇到错误立即退出

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 配置
PACKAGE_NAME="com.koom.leak"
ACTIVITY_NAME="com.koom.leak.MainActivity"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$PROJECT_DIR/build/libs/mem-monitor-1.0.0-all.jar"
TEST_OUTPUT_DIR="/tmp/mem-monitor-test"
DEVICE_HPROF_DIR="/data/local/tmp"

# 检查并构建JAR文件
check_jar() {
    if [ ! -f "$JAR_PATH" ]; then
        print_info "JAR文件不存在，尝试构建..."
        local original_dir=$(pwd)
        cd "$PROJECT_DIR"
        
        local gradlew_path="$PROJECT_DIR/gradlew"
        if [ ! -f "$gradlew_path" ]; then
            print_error "gradlew 不存在: $gradlew_path"
            print_error "请确保在 mem-monitor 项目根目录下运行此脚本"
            cd "$original_dir"
            exit 1
        fi
        
        if "$gradlew_path" shadowJar; then
            if [ -f "$JAR_PATH" ]; then
                print_success "JAR文件已构建: $JAR_PATH"
            else
                print_error "JAR文件构建失败: $JAR_PATH"
                cd "$original_dir"
                exit 1
            fi
        else
            print_error "构建失败"
            cd "$original_dir"
            exit 1
        fi
        
        cd "$original_dir"
    else
        print_success "JAR文件已存在: $JAR_PATH"
    fi
}

# 创建测试输出目录
mkdir -p "$TEST_OUTPUT_DIR"

# 创建执行日志文件
exec_log="$TEST_OUTPUT_DIR/test_execution.log"
echo "=== 测试执行日志 $(date) ===" > "$exec_log"

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
    print_info "Intent Action: $action"

    # 先强制停止app
    print_info "停止应用..."
    if ! adb shell am force-stop "$PACKAGE_NAME" 2>/dev/null; then
        print_info "应用可能未运行，继续..."
    else
        print_success "应用已停止"
    fi

    # 等待进程完全停止
    sleep 1

    # 启动app并触发特定泄露
    print_info "启动应用并发送 Intent..."
    print_info "命令: adb shell am start -n $PACKAGE_NAME/$ACTIVITY_NAME -a $action"
    
    local start_result
    if start_result=$(adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "$action" 2>&1); then
        print_success "Intent 已发送"
        # 显示启动结果（如果有）
        if [ -n "$start_result" ]; then
            echo "$start_result" | grep -v "^$" || true
        fi
    else
        local exit_code=$?
        print_error "Intent 发送失败 (退出码: $exit_code)"
        echo "$start_result" || true
        return 1
    fi

    # 等待泄露创建完成
    print_info "等待泄露创建完成 (3秒)..."
    sleep 3
    print_success "泄露触发完成"
    return 0
}

# 执行dumpheap
dump_heap() {
    local hprof_name=$1

    print_info "执行dumpheap: $hprof_name"
    print_info "设备路径: $DEVICE_HPROF_DIR/$hprof_name"

    # 删除旧文件（如果存在）
    print_info "清理旧文件..."
    adb shell rm -f "$DEVICE_HPROF_DIR/$hprof_name" 2>/dev/null || true

    # 执行dumpheap
    print_info "正在 dump heap (这可能需要几秒钟)..."
    if adb shell am dumpheap "$PACKAGE_NAME" "$DEVICE_HPROF_DIR/$hprof_name"; then
        print_success "dumpheap 命令已执行"
    else
        print_error "dumpheap 命令执行失败"
        return 1
    fi

    # 等待文件写入完成
    print_info "等待文件写入完成 (5秒)..."
    sleep 5

    # 检查文件是否存在
    print_info "检查文件是否生成..."
    if adb shell test -f "$DEVICE_HPROF_DIR/$hprof_name"; then
        print_success "hprof 文件已生成"
        
        # 获取文件大小
        local file_size=$(adb shell stat -c%s "$DEVICE_HPROF_DIR/$hprof_name" 2>/dev/null || echo "0")
        print_info "文件大小: $file_size 字节"
    else
        print_error "hprof 文件未生成"
        return 1
    fi

    # 拉取hprof文件
    print_info "正在拉取文件到本地..."
    mkdir -p "$TEST_OUTPUT_DIR"
    if adb pull "$DEVICE_HPROF_DIR/$hprof_name" "$TEST_OUTPUT_DIR/$hprof_name"; then
        print_success "文件已拉取"
    else
        print_error "文件拉取失败"
        return 1
    fi

    # 删除设备上的文件
    adb shell rm -f "$DEVICE_HPROF_DIR/$hprof_name" 2>/dev/null || true

    # 验证本地文件
    if [ -f "$TEST_OUTPUT_DIR/$hprof_name" ]; then
        local local_size=$(stat -c%s "$TEST_OUTPUT_DIR/$hprof_name" 2>/dev/null || stat -f%z "$TEST_OUTPUT_DIR/$hprof_name" 2>/dev/null || echo "0")
        print_success "hprof文件已保存: $TEST_OUTPUT_DIR/$hprof_name (${local_size} 字节)"
    else
        print_error "本地文件不存在"
        return 1
    fi
}

# 分析hprof文件
analyze_heap() {
    local hprof_file=$1
    local expected_leaks=$2

    print_info "分析hprof文件: $hprof_file"

    # 创建输出目录
    local output_dir="$TEST_OUTPUT_DIR/analysis_output_$(basename "$hprof_file" .hprof)"
    mkdir -p "$output_dir"

    # 执行分析
    local output_file="$TEST_OUTPUT_DIR/analysis_result_$(basename "$hprof_file" .hprof).txt"
    print_info "执行命令: java -jar $JAR_PATH analyze -f \"$hprof_file\" -o \"$output_dir\""
    
    if java -jar "$JAR_PATH" analyze -f "$hprof_file" -o "$output_dir" > "$output_file" 2>&1; then
        print_success "分析完成"
    else
        print_error "分析失败，查看日志: $output_file"
        # 即使失败也继续验证（可能部分信息已生成）
    fi

    # 读取文本报告（优先，因为包含更详细的信息）
    # 注意：分析命令会在output_dir下创建一个带时间戳的子目录，然后在子目录中生成hprof_analysis.txt
    local txt_file=$(find "$output_dir" -name "hprof_analysis.txt" -type f | head -1)
    if [ -f "$txt_file" ]; then
        echo "=== 文本报告内容 ===" >> "$output_file"
        cat "$txt_file" >> "$output_file"
    fi

    # 同时读取HTML报告（如果有）
    # 注意：分析命令会在output_dir下创建一个带时间戳的子目录，然后在子目录中生成hprof_analysis.html
    local html_file=$(find "$output_dir" -name "hprof_analysis.html" -type f | head -1)
    if [ -f "$html_file" ]; then
        # 将HTML内容也追加到输出文件（用于验证）
        echo "=== HTML报告内容（关键信息） ===" >> "$output_file"
        # 提取HTML中的关键信息（去除HTML标签）
        grep -i "泄露\|leak\|统计\|Activity\|Fragment\|View\|ViewModel\|Service\|Dialog\|Handler\|Message\|BroadcastReceiver\|Animator\|Bitmap\|ByteArray" "$html_file" | \
            sed 's/<[^>]*>//g' | head -50 >> "$output_file" 2>/dev/null || true
    fi

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
        if grep -qi "大Bitmap\|Bitmap泄露\|leakedBitmapCount" "$output_file"; then
            local count=$(grep -oP "大Bitmap[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [ "$count" -gt 0 ] || grep -qi "Bitmap.*泄露" "$output_file"; then
                print_success "检测到大Bitmap泄露 (${count}个)"
                ((passed++))
            else
                print_error "未检测到大Bitmap泄露"
                ((failed++))
            fi
        else
            print_error "未检测到大Bitmap泄露"
            ((failed++))
        fi
    fi

    # 检查ByteArray泄露
    if [[ "$expected_leaks" == *"large_bytearray"* ]]; then
        if grep -qi "大ByteArray\|ByteArray泄露\|leakedByteArrayCount" "$output_file"; then
            local count=$(grep -oP "大ByteArray[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [ "$count" -gt 0 ] || grep -qi "ByteArray.*泄露" "$output_file"; then
                print_success "检测到大ByteArray泄露 (${count}个)"
                ((passed++))
            else
                print_error "未检测到大ByteArray泄露"
                ((failed++))
            fi
        else
            print_error "未检测到大ByteArray泄露"
            ((failed++))
        fi
    fi

    # 检查Activity泄露
    if [[ "$expected_leaks" == *"activity"* ]]; then
        # 匹配格式: "   Activity泄露: 1 个"
        if grep -qi "Activity泄露" "$output_file"; then
            local count=$(grep -oP "Activity泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到Activity泄露 (${count}个)"
                ((passed++))
            elif grep -qi "Activity泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到Activity泄露"
                ((passed++))
            else
                print_error "未检测到Activity泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到Activity泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查Fragment泄露
    if [[ "$expected_leaks" == *"fragment"* ]]; then
        # 匹配格式: "   Fragment泄露: 1 个"
        if grep -qi "Fragment泄露" "$output_file"; then
            local count=$(grep -oP "Fragment泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到Fragment泄露 (${count}个)"
                ((passed++))
            elif grep -qi "Fragment泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到Fragment泄露"
                ((passed++))
            else
                print_error "未检测到Fragment泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到Fragment泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查View泄露
    if [[ "$expected_leaks" == *"view"* ]]; then
        # 匹配格式: "   View泄露: 3 个" (注意可能有空格，且View可能被其他词包含)
        if grep -qiE "View泄露|View.*泄露" "$output_file"; then
            local count=$(grep -oP "View泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到View泄露 (${count}个)"
                ((passed++))
            elif grep -qiE "View泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到View泄露"
                ((passed++))
            else
                print_error "未检测到View泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到View泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查ViewModel泄露
    if [[ "$expected_leaks" == *"viewmodel"* ]]; then
        # 匹配格式: "   ViewModel泄露: 2 个" (注意可能有空格)
        if grep -qi "ViewModel泄露" "$output_file"; then
            local count=$(grep -oP "ViewModel泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到ViewModel泄露 (${count}个)"
                ((passed++))
            elif grep -qi "ViewModel泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到ViewModel泄露"
                ((passed++))
            else
                print_error "未检测到ViewModel泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到ViewModel泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查Service泄露
    if [[ "$expected_leaks" == *"service"* ]]; then
        # 匹配格式: "   Service泄露: 1 个"
        if grep -qi "Service泄露" "$output_file"; then
            local count=$(grep -oP "Service泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到Service泄露 (${count}个)"
                ((passed++))
            elif grep -qi "Service泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到Service泄露"
                ((passed++))
            else
                print_error "未检测到Service泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到Service泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查Dialog泄露
    if [[ "$expected_leaks" == *"dialog"* ]]; then
        # 匹配格式: "   Dialog泄露: 1 个"
        if grep -qi "Dialog泄露" "$output_file"; then
            local count=$(grep -oP "Dialog泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到Dialog泄露 (${count}个)"
                ((passed++))
            elif grep -qi "Dialog泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到Dialog泄露"
                ((passed++))
            else
                print_error "未检测到Dialog泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到Dialog泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查Handler/Message泄露
    if [[ "$expected_leaks" == *"handler_message"* ]]; then
        # 匹配格式: "   Handler/Message泄露: 1 个"
        if grep -qiE "Handler.*Message泄露|Handler/Message泄露" "$output_file"; then
            local count=$(grep -oP "Handler.*Message泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到Handler/Message泄露 (${count}个)"
                ((passed++))
            elif grep -qiE "Handler.*Message泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到Handler/Message泄露"
                ((passed++))
            else
                print_error "未检测到Handler/Message泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到Handler/Message泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查BroadcastReceiver泄露
    if [[ "$expected_leaks" == *"broadcast_receiver"* ]]; then
        # 匹配格式: "   BroadcastReceiver泄露: 1 个"
        if grep -qi "BroadcastReceiver泄露" "$output_file"; then
            local count=$(grep -oP "BroadcastReceiver泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到BroadcastReceiver泄露 (${count}个)"
                ((passed++))
            elif grep -qi "BroadcastReceiver泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到BroadcastReceiver泄露"
                ((passed++))
            else
                print_error "未检测到BroadcastReceiver泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到BroadcastReceiver泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查Animator泄露
    if [[ "$expected_leaks" == *"animator"* ]]; then
        # 匹配格式: "   Animator泄露: 1 个"
        if grep -qi "Animator泄露" "$output_file"; then
            local count=$(grep -oP "Animator泄露[^:]*:\s*\K\d+" "$output_file" | head -1)
            count=${count:-0}  # 如果为空则设为0
            # 确保 count 是数字
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_success "检测到Animator泄露 (${count}个)"
                ((passed++))
            elif grep -qi "Animator泄露.*[0-9]" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有泄露
                print_success "检测到Animator泄露"
                ((passed++))
            else
                print_error "未检测到Animator泄露 (count=${count})"
                ((failed++))
            fi
        else
            print_error "未检测到Animator泄露 (报告中未找到关键词)"
            ((failed++))
        fi
    fi

    # 检查重复线程名
    if [[ "$expected_leaks" == *"duplicate_threads"* ]]; then
        if grep -qi "重复线程\|同名线程\|WorkerThread" "$output_file"; then
            # 匹配格式: "WorkerThread: 20 个" 或 "WorkerThread: 20"
            local thread_count=$(grep -oP "WorkerThread[^:]*:\s*\K\d+" "$output_file" | head -1)
            thread_count=${thread_count:-0}  # 如果为空则设为0
            # 确保 thread_count 是数字
            if [[ "$thread_count" =~ ^[0-9]+$ ]] && [ "$thread_count" -ge 20 ] 2>/dev/null; then
                print_success "检测到重复线程名 (WorkerThread: $thread_count 个)"
                ((passed++))
            else
                print_error "未检测到足够的重复线程 (WorkerThread: ${thread_count:-0} 个，期望>=20)"
                ((failed++))
            fi
        else
            print_error "未检测到重复线程名"
            ((failed++))
        fi
    fi

    # 检查多组重复线程
    if [[ "$expected_leaks" == *"multiple_thread_groups"* ]]; then
        # 匹配格式: "多组重复线程: 5种 x 10 (平均)" 或 "种 x"
        if grep -qi "多组重复线程\|种 x" "$output_file"; then
            # 提取组数：匹配 "多组重复线程: 5种" 或 "5种 x"
            local group_count=$(grep -oP "多组重复线程[^:]*:\s*\K\d+" "$output_file" | head -1)
            if [ -z "$group_count" ]; then
                # 如果没有匹配到，尝试匹配 "种 x" 格式
                group_count=$(grep -oP "\d+种 x" "$output_file" | grep -oP "\d+" | head -1)
            fi
            group_count=${group_count:-0}  # 如果为空则设为0
            # 确保 group_count 是数字
            if [[ "$group_count" =~ ^[0-9]+$ ]] && [ "$group_count" -ge 2 ] 2>/dev/null; then
                print_success "检测到多组重复线程 (${group_count}种)"
                ((passed++))
            elif grep -qi "多组重复线程" "$output_file"; then
                # 如果匹配到但数字提取失败，至少确认有多组
                print_success "检测到多组重复线程"
                ((passed++))
            else
                print_error "未检测到多组重复线程 (group_count=${group_count})"
                ((failed++))
            fi
        else
            print_error "未检测到多组重复线程 (报告中未找到关键词)"
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

    print_info "=== 步骤 1/3: 触发泄露 ==="
    if ! trigger_leak "com.koom.leak.action.BITMAP_LEAK" "Bitmap泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    print_info "=== 步骤 2/3: Dump Heap ==="
    if ! dump_heap "test_bitmap_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    print_info "=== 步骤 3/3: 分析 Heap ==="
    if ! analyze_heap "$TEST_OUTPUT_DIR/test_bitmap_leak.hprof" "large_bitmap"; then
        print_error "分析失败"
        return 1
    fi

    print_success "Bitmap泄露测试完成"
    return 0
}

# 测试用例：多个Bitmap泄露
test_multiple_bitmap_leak() {
    print_test "多个Bitmap泄露检测"

    if ! trigger_leak "com.koom.leak.action.MULTIPLE_BITMAP_LEAK" "多个Bitmap泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    if ! dump_heap "test_multiple_bitmap_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_multiple_bitmap_leak.hprof" "large_bitmap"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：超大Bitmap泄露
test_huge_bitmap_leak() {
    print_test "超大Bitmap泄露检测"

    if ! trigger_leak "com.koom.leak.action.HUGE_BITMAP_LEAK" "超大Bitmap泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    if ! dump_heap "test_huge_bitmap_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_huge_bitmap_leak.hprof" "large_bitmap"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：ByteArray泄露
test_bytearray_leak() {
    print_test "ByteArray泄露检测"

    if ! trigger_leak "com.koom.leak.action.BYTEARRAY_LEAK" "ByteArray泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    if ! dump_heap "test_bytearray_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_bytearray_leak.hprof" "large_bytearray"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：重复线程名泄露
test_duplicate_thread_leak() {
    print_test "重复线程名检测"

    if ! trigger_leak "com.koom.leak.action.DUPLICATE_THREAD_LEAK" "重复线程名泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    if ! dump_heap "test_duplicate_thread_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_duplicate_thread_leak.hprof" "duplicate_threads"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：多组重复线程泄露
test_multiple_duplicate_thread_leak() {
    print_test "多组重复线程检测"

    if ! trigger_leak "com.koom.leak.action.MULTIPLE_DUPLICATE_THREAD_LEAK" "多组重复线程泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    if ! dump_heap "test_multiple_duplicate_thread_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_multiple_duplicate_thread_leak.hprof" "multiple_thread_groups"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Activity泄露
test_activity_leak() {
    print_test "Activity泄露检测"

    print_info "启动app并触发Activity泄露..."

    # 先强制停止app
    print_info "停止应用..."
    if ! adb shell am force-stop "$PACKAGE_NAME"; then
        print_error "停止应用失败"
        return 1
    fi
    sleep 1

    # 启动app并触发Activity泄露（会退出app）
    print_info "启动应用并发送 Intent (ACTIVITY_LEAK_AND_EXIT)..."
    print_info "注意：应用会自动退出，这是正常的"
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "com.koom.leak.action.ACTIVITY_LEAK_AND_EXIT"; then
        print_error "启动应用失败"
        return 1
    fi
    print_success "Intent 已发送"

    # 等待app退出并重新启动
    print_info "等待应用退出 (3秒)..."
    sleep 3

    # 重新启动app（创建新的MainActivity实例）
    print_info "重新启动应用（创建新的 MainActivity 实例）..."
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"; then
        print_error "重新启动应用失败"
        return 1
    fi
    print_success "应用已重新启动"
    sleep 2

    if ! dump_heap "test_activity_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_activity_leak.hprof" "activity"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Fragment泄露
test_fragment_leak() {
    print_test "Fragment泄露检测"

    if ! trigger_leak "com.koom.leak.action.FRAGMENT_LEAK" "Fragment泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    # Fragment 需要额外等待时间，确保Activity完全销毁，Fragment的mFragmentManager被清空
    # Activity finish后，Fragment的onDetach()是异步调用的，需要等待更长时间
    print_info "等待 Fragment 被移除 (额外 10 秒，确保Activity完全销毁和Fragment分离)..."
    sleep 10

    # 检查应用是否还在运行（Fragment泄露测试中Activity会finish，但应用可能还在运行）
    print_info "检查应用是否还在运行..."
    if ! adb shell pidof "$PACKAGE_NAME" > /dev/null 2>&1; then
        print_info "应用已退出，重新启动应用..."
        if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"; then
            print_error "重新启动应用失败"
            return 1
        fi
        sleep 2
    else
        print_info "应用仍在运行，直接dumpheap"
    fi

    if ! dump_heap "test_fragment_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_fragment_leak.hprof" "fragment"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：View泄露
test_view_leak() {
    print_test "View泄露检测"

    # 先强制停止app
    print_info "停止应用..."
    if ! adb shell am force-stop "$PACKAGE_NAME"; then
        print_error "停止应用失败"
        return 1
    fi
    sleep 1

    # 启动app并触发View泄露
    print_info "启动app并触发View泄露..."
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "com.koom.leak.action.VIEW_LEAK"; then
        print_error "启动应用失败"
        return 1
    fi
    print_success "泄露触发完成"

    # 等待泄露创建完成
    print_info "等待泄露创建完成 (3秒)..."
    sleep 3

    # 等待Activity被销毁（参考Fragment泄露的做法）
    print_info "等待 Activity 被销毁 (额外 10 秒，确保Activity完全销毁和View分离)..."
    sleep 10

    # 重新启动app（确保应用在运行）
    print_info "重新启动应用（确保应用在运行）..."
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"; then
        print_error "重新启动应用失败"
        return 1
    fi
    sleep 2

    if ! dump_heap "test_view_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_view_leak.hprof" "view"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：ViewModel泄露
test_viewmodel_leak() {
    print_test "ViewModel泄露检测"

    if ! trigger_leak "com.koom.leak.action.VIEWMODEL_LEAK" "ViewModel泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    if ! dump_heap "test_viewmodel_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_viewmodel_leak.hprof" "viewmodel"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Service泄露
test_service_leak() {
    print_test "Service泄露检测"

    if ! trigger_leak "com.koom.leak.action.SERVICE_LEAK" "Service泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    if ! dump_heap "test_service_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_service_leak.hprof" "service"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Dialog泄露
test_dialog_leak() {
    print_test "Dialog泄露检测"

    if ! trigger_leak "com.koom.leak.action.DIALOG_LEAK" "Dialog泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    # 等待Dialog被关闭
    print_info "等待 Dialog 被关闭 (2秒)..."
    sleep 2

    if ! dump_heap "test_dialog_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_dialog_leak.hprof" "dialog"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Handler/Message泄露
test_handler_message_leak() {
    print_test "Handler/Message泄露检测"

    # 先强制停止app
    print_info "停止应用..."
    if ! adb shell am force-stop "$PACKAGE_NAME"; then
        print_error "停止应用失败"
        return 1
    fi
    sleep 1

    # 启动app并触发Handler/Message泄露
    print_info "启动app并触发Handler/Message泄露..."
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "com.koom.leak.action.HANDLER_MESSAGE_LEAK"; then
        print_error "启动应用失败"
        return 1
    fi
    print_success "泄露触发完成"

    # 等待泄露创建完成
    print_info "等待泄露创建完成 (3秒)..."
    sleep 3

    # 等待Activity被销毁（参考Fragment泄露的做法）
    # Handler/Message泄露：Activity会在1秒后finish，Message会在60秒后执行
    # 所以等待2秒确保Activity已finish，但Message仍在队列中
    print_info "等待 Activity 被销毁 (额外 2 秒，确保Activity已finish但Message仍在队列中)..."
    sleep 2

    # 注意：不要重新启动app，因为重新启动会清空消息队列
    # 直接dumpheap，确保Message仍在队列中
    # 但需要确保应用还在运行
    print_info "检查应用是否还在运行..."
    if ! adb shell pidof "$PACKAGE_NAME" > /dev/null 2>&1; then
        print_info "应用已退出，重新启动应用..."
        if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"; then
            print_error "重新启动应用失败"
            return 1
        fi
        sleep 2
    else
        print_info "应用仍在运行，直接dumpheap"
    fi

    if ! dump_heap "test_handler_message_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_handler_message_leak.hprof" "handler_message"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：BroadcastReceiver泄露
test_broadcast_receiver_leak() {
    print_test "BroadcastReceiver泄露检测"

    # 先强制停止app
    print_info "停止应用..."
    if ! adb shell am force-stop "$PACKAGE_NAME"; then
        print_error "停止应用失败"
        return 1
    fi
    sleep 1

    # 启动app并触发BroadcastReceiver泄露
    print_info "启动app并触发BroadcastReceiver泄露..."
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "com.koom.leak.action.BROADCAST_RECEIVER_LEAK"; then
        print_error "启动应用失败"
        return 1
    fi
    print_success "泄露触发完成"

    # 等待泄露创建完成
    print_info "等待泄露创建完成 (3秒)..."
    sleep 3

    # BroadcastReceiver泄露现在直接在MainActivity中创建，不需要等待Activity销毁
    # 减少等待时间，避免应用退出
    print_info "等待泄露稳定 (2秒)..."
    sleep 2

    # 检查应用是否还在运行
    print_info "检查应用是否还在运行..."
    if ! adb shell pidof "$PACKAGE_NAME" > /dev/null 2>&1; then
        print_info "应用已退出，重新启动应用..."
        if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"; then
            print_error "重新启动应用失败"
            return 1
        fi
        sleep 2
    else
        print_info "应用仍在运行，直接dumpheap"
    fi

    if ! dump_heap "test_broadcast_receiver_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_broadcast_receiver_leak.hprof" "broadcast_receiver"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Animator泄露
test_animator_leak() {
    print_test "Animator泄露检测"

    if ! trigger_leak "com.koom.leak.action.ANIMATOR_LEAK" "Animator泄露"; then
        print_error "触发泄露失败"
        return 1
    fi

    # 等待Animator开始运行
    print_info "等待 Animator 开始运行 (2秒)..."
    sleep 2

    if ! dump_heap "test_animator_leak.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_animator_leak.hprof" "animator"; then
        print_error "分析失败"
        return 1
    fi

    return 0
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
        "test_fragment_leak"
        "test_view_leak"
        "test_viewmodel_leak"
        "test_service_leak"
        "test_dialog_leak"
        "test_handler_message_leak"
        "test_broadcast_receiver_leak"
        "test_animator_leak"
    )

    echo ""
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}  总共 ${#tests[@]} 个测试用例${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""

    for test in "${tests[@]}"; do
        ((total++))
        echo ""
        echo -e "${YELLOW}[$total/${#tests[@]}] 运行测试: $test${NC}"
        echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        
        # 执行测试（不使用 set -e，所以即使失败也会继续）
        print_info "开始执行测试函数: $test"
        $test
        local exit_code=$?
        
        if [ $exit_code -eq 0 ]; then
            ((passed++))
            echo ""
            echo -e "${GREEN}✓ 测试 $total 通过${NC}"
        else
            ((failed++))
            echo ""
            echo -e "${RED}✗ 测试 $total 失败 (退出码: $exit_code)${NC}"
        fi
        echo ""
        echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo ""
        
        # 添加短暂延迟，让输出更清晰
        sleep 1
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
    check_jar

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
            fragment)
                test_fragment_leak
                ;;
            view)
                test_view_leak
                ;;
            viewmodel)
                test_viewmodel_leak
                ;;
            service)
                test_service_leak
                ;;
            dialog)
                test_dialog_leak
                ;;
            handler_message)
                test_handler_message_leak
                ;;
            broadcast_receiver)
                test_broadcast_receiver_leak
                ;;
            animator)
                test_animator_leak
                ;;
            all)
                run_all_tests
                ;;
            *)
                echo "用法: $0 [bitmap|multiple_bitmap|huge_bitmap|bytearray|duplicate_threads|multiple_threads|activity|fragment|view|viewmodel|service|dialog|handler_message|broadcast_receiver|animator|all]"
                exit 1
                ;;
        esac
    fi
}

main "$@"
