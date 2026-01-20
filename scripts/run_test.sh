#!/bin/bash
# 综合测试脚本 - 同时运行Monkey和Watch监控

set -e

PACKAGE_NAME="com.koom.leak"
MONKEY_EVENTS=3000
HEAP_THRESHOLD=80

echo "=========================================="
echo "   Bitmap泄露综合测试"
echo "=========================================="
echo ""

# 检查JAR
JAR_FILE="/home/dk/workspaces/koom/mem-monitor/build/libs/mem-monitor-1.0.0-all.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "错误: JAR文件不存在，正在构建..."
    cd /home/dk/workspaces/koom/mem-monitor
    ./gradlew shadowJar
fi

# 检查APK
APK_FILE="/home/dk/workspaces/koom/mem-monitor/demo/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_FILE" ]; then
    echo "错误: APK文件不存在，正在构建..."
    cd /home/dk/workspaces/koom/mem-monitor/demo
    ./gradlew assembleDebug
fi

# 检查设备
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "错误: 没有检测到Android设备"
    exit 1
fi
echo "设备已连接: $DEVICES 台"

# 安装应用
echo "正在安装/更新应用..."
adb install -r "$APK_FILE" 2>/dev/null || true

# 启动应用
echo "启动应用..."
adb shell am start -n "$PACKAGE_NAME/.MainActivity"
sleep 2

echo ""
echo "=========================================="
echo "   测试开始"
echo "=========================================="
echo ""
echo "终端1: 运行监控 (Ctrl+C 停止)"
echo "终端2: 运行Monkey测试 (Ctrl+C 停止)"
echo ""

# 创建监控脚本在后台运行
cat > /tmp/watch_run.sh << 'EOF'
#!/bin/bash
java -jar /home/dk/workspaces/koom/mem-monitor/build/libs/mem-monitor-1.0.0-all.jar watch \
    -p com.koom.leak \
    --heap-threshold 80 \
    --thread-threshold 200 \
    --fd-threshold 300 \
    -o ./reports
EOF
chmod +x /tmp/watch_run.sh

# 创建monkey脚本
cat > /tmp/monkey_run.sh << 'EOF'
#!/bin/bash
adb shell monkey -p com.koom.leak \
    --throttle 200 \
    --pct-touch 60 \
    --pct-motion 20 \
    --pct-nav 15 \
    --pct-appswitch 5 \
    -v -1
EOF
chmod +x /tmp/monkey_run.sh

echo "请在两个终端中分别运行:"
echo ""
echo "终端1 (监控):"
echo "  bash /tmp/watch_run.sh"
echo ""
echo "终端2 (Monkey):"
echo "  bash /tmp/monkey_run.sh"
echo ""
echo "或者使用 tmux/screen 同时运行两个脚本"
echo ""

# 询问是否自动启动
read -p "是否自动启动(需要tmux)? [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    if command -v tmux &> /dev/null; then
        SESSION_NAME="leak_test"

        # 创建tmux会话
        tmux new-session -d -s "$SESSION_NAME" -n watch
        tmux send-keys -t "$SESSION_NAME" "bash /tmp/watch_run.sh" C-m

        tmux new-window -t "$SESSION_NAME" -n monkey
        tmux send-keys -t "$SESSION_NAME:1" "bash /tmp/monkey_run.sh" C-m

        tmux attach-session -t "$SESSION_NAME"
    else
        echo "错误: tmux 未安装"
        echo "请手动在两个终端运行上述脚本"
    fi
fi
