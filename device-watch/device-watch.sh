#!/system/bin/sh
# ============================================================================
# mem-analyze 设备端内存监控脚本
# 
# 功能：在 Android 设备上直接运行，监控应用内存并自动 dump hprof
# 
# 使用方法：
#   1. 将此脚本推送到设备：adb push device-watch.sh /data/local/tmp/
#   2. 在设备上执行：adb shell sh /data/local/tmp/device-watch.sh -p <包名>
#   3. 或者进入 shell 后执行：sh /data/local/tmp/device-watch.sh -p <包名>
#
# 参数：
#   -p <包名>       必填，要监控的应用包名（可多次指定以监控多个应用）
#   -t <阈值>       堆内存使用率阈值，默认 80 (%)
#   -i <间隔>       监控间隔秒数，默认 10
#   -n <次数>       连续超过阈值触发dump的次数，默认 3
#   -o <输出目录>   hprof 输出目录，默认 /data/local/tmp/mem-analyze
#   -m <最大次数>   最大监控次数，0 表示无限，默认 0
#   -g              dump 前执行 GC（默认启用，使用 -g 可显式指定）
#   -b              包含 bitmap 数据 (Android 14+)
#   --fd-threshold <数量>    FD 阈值，默认 1000
#   --thread-threshold <数量> 线程阈值，默认 750（EMUI且≤8.0为450）
#   --heap-high-watermark <百分比> 堆高水位线，默认 90（立即触发）
#   --heap-delta <MB>        堆增量阈值，默认 350MB（立即触发）
#   -h              显示帮助
#
# 示例：
#   sh /data/local/tmp/device-watch.sh -p com.example.app -t 70 -i 5
#   sh /data/local/tmp/device-watch.sh -p com.example.app -p com.other.app -g -b
#
# 输出：
#   hprof 文件保存在 /data/local/tmp/mem-analyze/ 目录下
#   文件名格式：heap_<包名>_<时间戳>.hprof
#
# 后续分析：
#   adb pull /data/local/tmp/mem-analyze/ ./
#   java -jar mem-analyze-1.0.0-all.jar analyze <hprof文件>
# ============================================================================

# 默认配置（参考 KOOM）
PACKAGE_LIST=""   # 包名列表，空格分隔（支持 -p 多次指定）
HEAP_THRESHOLD=80
INTERVAL=10
TRIGGER_COUNT=3
OUTPUT_DIR="/data/local/tmp/mem-analyze"
MAX_ITERATIONS=0
USE_GC=true  # 默认启用 GC，减少非泄露对象的干扰
USE_BITMAP=false

# FD/Thread 阈值（参考 KOOM 默认值）
FD_THRESHOLD=1000
THREAD_THRESHOLD=750  # 默认值，会根据设备调整
FD_THRESHOLD_GAP=50   # FD 浮动范围
THREAD_THRESHOLD_GAP=50  # Thread 浮动范围

# Heap 剧烈增长检测（参考 KOOM）
HEAP_HIGH_WATERMARK=90  # 高水位线 90%
HEAP_DELTA_THRESHOLD_MB=350  # 增量阈值 350MB

# 状态变量
heap_over_threshold_count=0
fd_over_threshold_count=0
thread_over_threshold_count=0
last_heap_ratio=0
last_fd_count=0
last_thread_count=0
has_dumped=false
iteration=0

