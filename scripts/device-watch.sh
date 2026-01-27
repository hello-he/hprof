#!/system/bin/sh
# ============================================================================
# mem-monitor 设备端内存监控脚本
# 
# 功能：在 Android 设备上直接运行，监控应用内存并自动 dump hprof
# 
# 使用方法：
#   1. 将此脚本推送到设备：adb push device-watch.sh /data/local/tmp/
#   2. 在设备上执行：adb shell sh /data/local/tmp/device-watch.sh -p <包名>
#   3. 或者进入 shell 后执行：sh /data/local/tmp/device-watch.sh -p <包名>
#
# 参数：
#   -p <包名>       必填，要监控的应用包名
#   -t <阈值>       堆内存使用率阈值，默认 80 (%)
#   -i <间隔>       监控间隔秒数，默认 10
#   -n <次数>       连续超过阈值触发dump的次数，默认 3
#   -o <输出目录>   hprof 输出目录，默认 /data/local/tmp/mem-monitor
#   -m <最大次数>   最大监控次数，0 表示无限，默认 0
#   -g              dump 前执行 GC
#   -b              包含 bitmap 数据 (Android 14+)
#   -h              显示帮助
#
# 示例：
#   sh /data/local/tmp/device-watch.sh -p com.example.app -t 70 -i 5
#   sh /data/local/tmp/device-watch.sh -p com.example.app -g -b
#
# 输出：
#   hprof 文件保存在 /data/local/tmp/mem-monitor/ 目录下
#   文件名格式：heap_<包名>_<时间戳>.hprof
#
# 后续分析：
#   adb pull /data/local/tmp/mem-monitor/ ./
#   java -jar mem-monitor-1.0.0-all.jar analyze <hprof文件>
# ============================================================================

# 默认配置
PACKAGE=""
HEAP_THRESHOLD=80
INTERVAL=10
TRIGGER_COUNT=3
OUTPUT_DIR="/data/local/tmp/mem-monitor"
MAX_ITERATIONS=0
USE_GC=false
USE_BITMAP=false

# 状态变量
over_threshold_count=0
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
    echo "  mem-monitor 设备端内存监控"
    echo "=============================================="
    echo ""
    echo "用法: sh $0 -p <包名> [选项]"
    echo ""
    echo "必填参数:"
    echo "  -p <包名>       要监控的应用包名"
    echo ""
    echo "可选参数:"
    echo "  -t <阈值>       堆内存使用率阈值 (%)，默认: 80"
    echo "  -i <间隔>       监控间隔 (秒)，默认: 10"
    echo "  -n <次数>       连续超过阈值触发dump次数，默认: 3"
    echo "  -o <目录>       输出目录，默认: /data/local/tmp/mem-monitor"
    echo "  -m <次数>       最大监控次数，0=无限，默认: 0"
    echo "  -g              dump 前执行 GC"
    echo "  -b              包含 bitmap 数据 (Android 14+)"
    echo "  -h              显示此帮助"
    echo ""
    echo "示例:"
    echo "  sh $0 -p com.example.app"
    echo "  sh $0 -p com.example.app -t 70 -i 5 -g"
    echo "  sh $0 -p com.example.app -g -b -n 2"
    echo ""
    echo "输出文件位置: /data/local/tmp/mem-monitor/"
    echo ""
    exit 0
}

# 解析参数
while getopts "p:t:i:n:o:m:gbh" opt; do
    case $opt in
        p) PACKAGE="$OPTARG" ;;
        t) HEAP_THRESHOLD="$OPTARG" ;;
        i) INTERVAL="$OPTARG" ;;
        n) TRIGGER_COUNT="$OPTARG" ;;
        o) OUTPUT_DIR="$OPTARG" ;;
        m) MAX_ITERATIONS="$OPTARG" ;;
        g) USE_GC=true ;;
        b) USE_BITMAP=true ;;
        h) show_help ;;
        *) echo "未知参数: -$opt"; show_help ;;
    esac
done

# 检查必填参数
if [ -z "$PACKAGE" ]; then
    echo "错误: 必须指定包名 (-p)"
    echo ""
    show_help
