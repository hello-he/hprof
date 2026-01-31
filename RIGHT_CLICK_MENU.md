# 右键菜单快捷方式安装说明

## 已安装的文件

1. **脚本文件**: `~/.local/bin/mem-analyze.sh`
   - 执行实际的分析工作
   - 自动查找 jar 文件并执行分析

2. **桌面文件**: `~/.local/share/applications/mem-analyze.desktop`
   - 定义右键菜单项
   - 关联 .hprof 文件类型

3. **MIME 类型**: `~/.local/share/mime/packages/hprof.xml`
   - 注册 .hprof 文件的 MIME 类型
   - 确保文件管理器能识别文件类型

## 使用方法

### 方法 1: 右键菜单（推荐）

1. 在文件管理器中找到 `.hprof` 文件
2. 右键点击文件
3. 选择 **"打开方式"** → **"分析内存泄露"**

### 方法 2: 命令行

```bash
~/.local/bin/mem-analyze.sh /path/to/file.hprof
```

## 功能特性

- ✅ 自动检测 jar 文件位置
- ✅ 显示分析进度对话框
- ✅ 自动生成报告到 `hprof文件所在目录/mem-analyze-reports/`
- ✅ 分析完成后自动提示是否打开 HTML 报告
- ✅ 错误提示和日志记录

## 报告位置

分析报告会生成在：
```
hprof文件所在目录/mem-analyze-reports/
```

例如：
- 文件：`/home/user/heap.hprof`
- 报告：`/home/user/mem-analyze-reports/`

## 故障排除

### 右键菜单中没有出现

1. 更新桌面数据库：
   ```bash
   update-desktop-database ~/.local/share/applications
   ```

2. 更新 MIME 数据库：
   ```bash
   update-mime-database ~/.local/share/mime
   ```

3. 重启文件管理器：
   - GNOME: `killall nautilus` 或重启系统
   - KDE: `killall dolphin` 或重启系统
   - XFCE: `killall thunar` 或重启系统

### 脚本找不到 jar 文件

检查 jar 文件是否存在：
```bash
ls -lh ~/workspaces/koom/mem-analyze/build/libs/mem-analyze-1.0.0-all.jar
```

如果 jar 文件位置改变了，需要更新脚本中的路径：
```bash
nano ~/.local/bin/mem-analyze.sh
```

修改 `JAR_PATH` 变量为正确的路径。

### 没有图形界面（zenity）

如果系统没有安装 `zenity`，脚本会回退到命令行输出。安装 zenity：
```bash
# Ubuntu/Debian
sudo apt-get install zenity

# Fedora
sudo dnf install zenity

# Arch Linux
sudo pacman -S zenity
```

## 卸载

删除以下文件即可卸载：

```bash
rm ~/.local/bin/mem-analyze.sh
rm ~/.local/share/applications/mem-analyze.desktop
rm ~/.local/share/mime/packages/hprof.xml
update-desktop-database ~/.local/share/applications
update-mime-database ~/.local/share/mime
```

## 更新

如果 jar 文件位置改变了，只需要更新脚本中的路径：

```bash
nano ~/.local/bin/mem-analyze.sh
```

修改 `JAR_PATH` 变量即可。
