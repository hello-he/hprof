#!/bin/bash
# mem-analyze hprof 分析脚本
# 此脚本会自动检测jar文件位置

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# jar文件路径（必须在脚本同一目录）
JAR_PATH="$SCRIPT_DIR/mem-analyze-1.0.0-all.jar"

# 检查jar文件是否存在
if [ ! -f "$JAR_PATH" ]; then
    zenity --error --text="错误：找不到 mem-analyze jar 文件\n\n请确保 jar 文件位于：\n$JAR_PATH" --title="内存泄露分析" 2>/dev/null || \
    echo "错误：找不到 mem-analyze jar 文件: $JAR_PATH"
    exit 1
fi

BASE_REPORT_DIR="$(dirname "$1")"

# 检查 hprof 文件是否存在
if [ ! -f "$1" ]; then
    zenity --error --text="错误：找不到 hprof 文件\n\n路径: $1" --title="内存泄露分析" 2>/dev/null || \
    echo "错误：找不到 hprof 文件: $1"
    exit 1
fi

# 显示进度对话框
zenity --info --text="正在分析内存泄露...\n\n文件: $(basename "$1")\n\n请稍候..." --title="内存泄露分析" --timeout=2 2>/dev/null &

# 执行分析（analyze 命令会在 -o 目录下创建子目录）
# 注意：analyze 命令使用 -f 参数指定 hprof 文件
java -jar "$JAR_PATH" analyze -f "$1" -o "$BASE_REPORT_DIR" 2>&1 | tee /tmp/mem-analyze.log
ANALYZE_EXIT_CODE=$?

# 检查结果
if [ $ANALYZE_EXIT_CODE -eq 0 ]; then
    # analyze 命令会在 BASE_REPORT_DIR 下创建子目录（格式：hprof文件名_yyyyMMdd_HHmmss）
    # 获取hprof文件名（不含扩展名）用于匹配报告目录
    HPROF_BASENAME=$(basename "$1" .hprof)
    
    # 基于hprof文件名匹配报告目录，然后按时间戳排序找到最新的
    REPORT_SUBDIR=$(find "$BASE_REPORT_DIR" -maxdepth 1 -type d -name "${HPROF_BASENAME}_*" 2>/dev/null | sort -r | head -1)
    
    if [ -z "$REPORT_SUBDIR" ]; then
        # 如果没有找到匹配的目录，尝试查找所有带下划线的目录（兼容旧格式）
        REPORT_SUBDIR=$(find "$BASE_REPORT_DIR" -maxdepth 1 -type d -name "*_*" 2>/dev/null | sort -r | head -1)
    fi
    
    if [ -z "$REPORT_SUBDIR" ]; then
        # 如果还是没有找到，可能报告直接在 BASE_REPORT_DIR 下
        REPORT_SUBDIR="$BASE_REPORT_DIR"
    fi
    
    # 在最新报告目录中查找 HTML 报告（优先查找 hprof_analysis.html）
    HTML_REPORT="$REPORT_SUBDIR/hprof_analysis.html"
    if [ ! -f "$HTML_REPORT" ]; then
        # 如果没有找到 hprof_analysis.html，查找其他 HTML 文件
        HTML_REPORT=$(find "$REPORT_SUBDIR" -maxdepth 1 -name "*.html" -type f 2>/dev/null | head -1)
    fi
    
    if [ -n "$HTML_REPORT" ] && [ -f "$HTML_REPORT" ]; then
        # 显示成功提示，然后直接打开HTML报告
        zenity --info --text="分析完成！\n\nHTML报告已生成\n\n正在打开报告..." --title="内存泄露分析" --timeout=2 2>/dev/null &
        # 立即打开HTML报告
        sleep 0.3
        xdg-open "$HTML_REPORT" 2>/dev/null || \
        gnome-open "$HTML_REPORT" 2>/dev/null || \
        kde-open "$HTML_REPORT" 2>/dev/null || \
        echo "请手动打开: $HTML_REPORT"
    else
        # 列出所有生成的文件
        ALL_FILES=$(find "$REPORT_SUBDIR" -type f 2>/dev/null | head -5)
        if [ -n "$ALL_FILES" ]; then
            zenity --info --text="分析完成！\n\n报告目录: $REPORT_SUBDIR\n\n生成的文件:\n$ALL_FILES" --title="内存泄露分析" 2>/dev/null || \
            echo "分析完成！报告目录: $REPORT_SUBDIR"
            echo "生成的文件:"
            echo "$ALL_FILES"
        else
            zenity --info --text="分析完成！\n\n报告目录: $REPORT_SUBDIR\n\n（未找到报告文件，请检查日志）" --title="内存泄露分析" 2>/dev/null || \
            echo "分析完成！报告目录: $REPORT_SUBDIR"
        fi
    fi
else
    zenity --error --text="分析失败！\n\n请查看日志: /tmp/mem-analyze.log" --title="内存泄露分析" 2>/dev/null || \
    echo "分析失败！请查看日志: /tmp/mem-analyze.log"
    cat /tmp/mem-analyze.log | tail -20
fi
