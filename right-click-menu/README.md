# 右键菜单快捷方式安装

此目录是一个**独立的发布包**，包含在Linux桌面环境中添加右键菜单快捷方式分析hprof文件所需的所有文件。

## 文件说明

- `mem-monitor-analyze.sh` - 分析脚本，用于执行hprof文件分析
- `install.sh` - 安装脚本，用于安装右键菜单快捷方式
- `mem-monitor-1.0.0-all.jar` - 可执行jar文件（**必须包含在发布包中**）
- `README.md` - 本说明文件

## 用户安装步骤

### 1. 确保jar文件存在

此目录应该已经包含 `mem-monitor-1.0.0-all.jar` 文件。如果不存在，请联系发布者。

### 2. 运行安装脚本

```bash
cd right-click-menu
chmod +x install.sh
./install.sh
```

安装脚本会：
- 将分析脚本复制到 `~/.local/bin/`
- 创建桌面快捷方式文件
- 注册MIME类型
- 更新桌面和MIME数据库

## 开发者打包步骤

如果你是开发者，需要创建发布包：

```bash
# 1. 构建项目
cd /path/to/mem-monitor
./gradlew shadowJar

# 2. 打包右键菜单发布包
./scripts/package-right-click-menu.sh
```

打包脚本会将jar文件复制到 `right-click-menu/` 目录，创建完整的独立发布包。

安装脚本会：
- 将分析脚本复制到 `~/.local/bin/`
- 创建桌面快捷方式文件
- 注册MIME类型
- 更新桌面和MIME数据库

## 使用方法

安装完成后：

1. 在文件管理器中找到 `.hprof` 文件
2. 右键点击文件
3. 选择 **"打开方式"** → **"分析内存泄露"**

分析完成后会自动打开HTML报告。

## 卸载

删除以下文件即可卸载：

```bash
rm ~/.local/bin/mem-monitor-analyze.sh
rm ~/.local/share/applications/mem-monitor-analyze.desktop
rm ~/.local/share/mime/packages/hprof.xml
update-desktop-database ~/.local/share/applications
update-mime-database ~/.local/share/mime
```

## 故障排除

### 右键菜单中没有出现

1. 更新桌面数据库：
   ```bash
   update-desktop-database ~/.local/share/applications
   ```

2. 更新MIME数据库：
   ```bash
   update-mime-database ~/.local/share/mime
   ```

3. 重启文件管理器：
   - GNOME: `killall nautilus` 或重启系统
   - KDE: `killall dolphin` 或重启系统
   - XFCE: `killall thunar` 或重启系统

### 找不到jar文件

jar文件必须位于 `right-click-menu/mem-monitor-1.0.0-all.jar`。

如果这是从源码获取的，请运行打包脚本：
```bash
cd /path/to/mem-monitor
./scripts/package-right-click-menu.sh
```

如果这是发布包，请联系发布者获取包含jar文件的完整版本。

### 没有图形界面（zenity）

如果系统没有安装 `zenity`，脚本会回退到命令行输出。安装zenity：

```bash
# Ubuntu/Debian
sudo apt-get install zenity

# Fedora
sudo dnf install zenity

# Arch Linux
sudo pacman -S zenity
```

## 报告位置

分析报告会生成在hprof文件所在目录，格式为：
```
hprof文件所在目录/hprof文件名_时间戳/
```

例如：
- 文件：`/home/user/heap.hprof`
- 报告：`/home/user/heap_20260126_163045/hprof_analysis.html`

## 功能特性

- ✅ 独立发布包，包含所有必需文件
- ✅ 显示分析进度对话框
- ✅ 自动生成报告到hprof文件所在目录
- ✅ 分析完成后自动打开HTML报告
- ✅ 错误提示和日志记录

## 发布说明

此目录是一个**完整的独立发布包**，用户只需要这个目录即可使用：

1. 将整个 `right-click-menu` 目录分发给用户
2. 用户运行 `./install.sh` 即可安装
3. 无需其他依赖，jar文件已包含在目录中