# 颜色输出 (部分设备支持)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 显示帮助
show_help() {
    echo "=============================================="
    echo "  mem-analyze 设备端内存监控"
    echo "=============================================="
    echo ""
    echo "用法: sh $0 -p <包名> [ -p <包名2> ... ] [选项]"
    echo ""
    echo "必填参数:"
    echo "  -p <包名>       要监控的应用包名（可多次指定以监控多个应用）"
    echo ""
    echo "可选参数:"
    echo "  -t <阈值>       堆内存使用率阈值 (%)，默认: 80"
    echo "  -i <间隔>       监控间隔 (秒)，默认: 10"
    echo "  -n <次数>       连续超过阈值触发dump次数，默认: 3"
    echo "  -o <目录>       输出目录，默认: /data/local/tmp/mem-analyze"
    echo "  -m <次数>       最大监控次数，0=无限，默认: 0"
    echo "  -g              dump 前执行 GC（默认启用）"
    echo "  -b              包含 bitmap 数据 (Android 14+)"
    echo "  --fd-threshold <数量>    FD 阈值，默认: 1000"
    echo "  --thread-threshold <数量> 线程阈值，默认: 750"
    echo "  --heap-high-watermark <百分比> 堆高水位线，默认: 90（立即触发）"
    echo "  --heap-delta <MB>        堆增量阈值，默认: 350MB（立即触发）"
    echo "  -h              显示此帮助"
    echo ""
    echo "示例:"
    echo "  sh $0 -p com.example.app"
    echo "  sh $0 -p com.example.app -p com.other.app"
    echo "  sh $0 -p com.example.app -t 70 -i 5 -g"
    echo "  sh $0 -p com.example.app --fd-threshold 500 --thread-threshold 200"
    echo "  sh $0 -p com.example.app --heap-high-watermark 85 --heap-delta 200"
    echo ""
    echo "输出文件位置: /data/local/tmp/mem-analyze/"
    echo ""
    exit 0
}

# 解析参数（支持多个 -p 指定包名列表）
while [ $# -gt 0 ]; do
    case "$1" in
        -p)
            if [ -n "$PACKAGE_LIST" ]; then
                PACKAGE_LIST="$PACKAGE_LIST $2"
            else
                PACKAGE_LIST="$2"
            fi
            shift 2
            ;;
        -t) HEAP_THRESHOLD="$2"; shift 2 ;;
        -i) INTERVAL="$2"; shift 2 ;;
        -n) TRIGGER_COUNT="$2"; shift 2 ;;
        -o) OUTPUT_DIR="$2"; shift 2 ;;
        -m) MAX_ITERATIONS="$2"; shift 2 ;;
        -g) USE_GC=true; shift ;;
        -b) USE_BITMAP=true; shift ;;
        --fd-threshold) FD_THRESHOLD="$2"; shift 2 ;;
        --thread-threshold) THREAD_THRESHOLD="$2"; shift 2 ;;
        --heap-high-watermark) HEAP_HIGH_WATERMARK="$2"; shift 2 ;;
        --heap-delta) HEAP_DELTA_THRESHOLD_MB="$2"; shift 2 ;;
        -h) show_help ;;
        *) echo "未知参数: $1"; show_help ;;
    esac
done

# 检查必填参数
if [ -z "$PACKAGE_LIST" ]; then
    echo "错误: 必须指定至少一个包名 (-p)"
    echo ""
    show_help
fi
# 兼容单包名：PACKAGE 取第一个包名
PACKAGE=$(echo "$PACKAGE_LIST" | awk '{print $1}')

# 创建输出目录
mkdir -p "$OUTPUT_DIR"

# 根据设备调整 Thread 阈值（参考 KOOM）
detect_thread_threshold() {
    local rom=$(getprop ro.build.version.emui 2>/dev/null)
    local sdk=$(getprop ro.build.version.sdk 2>/dev/null)
    if [ -n "$rom" ] && [ -n "$sdk" ] && [ "$sdk" -le 26 ]; then
        # EMUI 且 Android <= 8.0
        THREAD_THRESHOLD=450
    fi
}
detect_thread_threshold

# 获取时间戳
get_timestamp() {
    date "+%Y%m%d_%H%M%S"
}

# 获取格式化时间
get_time() {
    date "+%H:%M:%S"
}

