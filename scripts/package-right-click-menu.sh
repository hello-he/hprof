#!/bin/bash
# 打包右键菜单发布包
# 将jar文件复制到right-click-menu目录，创建可发布的独立包

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RIGHT_CLICK_DIR="$PROJECT_ROOT/right-click-menu"
BUILD_JAR="$PROJECT_ROOT/build/libs/mem-analyze-1.0.0-all.jar"
TARGET_JAR="$RIGHT_CLICK_DIR/mem-analyze-1.0.0-all.jar"

echo "📦 打包右键菜单发布包..."
echo ""

# 检查build目录的jar文件是否存在
if [ ! -f "$BUILD_JAR" ]; then
    echo "❌ 错误：找不到jar文件: $BUILD_JAR"
    echo ""
    echo "请先构建项目："
    echo "  cd $PROJECT_ROOT && ./gradlew shadowJar"
    exit 1
fi

# 检查right-click-menu目录是否存在
if [ ! -d "$RIGHT_CLICK_DIR" ]; then
    echo "❌ 错误：找不到right-click-menu目录: $RIGHT_CLICK_DIR"
    exit 1
fi

# 复制jar文件
echo "📋 复制jar文件..."
cp "$BUILD_JAR" "$TARGET_JAR"
echo "   ✅ $BUILD_JAR -> $TARGET_JAR"

# 显示文件信息
echo ""
echo "📊 发布包内容："
ls -lh "$RIGHT_CLICK_DIR" | grep -E "^-|^d" | awk '{print "   " $9 " (" $5 ")"}'

# 计算总大小
TOTAL_SIZE=$(du -sh "$RIGHT_CLICK_DIR" | cut -f1)
echo ""
echo "📦 总大小: $TOTAL_SIZE"
echo ""
echo "✅ 打包完成！"
echo ""
echo "发布包位置: $RIGHT_CLICK_DIR"
echo ""
echo "用户只需要这个目录即可使用："
echo "  1. 将 right-click-menu 目录分发给用户"
echo "  2. 用户运行: cd right-click-menu && ./install.sh"
echo ""