fi

# 创建输出目录
mkdir -p "$OUTPUT_DIR"

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

# 执行 heap dump
perform_dump() {
    local pkg="$1"
    local reason="$2"
    
    local timestamp=$(get_timestamp)
    local hprof_file="$OUTPUT_DIR/heap_${pkg}_${timestamp}.hprof"
    
    echo ""
    echo "=========================================="
    echo "  触发 Heap Dump"
    echo "=========================================="
    echo "  包名: $pkg"
    echo "  原因: $reason"
    echo "  时间: $(get_time)"
    echo "  输出: $hprof_file"
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
        echo "    java -jar mem-monitor-1.0.0-all.jar analyze $(basename $hprof_file)"
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
    echo "║      mem-monitor 设备端内存监控                              ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    echo "📱 设备: $(getprop ro.product.model)"
    echo "🎯 监控包名: $PACKAGE"
    echo ""
    echo "⚙️  配置:"
    echo "   堆内存阈值: ${HEAP_THRESHOLD}%"
    echo "   监控间隔: ${INTERVAL}秒"
    echo "   触发次数: 连续${TRIGGER_COUNT}次超过阈值"
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
    
    # 检查应用是否运行
    local pid=$(get_pid "$PACKAGE")
    if [ -z "$pid" ]; then
        echo "⚠️  警告: 应用 $PACKAGE 未运行"
        echo "   等待应用启动..."
    fi
    
    # 上一次的内存值（用于计算增量）
    local prev_heap_used=0
    
    while true; do
        # 检查最大迭代次数
        if [ "$MAX_ITERATIONS" -gt 0 ] && [ "$iteration" -ge "$MAX_ITERATIONS" ]; then
            echo ""
            echo "✅ 达到最大监控次数 ($MAX_ITERATIONS)，监控结束"
            break
        fi
        
        # 获取 PID
        pid=$(get_pid "$PACKAGE")
        
        if [ -z "$pid" ]; then
            echo "[$(get_time)] ⏳ 等待应用启动: $PACKAGE"
            sleep "$INTERVAL"
            continue
        fi
        
        # 获取内存信息
        local mem_info=$(get_memory_info "$PACKAGE")
        local heap_used=$(echo "$mem_info" | awk '{print $1}')
        local heap_max=$(echo "$mem_info" | awk '{print $2}')
        local total_pss=$(echo "$mem_info" | awk '{print $3}')
        
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
        prev_heap_used=$heap_used
        
        # 显示状态
        local status_icon="📊"
        if [ "$heap_percent" -ge "$HEAP_THRESHOLD" ]; then
            status_icon="⚠️ "
        fi
        
        echo "[$iteration] $(get_time) | $status_icon 堆: ${heap_percent}% (${heap_used}KB/${heap_max}KB)$delta | 线程: $thread_count | FD: $fd_count"
        
        # 检查是否超过阈值
        if [ "$heap_percent" -ge "$HEAP_THRESHOLD" ]; then
            over_threshold_count=$((over_threshold_count + 1))
            echo "    ↳ 超过阈值! (连续 ${over_threshold_count}/${TRIGGER_COUNT} 次)"
            
            # 检查是否需要触发 dump
            if [ "$over_threshold_count" -ge "$TRIGGER_COUNT" ] && [ "$has_dumped" = false ]; then
                perform_dump "$PACKAGE" "堆内存连续 ${TRIGGER_COUNT} 次超过阈值 ${HEAP_THRESHOLD}%"
                has_dumped=true
                over_threshold_count=0
            fi
        else
            # 重置计数器
            if [ "$over_threshold_count" -gt 0 ]; then
                echo "    ↳ 恢复正常，重置计数器"
            fi
            over_threshold_count=0
        fi
        
        iteration=$((iteration + 1))
        sleep "$INTERVAL"
    done
}

# 捕获 Ctrl+C
trap 'echo ""; echo ""; echo "✅ 监控已停止"; echo "   hprof 文件位置: $OUTPUT_DIR"; exit 0' INT TERM

# 启动主循环
main