# 获取进程 PID
get_pid() {
    local pkg="$1"
    # 尝试多种方式获取 PID
    local pid=$(pidof "$pkg" 2>/dev/null)
    if [ -z "$pid" ]; then
        pid=$(ps -A | grep "$pkg" | grep -v grep | awk '{print $2}' | head -1)
    fi
    if [ -z "$pid" ]; then
        pid=$(dumpsys activity processes | grep -A 1 "ProcessRecord{.*$pkg" | grep "pid=" | sed 's/.*pid=\([0-9]*\).*/\1/' | head -1)
    fi
    echo "$pid"
}

# 获取内存信息
get_memory_info() {
    local pkg="$1"
    local meminfo=$(dumpsys meminfo "$pkg" 2>/dev/null)
    
    if [ -z "$meminfo" ]; then
        echo "0 0 0"
        return
    fi
    
    # 解析 TOTAL 行获取已用内存
    local total_pss=$(echo "$meminfo" | grep "TOTAL:" | head -1 | awk '{print $2}')
    if [ -z "$total_pss" ]; then
        total_pss=$(echo "$meminfo" | grep "TOTAL" | head -1 | awk '{print $2}')
    fi
    
    # 解析 App Summary 中的 Java Heap
    local java_heap=$(echo "$meminfo" | grep "Java Heap:" | awk '{print $3}')
    if [ -z "$java_heap" ]; then
        java_heap="0"
    fi
    
    # 获取堆内存限制
    local heap_limit=$(getprop dalvik.vm.heapsize 2>/dev/null | sed 's/m//i')
    if [ -z "$heap_limit" ] || [ "$heap_limit" = "0" ]; then
        heap_limit=$(getprop dalvik.vm.heapgrowthlimit 2>/dev/null | sed 's/m//i')
    fi
    if [ -z "$heap_limit" ] || [ "$heap_limit" = "0" ]; then
        heap_limit="512"
    fi
    
    # 转换为 KB
    heap_limit=$((heap_limit * 1024))
    
    echo "${java_heap:-0} ${heap_limit} ${total_pss:-0}"
}

# 获取线程数
get_thread_count() {
    local pid="$1"
    if [ -n "$pid" ] && [ -d "/proc/$pid/task" ]; then
        ls -1 "/proc/$pid/task" 2>/dev/null | wc -l
    else
        echo "0"
    fi
}

# 获取文件句柄数
get_fd_count() {
    local pid="$1"
    if [ -n "$pid" ] && [ -d "/proc/$pid/fd" ]; then
        ls -1 "/proc/$pid/fd" 2>/dev/null | wc -l
    else
        echo "0"
    fi
}

