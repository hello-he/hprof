#!/bin/bash
# 安装右键菜单快捷方式

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANALYZE_SCRIPT="$SCRIPT_DIR/mem-monitor-analyze.sh"
JAR_FILE="$SCRIPT_DIR/mem-monitor-1.0.0-all.jar"

# 检查必要文件是否存在
if [ ! -f "$ANALYZE_SCRIPT" ]; then
    echo "错误：找不到分析脚本: $ANALYZE_SCRIPT"
    exit 1
fi

# 检查jar文件是否存在（必须在脚本目录）
if [ ! -f "$JAR_FILE" ]; then
    echo "错误：找不到jar文件: $JAR_FILE"
    echo ""
    echo "请确保jar文件位于此目录中。"
    echo "如果这是从源码构建的，请运行打包脚本："
    echo "  cd $(dirname "$SCRIPT_DIR") && ./scripts/package-right-click-menu.sh"
    exit 1
fi

# 创建必要的目录
mkdir -p ~/.local/bin
mkdir -p ~/.local/share/applications
mkdir -p ~/.local/share/mime/packages

# 复制脚本和jar文件到 ~/.local/bin
echo "安装分析脚本..."
cp "$ANALYZE_SCRIPT" ~/.local/bin/mem-monitor-analyze.sh
chmod +x ~/.local/bin/mem-monitor-analyze.sh

echo "安装jar文件..."
cp "$JAR_FILE" ~/.local/bin/mem-monitor-1.0.0-all.jar

# 创建桌面文件
echo "创建桌面快捷方式..."
cat > ~/.local/share/applications/mem-monitor-analyze.desktop <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=分析内存泄露
Name[en]=Analyze Memory Leak
Comment=使用 mem-monitor 分析 hprof 文件
Comment[en]=Analyze hprof file with mem-monitor
Exec=$HOME/.local/bin/mem-monitor-analyze.sh %f
Icon=utilities-system-monitor
Terminal=false
Categories=Development;System;
MimeType=application/x-hprof;application/octet-stream;
EOF

# 创建MIME类型定义
echo "注册MIME类型..."
cat > ~/.local/share/mime/packages/hprof.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
    <mime-type type="application/x-hprof">
        <comment>Android Heap Dump</comment>
        <comment xml:lang="en">Android Heap Dump</comment>
        <glob pattern="*.hprof"/>
    </mime-type>
</mime-info>
EOF

# 更新数据库
echo "更新桌面数据库..."
update-desktop-database ~/.local/share/applications 2>/dev/null || true

echo "更新MIME数据库..."
update-mime-database ~/.local/share/mime 2>/dev/null || true

echo ""
echo "✅ 安装完成！"
echo ""
echo "使用方法："
echo "1. 在文件管理器中找到 .hprof 文件"
echo "2. 右键点击文件"
echo "3. 选择 '打开方式' -> '分析内存泄露'"
echo ""
echo "如果右键菜单中没有出现，请："
echo "1. 重启文件管理器（或注销重新登录）"
echo "2. 或者运行以下命令："
echo "   update-desktop-database ~/.local/share/applications"
echo "   update-mime-database ~/.local/share/mime"
echo ""
