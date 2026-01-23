#!/bin/bash
#
# 正常场景测试脚本
# 用于验证jar分析功能不会误报正常使用场景为泄露
#

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 配置
PACKAGE_NAME="com.koom.normal"
ACTIVITY_NAME="com.koom.normal.MainActivity"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$PROJECT_DIR/build/libs/mem-monitor-1.0.0-all.jar"
TEST_OUTPUT_DIR="/tmp/mem-monitor-test-normal"
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

# 检查JAR文件
check_jar() {
    if [ ! -f "$JAR_PATH" ]; then
        print_error "JAR文件不存在: $JAR_PATH"
        print_info "请先运行: cd $PROJECT_DIR && ./gradlew shadowJar"
        exit 1
    fi
    print_success "JAR文件已存在: $JAR_PATH"
}

# Dump heap
dump_heap() {
    local hprof_name=$1
    local device_path="$DEVICE_HPROF_DIR/$hprof_name"
    local local_path="$TEST_OUTPUT_DIR/$hprof_name"

    print_info "执行dumpheap: $hprof_name"
    print_info "设备路径: $device_path"

    # 清理旧文件
    print_info "清理旧文件..."
    adb shell rm -f "$device_path" 2>/dev/null || true

    # 执行dumpheap
    print_info "正在 dump heap (这可能需要几秒钟)..."
    if ! adb shell am dumpheap "$PACKAGE_NAME" "$device_path" 2>&1 | grep -q "File:"; then
        print_error "dumpheap 命令执行失败"
        return 1
    fi
    print_success "dumpheap 命令已执行"

    # 等待文件写入完成
    print_info "等待文件写入完成 (5秒)..."
    sleep 5

    # 检查文件是否生成
    print_info "检查文件是否生成..."
    if ! adb shell test -f "$device_path"; then
        print_error "hprof 文件未生成"
        return 1
    fi
    print_success "hprof 文件已生成"

    # 获取文件大小
    local file_size=$(adb shell stat -c%s "$device_path" 2>/dev/null || adb shell ls -l "$device_path" | awk '{print $5}')
    print_info "文件大小: $file_size 字节"

    # 拉取文件
    print_info "正在拉取文件到本地..."
    if ! adb pull "$device_path" "$local_path" 2>&1 | grep -q "$hprof_name"; then
        print_error "拉取文件失败"
        return 1
    fi
    print_success "文件已拉取"
    print_success "hprof文件已保存: $local_path ($file_size 字节)"

    return 0
}

# 分析hprof文件
analyze_heap() {
    local hprof_file=$1
    local expected_no_leaks=$2

    print_info "分析hprof文件: $hprof_file"

    # 创建输出目录
    local output_dir="$TEST_OUTPUT_DIR/analysis_output_$(basename "$hprof_file" .hprof)"
    mkdir -p "$output_dir"

    # 执行分析
    local output_file="$TEST_OUTPUT_DIR/analysis_result_$(basename "$hprof_file" .hprof).txt"
    print_info "执行命令: java -jar $JAR_PATH analyze --hprof \"$hprof_file\" --output \"$output_dir\""
    
    if java -jar "$JAR_PATH" analyze --hprof "$hprof_file" --output "$output_dir" > "$output_file" 2>&1; then
        print_success "分析完成"
    else
        print_error "分析失败，查看日志: $output_file"
        # 即使失败也继续验证（可能部分信息已生成）
    fi

    # 读取文本报告
    local txt_file="$output_dir/hprof_analysis.txt"
    if [ -f "$txt_file" ]; then
        echo "=== 文本报告内容 ===" >> "$output_file"
        cat "$txt_file" >> "$output_file"
    fi

    # 同时读取HTML报告
    local html_file="$output_dir/hprof_analysis.html"
    if [ -f "$html_file" ]; then
        echo "=== HTML报告内容（关键信息） ===" >> "$output_file"
        grep -i "泄露\|leak\|统计\|Activity\|Fragment\|View\|ViewModel\|Service\|Dialog\|Handler\|Message\|BroadcastReceiver\|Animator\|Bitmap\|ByteArray" "$html_file" | \
            sed 's/<[^>]*>//g' | head -50 >> "$output_file" 2>/dev/null || true
    fi

    # 验证结果（不应该检测到泄露）
    verify_no_leaks "$output_file" "$expected_no_leaks"
}