# 获取 FD 详细信息（参考 KOOM FdOOMTracker）
dump_fd_info() {
    local pid="$1"
    local output_file="$2"
    
    if [ -z "$pid" ] || [ ! -d "/proc/$pid/fd" ]; then
        echo "  ✗ 无法访问 /proc/$pid/fd"
        return 1
    fi
    
    echo "  正在 dump FD 信息..."
    
    # 读取所有 FD 的链接目标（参考 KOOM）
    local fd_list=""
    local fd_count=0
    local socket_count=0
    local pipe_count=0
    local file_count=0
    local anon_count=0
    
    for fd in /proc/$pid/fd/*; do
        if [ -L "$fd" ]; then
            local target=$(readlink "$fd" 2>/dev/null || echo "failed to read link $fd")
            if [ -n "$fd_list" ]; then
                fd_list="${fd_list}\n${target}"
            else
                fd_list="${target}"
            fi
            fd_count=$((fd_count + 1))
            
            # 统计不同类型的 FD
            case "$target" in
                socket:*)
                    socket_count=$((socket_count + 1))
                    ;;
                pipe:*)
                    pipe_count=$((pipe_count + 1))
                    ;;
                anon_inode:*)
                    anon_count=$((anon_count + 1))
                    ;;
                /*)
                    file_count=$((file_count + 1))
                    ;;
            esac
        fi
    done
    
    # 保存到文件（按字母排序，参考 KOOM）
    if [ -n "$fd_list" ]; then
        # 使用 sort 排序（如果可用），否则直接保存
        if command -v sort >/dev/null 2>&1; then
            echo -e "$fd_list" | sort > "$output_file" 2>/dev/null
        else
            echo -e "$fd_list" > "$output_file" 2>/dev/null
        fi
        
        if [ -f "$output_file" ]; then
            echo "  ✓ FD 信息已保存: $output_file (共 $fd_count 个 FD)"
            echo "    FD 统计: 文件=$file_count, socket=$socket_count, pipe=$pipe_count, anon=$anon_count"
            
            # 显示最常见的文件 FD（前 10 个，排除 socket/pipe/anon）
            echo "    最常见的文件 FD (前10个):"
            local file_fds=$(echo -e "$fd_list" | grep "^/")
            if [ -n "$file_fds" ]; then
                # 统计每个路径出现的次数，显示最常见的（如果 uniq 可用）
                if command -v uniq >/dev/null 2>&1 && command -v sort >/dev/null 2>&1; then
                    # uniq -c 输出格式: "      count path"，需要处理前导空格
                    local top_paths=$(echo "$file_fds" | sort | uniq -c | sort -rn | head -10 | sed 's/^[[:space:]]*//')
                    if [ -n "$top_paths" ]; then
                        local idx=1
                        # 使用临时文件处理多行（兼容性最好）
                        local temp_fd="/data/local/tmp/fd_top_$$.tmp"
                        echo "$top_paths" > "$temp_fd" 2>/dev/null
                        if [ -f "$temp_fd" ]; then
                            while IFS= read -r line || [ -n "$line" ]; do
                                if [ -n "$line" ] && [ $idx -le 10 ]; then
                                    # 解析 "count path" 格式（count 是第一个字段）
                                    local count=$(echo "$line" | awk '{print $1}')
                                    local path=$(echo "$line" | awk '{$1=""; print $0}' | sed 's/^ //')
                                    if [ -n "$path" ] && [ "$count" -gt 0 ] 2>/dev/null; then
                                        echo "      $idx. [$count次] $path"
                                        idx=$((idx + 1))
                                    fi
                                fi
                            done < "$temp_fd"
                            rm -f "$temp_fd" 2>/dev/null
                        fi
                    else
                        echo "$file_fds" | head -10 | sed 's/^/      - /'
                    fi
                else
                    # 如果 uniq 不可用，直接显示前 10 个（去重后）
                    echo "$file_fds" | sort -u | head -10 | sed 's/^/      - /'
                fi
            else
                echo "      (无文件类型 FD)"
            fi
            
            return 0
        else
            echo "  ✗ 保存 FD 信息失败"
            return 1
        fi
    else
        echo "  ✗ 未找到 FD 信息"
        return 1
    fi
}

# 执行 heap dump
perform_dump() {
    local pkg="$1"
    local reason="$2"
    local current_pid="$3"  # 新增：当前 PID，用于 dump FD
    
    local timestamp=$(get_timestamp)
    local hprof_file="$OUTPUT_DIR/heap_${pkg}_${timestamp}.hprof"
    local fd_file=""  # FD 文件路径（如果因 FD 触发）
    
    echo ""
    echo "=========================================="
    echo "  触发 Heap Dump"
    echo "=========================================="
    echo "  包名: $pkg"
    echo "  原因: $reason"
    echo "  时间: $(get_time)"
    echo "  输出: $hprof_file"
    
    # 如果是因为 FD 触发，同时 dump FD 信息
    if echo "$reason" | grep -q "文件句柄"; then
        fd_file="$OUTPUT_DIR/fd_${pkg}_${timestamp}.txt"
        echo "  FD 信息: $fd_file"
        dump_fd_info "$current_pid" "$fd_file"
    fi
    
    echo "=========================================="
    
    # 构建 dump 命令
    local dump_cmd="am dumpheap"
    if [ "$USE_GC" = true ]; then
        dump_cmd="$dump_cmd -g"
    fi
    if [ "$USE_BITMAP" = true ]; then
        dump_cmd="$dump_cmd -b png"
    fi
    dump_cmd="$dump_cmd $pkg $hprof_file"
    
    echo "  执行: $dump_cmd"
    
    # 执行 dump
    eval "$dump_cmd"
    
    # 等待文件生成
    echo "  等待 dump 完成..."
    local wait_count=0
    while [ ! -f "$hprof_file" ] && [ $wait_count -lt 60 ]; do
        sleep 1
        wait_count=$((wait_count + 1))
    done
    
    if [ -f "$hprof_file" ]; then
        # 等待文件写入完成
        local prev_size=0
        local curr_size=1
        while [ $prev_size -ne $curr_size ]; do
            prev_size=$curr_size
            sleep 2
            curr_size=$(stat -c%s "$hprof_file" 2>/dev/null || echo "0")
        done
        
        local file_size=$((curr_size / 1024 / 1024))
        echo "  ✓ Dump 完成: ${file_size}MB"
        echo ""
        echo "  后续分析命令:"
        echo "    adb pull $hprof_file ./"
        if echo "$reason" | grep -q "文件句柄"; then
            echo "    adb pull $fd_file ./"
            echo "    # 注意: hprof 文件不包含 FD 信息，请查看 fd_*.txt 文件"
        fi
        echo "    java -jar mem-analyze-1.0.0-all.jar analyze $(basename $hprof_file)"
        echo ""
    else
        echo "  ✗ Dump 失败: 文件未生成"
    fi
    
    echo "=========================================="
    echo ""
}