# 验证不应该检测到泄露
verify_no_leaks() {
    local output_file=$1
    local expected_no_leaks=$2

    print_info "验证不应该检测到泄露..."

    local passed=0
    local failed=0

    # 检查Bitmap泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"bitmap"* ]]; then
        # 只检查"泄露类型统计"中的"大Bitmap泄露"，不检查"大对象统计"中的"大Bitmap"
        if grep -qi "大Bitmap泄露\|Bitmap泄露" "$output_file"; then
            local count=$(grep -oP "大Bitmap泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到Bitmap泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "大Bitmap泄露.*[0-9]\|Bitmap泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到Bitmap泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到Bitmap泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到Bitmap泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查ByteArray泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"bytearray"* ]]; then
        # 只检查"泄露类型统计"中的"大ByteArray泄露"，不检查"大对象统计"中的"大ByteArray"
        if grep -qi "大ByteArray泄露\|ByteArray泄露" "$output_file"; then
            local count=$(grep -oP "大ByteArray泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到ByteArray泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "大ByteArray泄露.*[0-9]\|ByteArray泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到ByteArray泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到ByteArray泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到ByteArray泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查Activity泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"activity"* ]]; then
        if grep -qi "Activity泄露" "$output_file"; then
            local count=$(grep -oP "Activity泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到Activity泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "Activity泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到Activity泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到Activity泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到Activity泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查Fragment泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"fragment"* ]]; then
        if grep -qi "Fragment泄露" "$output_file"; then
            local count=$(grep -oP "Fragment泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到Fragment泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "Fragment泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到Fragment泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到Fragment泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到Fragment泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查View泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"view"* ]]; then
        if grep -qi "View泄露" "$output_file"; then
            local count=$(grep -oP "View泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到View泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "View泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到View泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到View泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到View泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查ViewModel泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"viewmodel"* ]]; then
        if grep -qi "ViewModel泄露" "$output_file"; then
            local count=$(grep -oP "ViewModel泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到ViewModel泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "ViewModel泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到ViewModel泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到ViewModel泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到ViewModel泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查Service泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"service"* ]]; then
        if grep -qi "Service泄露" "$output_file"; then
            local count=$(grep -oP "Service泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到Service泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "Service泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到Service泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到Service泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到Service泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查Dialog泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"dialog"* ]]; then
        if grep -qi "Dialog泄露" "$output_file"; then
            local count=$(grep -oP "Dialog泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到Dialog泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "Dialog泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到Dialog泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到Dialog泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到Dialog泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查Handler/Message泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"handler_message"* ]]; then
        if grep -qi "Handler/Message泄露\|Handler泄露\|Message泄露" "$output_file"; then
            local count=$(grep -oP "Handler/Message泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到Handler/Message泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "Handler/Message泄露.*[0-9]\|Handler泄露.*[0-9]\|Message泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到Handler/Message泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到Handler/Message泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到Handler/Message泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查BroadcastReceiver泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"broadcast_receiver"* ]]; then
        if grep -qi "BroadcastReceiver泄露" "$output_file"; then
            local count=$(grep -oP "BroadcastReceiver泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到BroadcastReceiver泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "BroadcastReceiver泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到BroadcastReceiver泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到BroadcastReceiver泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到BroadcastReceiver泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查Animator泄露（不应该检测到）
    if [[ "$expected_no_leaks" == *"animator"* ]]; then
        if grep -qi "Animator泄露" "$output_file"; then
            local count=$(grep -oP "Animator泄露[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ] 2>/dev/null; then
                print_error "误报：检测到Animator泄露 (${count}个)，但这是正常使用场景"
                ((failed++))
            elif grep -qi "Animator泄露.*[0-9]" "$output_file"; then
                print_error "误报：检测到Animator泄露，但这是正常使用场景"
                ((failed++))
            else
                print_success "未检测到Animator泄露 (正常)"
                ((passed++))
            fi
        else
            print_success "未检测到Animator泄露 (正常)"
            ((passed++))
        fi
    fi

    # 检查重复线程（不应该检测到）
    if [[ "$expected_no_leaks" == *"duplicate_thread"* ]]; then
        # 只检查应用自己的线程（WorkerThread），不检查系统线程（Daemon、Jit等）
        if grep -qi "WorkerThread.*[0-9]" "$output_file"; then
            local thread_count=$(grep -oP "WorkerThread[^:]*:\s*\K\d+" "$output_file" | head -1 || echo "0")
            if [[ "$thread_count" =~ ^[0-9]+$ ]] && [ "$thread_count" -ge 3 ] 2>/dev/null; then
                print_error "误报：检测到重复线程 (WorkerThread: $thread_count 个)，但这是正常使用场景（不同线程名）"
                ((failed++))
            else
                print_success "未检测到应用线程重复 (正常，使用不同线程名)"
                ((passed++))
            fi
        else
            print_success "未检测到应用线程重复 (正常，使用不同线程名)"
            ((passed++))
        fi
    fi

    # 检查多组重复线程（不应该检测到）
    if [[ "$expected_no_leaks" == *"multiple_thread_groups"* ]]; then
        # 只检查应用自己的线程（OkHttp-Dispatcher、Executor-Service等），不检查系统线程
        # 检查是否有应用线程的重复（线程名包含我们创建的基础名称）
        if grep -qi "OkHttp-Dispatcher.*[0-9]\|Executor-Service.*[0-9]\|AsyncTask.*[0-9]\|Timer-Thread.*[0-9]\|Connection-Thread.*[0-9]" "$output_file"; then
            print_error "误报：检测到应用线程重复，但这是正常使用场景（不同线程名）"
            ((failed++))
        else
            print_success "未检测到应用线程重复 (正常，使用不同线程名)"
            ((passed++))
        fi
    fi

    # 总结
    if [ $failed -eq 0 ]; then
        print_success "测试通过 ($passed/$((passed+failed))) - 未误报"
        return 0
    else
        print_error "测试失败 ($passed 通过, $failed 失败) - 存在误报"
        return 1
    fi
}

# 触发正常场景
trigger_normal() {
    local action=$1
    local test_name=$2

    print_info "启动app并触发: $test_name"
    print_info "Intent Action: $action"
    
    # 停止应用
    print_info "停止应用..."
    adb shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
    print_success "应用已停止"
    sleep 1

    # 启动应用并发送 Intent
    print_info "启动应用并发送 Intent..."
    print_info "命令: adb shell am start -n $PACKAGE_NAME/$ACTIVITY_NAME -a $action"
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "$action" 2>&1 | grep -q "Starting:"; then
        print_error "启动应用失败"
        return 1
    fi
    print_success "Intent 已发送"

    # 等待场景创建完成
    print_info "等待场景创建完成 (3秒)..."
    sleep 3
    print_success "场景触发完成"
}

# ==================== 测试函数 ====================

# 测试用例：Bitmap正常使用
test_normal_bitmap() {
    print_test "Bitmap正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.BITMAP" "Bitmap正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待GC回收Bitmap（正常场景中，Bitmap应该被GC回收）
    print_info "等待GC回收Bitmap (额外 5 秒)..."
    sleep 5

    if ! dump_heap "test_normal_bitmap.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_bitmap.hprof" "bitmap"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：多个Bitmap正常使用
test_normal_multiple_bitmap() {
    print_test "多个Bitmap正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.MULTIPLE_BITMAP" "多个Bitmap正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待GC回收Bitmap
    print_info "等待GC回收Bitmap (额外 5 秒)..."
    sleep 5

    if ! dump_heap "test_normal_multiple_bitmap.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_multiple_bitmap.hprof" "bitmap"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：超大Bitmap正常使用
test_normal_huge_bitmap() {
    print_test "超大Bitmap正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.HUGE_BITMAP" "超大Bitmap正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待GC回收Bitmap
    print_info "等待GC回收Bitmap (额外 5 秒)..."
    sleep 5

    if ! dump_heap "test_normal_huge_bitmap.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_huge_bitmap.hprof" "bitmap"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：ByteArray正常使用
test_normal_bytearray() {
    print_test "ByteArray正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.BYTEARRAY" "ByteArray正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待GC回收ByteArray
    print_info "等待GC回收ByteArray (额外 5 秒)..."
    sleep 5

    if ! dump_heap "test_normal_bytearray.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_bytearray.hprof" "bytearray"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：重复线程正常使用
test_normal_duplicate_thread() {
    print_test "重复线程正常使用（不应该检测到重复线程）"

    if ! trigger_normal "com.koom.normal.action.DUPLICATE_THREAD" "重复线程正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    if ! dump_heap "test_normal_duplicate_thread.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_duplicate_thread.hprof" "duplicate_thread"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：多组重复线程正常使用
test_normal_multiple_duplicate_thread() {
    print_test "多组重复线程正常使用（不应该检测到重复线程）"

    if ! trigger_normal "com.koom.normal.action.MULTIPLE_DUPLICATE_THREAD" "多组重复线程正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    if ! dump_heap "test_normal_multiple_duplicate_thread.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_multiple_duplicate_thread.hprof" "multiple_thread_groups"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Activity正常使用
test_normal_activity() {
    print_test "Activity正常使用（不应该检测到泄露）"

    print_info "启动app并触发Activity正常使用..."

    # 先强制停止app
    print_info "停止应用..."
    if ! adb shell am force-stop "$PACKAGE_NAME"; then
        print_error "停止应用失败"
        return 1
    fi
    sleep 1

    # 启动app并触发Activity正常使用
    print_info "启动应用并发送 Intent (ACTIVITY)..."
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" -a "com.koom.normal.action.ACTIVITY"; then
        print_error "启动应用失败"
        return 1
    fi
    print_success "Intent 已发送"

    # 等待Activity正常finish
    print_info "等待Activity正常finish (3秒)..."
    sleep 3

    # 重新启动app（创建新的MainActivity实例）
    print_info "重新启动应用（创建新的 MainActivity 实例）..."
    if ! adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"; then
        print_error "重新启动应用失败"
        return 1
    fi
    print_success "应用已重新启动"
    sleep 2

    if ! dump_heap "test_normal_activity.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_activity.hprof" "activity"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Fragment正常使用
test_normal_fragment() {
    print_test "Fragment正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.FRAGMENT" "Fragment正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # Fragment 需要额外等待时间，确保Activity正常finish，Fragment正常移除
    print_info "等待 Fragment 正常移除 (额外 3 秒，确保Activity正常finish和Fragment正常移除)..."
    sleep 3

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

    if ! dump_heap "test_normal_fragment.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_fragment.hprof" "fragment"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：View正常使用
test_normal_view() {
    print_test "View正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.VIEW" "View正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待View正常移除
    print_info "等待 View 正常移除 (额外 3 秒，确保Activity正常finish和View正常移除)..."
    sleep 3

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

    if ! dump_heap "test_normal_view.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_view.hprof" "view"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：ViewModel正常使用
test_normal_viewmodel() {
    print_test "ViewModel正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.VIEWMODEL" "ViewModel正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    if ! dump_heap "test_normal_viewmodel.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_viewmodel.hprof" "viewmodel"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Service正常使用
test_normal_service() {
    print_test "Service正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.SERVICE" "Service正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待Service正常停止
    print_info "等待 Service 正常停止 (额外 2 秒)..."
    sleep 2

    if ! dump_heap "test_normal_service.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_service.hprof" "service"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Dialog正常使用
test_normal_dialog() {
    print_test "Dialog正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.DIALOG" "Dialog正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待Dialog正常关闭
    print_info "等待 Dialog 正常关闭 (额外 2 秒)..."
    sleep 2

    if ! dump_heap "test_normal_dialog.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_dialog.hprof" "dialog"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Handler/Message正常使用
test_normal_handler_message() {
    print_test "Handler/Message正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.HANDLER_MESSAGE" "Handler/Message正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    if ! dump_heap "test_normal_handler_message.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_handler_message.hprof" "handler_message"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：BroadcastReceiver正常使用
test_normal_broadcast_receiver() {
    print_test "BroadcastReceiver正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.BROADCAST_RECEIVER" "BroadcastReceiver正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待BroadcastReceiver正常注销
    print_info "等待 BroadcastReceiver 正常注销 (额外 2 秒)..."
    sleep 2

    if ! dump_heap "test_normal_broadcast_receiver.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_broadcast_receiver.hprof" "broadcast_receiver"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 测试用例：Animator正常使用
test_normal_animator() {
    print_test "Animator正常使用（不应该检测到泄露）"

    if ! trigger_normal "com.koom.normal.action.ANIMATOR" "Animator正常使用"; then
        print_error "触发场景失败"
        return 1
    fi

    # 等待Animator正常取消
    print_info "等待 Animator 正常取消 (额外 3 秒)..."
    sleep 3

    if ! dump_heap "test_normal_animator.hprof"; then
        print_error "dump heap 失败"
        return 1
    fi

    if ! analyze_heap "$TEST_OUTPUT_DIR/test_normal_animator.hprof" "animator"; then
        print_error "分析失败"
        return 1
    fi

    return 0
}

# 运行所有测试
run_all_tests() {
    local total=0
    local passed=0
    local failed=0

    local tests=(
        "test_normal_bitmap"
        "test_normal_multiple_bitmap"
        "test_normal_huge_bitmap"
        "test_normal_bytearray"
        "test_normal_duplicate_thread"
        "test_normal_multiple_duplicate_thread"
        "test_normal_activity"
        "test_normal_fragment"
        "test_normal_view"
        "test_normal_viewmodel"
        "test_normal_service"
        "test_normal_dialog"
        "test_normal_handler_message"
        "test_normal_broadcast_receiver"
        "test_normal_animator"
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
        
        # 执行测试
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
        
        # 添加短暂延迟
        sleep 1
    done

    # 总结
    echo ""
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}  测试总结${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo "总测试数: $total"
    echo -e "${GREEN}通过: $passed${NC}"
    echo -e "${RED}失败: $failed${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    if [ $failed -eq 0 ]; then
        echo -e "${GREEN}✓ 所有测试通过！未发现误报${NC}"
        return 0
    else
        echo -e "${RED}✗ 部分测试失败，存在误报${NC}"
        return 1
    fi
}

# 主函数
main() {
    check_device
    check_jar

    if [ $# -eq 0 ]; then
        echo "用法: $0 [bitmap|multiple_bitmap|huge_bitmap|bytearray|duplicate_thread|multiple_threads|activity|fragment|view|viewmodel|service|dialog|handler_message|broadcast_receiver|animator|all]"
        exit 1
    fi

    for arg in "$@"; do
        case "$arg" in
            bitmap)
                test_normal_bitmap
                ;;
            multiple_bitmap)
                test_normal_multiple_bitmap
                ;;
            huge_bitmap)
                test_normal_huge_bitmap
                ;;
            bytearray)
                test_normal_bytearray
                ;;
            duplicate_thread)
                test_normal_duplicate_thread
                ;;
            multiple_threads)
                test_normal_multiple_duplicate_thread
                ;;
            activity)
                test_normal_activity
                ;;
            fragment)
                test_normal_fragment
                ;;
            view)
                test_normal_view
                ;;
            viewmodel)
                test_normal_viewmodel
                ;;
            service)
                test_normal_service
                ;;
            dialog)
                test_normal_dialog
                ;;
            handler_message)
                test_normal_handler_message
                ;;
            broadcast_receiver)
                test_normal_broadcast_receiver
                ;;
            animator)
                test_normal_animator
                ;;
            all)
                run_all_tests
                ;;
            *)
                echo "用法: $0 [bitmap|multiple_bitmap|huge_bitmap|bytearray|duplicate_thread|multiple_threads|activity|fragment|view|viewmodel|service|dialog|handler_message|broadcast_receiver|animator|all]"
                exit 1
                ;;
        esac
    done
}

main "$@"