# 主监控循环
main() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║      mem-analyze 设备端内存监控                              ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    echo "📱 设备: $(getprop ro.product.model)"
    echo "🎯 监控包名: $PACKAGE_LIST"
    echo ""
    echo "⚙️  配置:"
    echo "   堆内存阈值: ${HEAP_THRESHOLD}% (连续${TRIGGER_COUNT}次触发)"
    echo "   FD 阈值: ${FD_THRESHOLD} (连续${TRIGGER_COUNT}次触发)"
    echo "   线程阈值: ${THREAD_THRESHOLD} (连续${TRIGGER_COUNT}次触发)"
    echo "   堆高水位线: ${HEAP_HIGH_WATERMARK}% (立即触发)"
    echo "   堆增量阈值: ${HEAP_DELTA_THRESHOLD_MB}MB (立即触发)"
    echo "   监控间隔: ${INTERVAL}秒"
    echo "   输出目录: $OUTPUT_DIR"
    echo "   dump前GC: $USE_GC"
    echo "   包含bitmap: $USE_BITMAP"
    if [ "$MAX_ITERATIONS" -gt 0 ]; then
        echo "   最大监控次数: $MAX_ITERATIONS"
    else
        echo "   最大监控次数: 无限"
    fi
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  按 Ctrl+C 停止监控"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    # 包数量（用于多包名时按包维护状态）
    set -- $PACKAGE_LIST
    local num_packages=$#
    
    # 多包名时初始化每包状态（使用 eval 按索引存储）
    local idx=0
    for _ in $PACKAGE_LIST; do
        eval "prev_heap_used_$idx=0"
        eval "prev_heap_ratio_$idx=0"
        eval "prev_fd_count_$idx=0"
        eval "prev_thread_count_$idx=0"
        eval "heap_over_threshold_count_$idx=0"
        eval "fd_over_threshold_count_$idx=0"
        eval "thread_over_threshold_count_$idx=0"
        idx=$((idx + 1))
    done
    
    # 多包名时说明：谁先启动就监控谁，不要求全部启动（适配 monkey 随机启动）
    if [ "$num_packages" -gt 1 ]; then
        echo "📌 监控列表内谁先启动就监控谁，无需等全部应用启动"
    fi
    
    # 单包名时使用的上一次内存值（与多包名 eval 变量 prev_*_0 一致）
    local prev_heap_used=0
    local prev_heap_ratio=0
    local prev_fd_count=0
    local prev_thread_count=0
    
    while true; do
        # 检查最大迭代次数
        if [ "$MAX_ITERATIONS" -gt 0 ] && [ "$iteration" -ge "$MAX_ITERATIONS" ]; then
            echo ""
            echo "✅ 达到最大监控次数 ($MAX_ITERATIONS)，监控结束"
            break
        fi
        
        # 按包名列表依次检查（单包名时仅一个元素）
        idx=0
        for pkg in $PACKAGE_LIST; do
            # 获取 PID
            pid=$(get_pid "$pkg")
            
            if [ -z "$pid" ]; then
                echo "[$iteration] $(get_time) | ⏳ $pkg 未启动（跳过，启动后会自动监控）"
                idx=$((idx + 1))
                continue
            fi
            
            # 多包名时从该包对应状态读取
            if [ "$num_packages" -gt 1 ]; then
                eval "prev_heap_used=\$prev_heap_used_$idx"
                eval "prev_heap_ratio=\$prev_heap_ratio_$idx"
                eval "prev_fd_count=\$prev_fd_count_$idx"
                eval "prev_thread_count=\$prev_thread_count_$idx"
                eval "heap_over_threshold_count=\$heap_over_threshold_count_$idx"
                eval "fd_over_threshold_count=\$fd_over_threshold_count_$idx"
                eval "thread_over_threshold_count=\$thread_over_threshold_count_$idx"
            fi
            
            # 获取内存信息
            local mem_info=$(get_memory_info "$pkg")
            local heap_used=$(echo "$mem_info" | awk '{print $1}')
            local heap_max=$(echo "$mem_info" | awk '{print $2}')
        
        # 获取线程和FD数
        local thread_count=$(get_thread_count "$pid")
        local fd_count=$(get_fd_count "$pid")
        
        # 计算堆内存使用率
        local heap_percent=0
        if [ "$heap_max" -gt 0 ]; then
            heap_percent=$((heap_used * 100 / heap_max))
        fi
        
        # 计算增量
        local delta=""
        if [ "$prev_heap_used" -gt 0 ]; then
            local heap_delta=$((heap_used - prev_heap_used))
            if [ "$heap_delta" -gt 0 ]; then
                delta=" (+${heap_delta}KB)"
            elif [ "$heap_delta" -lt 0 ]; then
                delta=" (${heap_delta}KB)"
            fi
        fi
        # 单包名时更新用于下一轮增量的 prev（多包名在循环末尾统一写回）
        if [ "$num_packages" -le 1 ]; then
            prev_heap_used=$heap_used
        fi
        
        # 计算堆内存比率（用于浮动范围检查，使用百分比*10避免小数）
        local heap_ratio=0
        if [ "$heap_max" -gt 0 ]; then
            heap_ratio=$((heap_percent * 10))  # 百分比*10，例如 80% = 800
        fi
        
        # 显示状态
        local status_icon="📊"
        if [ "$heap_percent" -ge "$HEAP_THRESHOLD" ] || [ "$fd_count" -gt "$FD_THRESHOLD" ] || [ "$thread_count" -gt "$THREAD_THRESHOLD" ]; then
            status_icon="⚠️ "
        fi
        
            # 显示状态（多包名时带上包名）
            if [ "$num_packages" -gt 1 ]; then
                echo "[$iteration] $(get_time) | $pkg | $status_icon 堆: ${heap_percent}% (${heap_used}KB/${heap_max}KB)$delta | 线程: $thread_count | FD: $fd_count"
            else
                echo "[$iteration] $(get_time) | $status_icon 堆: ${heap_percent}% (${heap_used}KB/${heap_max}KB)$delta | 线程: $thread_count | FD: $fd_count"
            fi
        
            # 检查触发条件（参考 KOOM 逻辑）
        local should_dump=false
        local dump_reason=""
        
        # 1. 检查 Heap 高水位线（立即触发）
        if [ "$heap_percent" -ge "$HEAP_HIGH_WATERMARK" ]; then
            should_dump=true
            dump_reason="堆内存高水位线 ${HEAP_HIGH_WATERMARK}%"
        fi
        
        # 2. 检查 Heap 增量阈值（立即触发）
        if [ "$prev_heap_used" -gt 0 ]; then
            local heap_delta_kb=$((heap_used - prev_heap_used))
            local heap_delta_mb=$((heap_delta_kb / 1024))
            if [ "$heap_delta_mb" -gt "$HEAP_DELTA_THRESHOLD_MB" ]; then
                should_dump=true
                if [ -n "$dump_reason" ]; then
                    dump_reason="${dump_reason}, 堆内存增量 ${heap_delta_mb}MB > ${HEAP_DELTA_THRESHOLD_MB}MB"
                else
                    dump_reason="堆内存增量 ${heap_delta_mb}MB > ${HEAP_DELTA_THRESHOLD_MB}MB"
                fi
            fi
        fi
        
        # 3. 检查 Heap 使用率阈值（连续超过）
        if [ "$heap_percent" -ge "$HEAP_THRESHOLD" ]; then
            # 允许浮动范围（参考 KOOM: HEAP_RATIO_THRESHOLD_GAP = 0.05 = 5%）
            # heap_ratio 是百分比*10，所以 5% = 50
            if [ "$prev_heap_ratio" -gt 0 ]; then
                local heap_gap=$((prev_heap_ratio - 50))
                if [ "$heap_ratio" -ge "$heap_gap" ]; then
                    heap_over_threshold_count=$((heap_over_threshold_count + 1))
                else
                    heap_over_threshold_count=0
                fi
            else
                # 第一次检查，直接计数
                heap_over_threshold_count=$((heap_over_threshold_count + 1))
            fi
            if [ "$heap_over_threshold_count" -ge "$TRIGGER_COUNT" ]; then
                should_dump=true
                if [ -n "$dump_reason" ]; then
                    dump_reason="${dump_reason}, 堆内存连续 ${TRIGGER_COUNT} 次超过阈值 ${HEAP_THRESHOLD}%"
                else
                    dump_reason="堆内存连续 ${TRIGGER_COUNT} 次超过阈值 ${HEAP_THRESHOLD}%"
                fi
            fi
        else
            heap_over_threshold_count=0
        fi
        
        # 4. 检查 FD 阈值（连续超过，参考 KOOM）
        if [ "$fd_count" -gt "$FD_THRESHOLD" ]; then
            # 允许浮动范围：当前值 >= 上次值 - 50
            if [ "$prev_fd_count" -gt 0 ]; then
                local fd_gap=$((prev_fd_count - FD_THRESHOLD_GAP))
                if [ "$fd_count" -ge "$fd_gap" ]; then
                    fd_over_threshold_count=$((fd_over_threshold_count + 1))
                else
                    fd_over_threshold_count=0
                fi
            else
                # 第一次检查，直接计数
                fd_over_threshold_count=$((fd_over_threshold_count + 1))
            fi
            if [ "$fd_over_threshold_count" -ge "$TRIGGER_COUNT" ]; then
                should_dump=true
                if [ -n "$dump_reason" ]; then
                    dump_reason="${dump_reason}, 文件句柄连续 ${TRIGGER_COUNT} 次超过阈值 ${FD_THRESHOLD}"
                else
                    dump_reason="文件句柄连续 ${TRIGGER_COUNT} 次超过阈值 ${FD_THRESHOLD}"
                fi
            fi
        else
            fd_over_threshold_count=0
        fi
        
        # 5. 检查 Thread 阈值（连续超过，参考 KOOM）
        if [ "$thread_count" -gt "$THREAD_THRESHOLD" ]; then
            # 允许浮动范围：当前值 >= 上次值 - 50
            if [ "$prev_thread_count" -gt 0 ]; then
                local thread_gap=$((prev_thread_count - THREAD_THRESHOLD_GAP))
                if [ "$thread_count" -ge "$thread_gap" ]; then
                    thread_over_threshold_count=$((thread_over_threshold_count + 1))
                else
                    thread_over_threshold_count=0
                fi
            else
                # 第一次检查，直接计数
                thread_over_threshold_count=$((thread_over_threshold_count + 1))
            fi
            if [ "$thread_over_threshold_count" -ge "$TRIGGER_COUNT" ]; then
                should_dump=true
                if [ -n "$dump_reason" ]; then
                    dump_reason="${dump_reason}, 线程数连续 ${TRIGGER_COUNT} 次超过阈值 ${THREAD_THRESHOLD}"
                else
                    dump_reason="线程数连续 ${TRIGGER_COUNT} 次超过阈值 ${THREAD_THRESHOLD}"
                fi
            fi
        else
            thread_over_threshold_count=0
        fi
        
        # 显示超过阈值的信息
        if [ "$heap_over_threshold_count" -gt 0 ] || [ "$fd_over_threshold_count" -gt 0 ] || [ "$thread_over_threshold_count" -gt 0 ]; then
            local counts=""
            if [ "$heap_over_threshold_count" -gt 0 ]; then
                counts="堆:${heap_over_threshold_count}/${TRIGGER_COUNT}"
            fi
            if [ "$fd_over_threshold_count" -gt 0 ]; then
                if [ -n "$counts" ]; then
                    counts="${counts}, FD:${fd_over_threshold_count}/${TRIGGER_COUNT}"
                else
                    counts="FD:${fd_over_threshold_count}/${TRIGGER_COUNT}"
                fi
            fi
            if [ "$thread_over_threshold_count" -gt 0 ]; then
                if [ -n "$counts" ]; then
                    counts="${counts}, 线程:${thread_over_threshold_count}/${TRIGGER_COUNT}"
                else
                    counts="线程:${thread_over_threshold_count}/${TRIGGER_COUNT}"
                fi
            fi
            echo "    ↳ 超过阈值! (连续: $counts)"
        fi
        
            # 触发 dump（使用当前包名 pkg）
            if [ "$should_dump" = true ] && [ "$has_dumped" = false ]; then
                perform_dump "$pkg" "$dump_reason" "$pid"
                has_dumped=true
                # 重置计数器
                heap_over_threshold_count=0
                fd_over_threshold_count=0
                thread_over_threshold_count=0
            fi
            
            # 更新上一次的值（多包名时写回该包对应状态）
            if [ "$num_packages" -gt 1 ]; then
                eval "prev_heap_used_$idx=$heap_used"
                eval "prev_heap_ratio_$idx=$heap_ratio"
                eval "prev_fd_count_$idx=$fd_count"
                eval "prev_thread_count_$idx=$thread_count"
                eval "heap_over_threshold_count_$idx=$heap_over_threshold_count"
                eval "fd_over_threshold_count_$idx=$fd_over_threshold_count"
                eval "thread_over_threshold_count_$idx=$thread_over_threshold_count"
            else
                prev_heap_used=$heap_used
                prev_heap_ratio=$heap_ratio
                prev_fd_count=$fd_count
                prev_thread_count=$thread_count
            fi
            
            idx=$((idx + 1))
        done
        
        iteration=$((iteration + 1))
        sleep "$INTERVAL"
    done
}

# 捕获 Ctrl+C
trap 'echo ""; echo ""; echo "✅ 监控已停止"; echo "   hprof 文件位置: $OUTPUT_DIR"; exit 0' INT TERM

# 启动主循环
main
