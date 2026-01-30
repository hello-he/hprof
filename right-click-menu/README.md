# 右键菜单快捷方式安装

此目录是一个**独立的发布包**，包含在Linux桌面环境中添加右键菜单快捷方式分析hprof文件所需的所有文件。

## 支持的泄露检测

本工具可以检测以下类型的内存泄露问题：

- **Activity 泄露** - Activity 已销毁但仍被引用
- **Fragment 泄露** - Fragment 生命周期已结束但仍被引用
- **Service 泄露** - Service 未被 ActivityThread 持有但仍可达
- **BroadcastReceiver 泄露** - BroadcastReceiver 的 mContext 持有已销毁的 Activity 引用，或 BroadcastReceiver 是 Activity 的非静态内部类（隐式持有 Activity 引用）
- **Animator 泄露** - 无限循环动画持有引用
- **大 Bitmap 泄露** - 超过 1M 像素的 Bitmap 占用大量内存
- **大 ByteArray 泄露** - 超过 1MB 的字节数组占用大量内存
- **线程泄露** - 重复的线程名（同名线程超过 5 个）

## 快速开始

### 1. 运行安装脚本

```bash
cd right-click-menu
chmod +x install.sh
./install.sh
```

安装脚本会自动完成以下操作：
- 安装分析脚本到系统
- 创建桌面快捷方式
- 注册文件类型关联
- 更新系统数据库

### 2. 使用右键菜单

安装完成后：

1. 在文件管理器中找到 `.hprof` 文件
2. 右键点击文件
3. 选择 **"打开方式"** → **"分析内存泄露"**

分析完成后会自动打开HTML报告。

## 报告位置

分析报告会生成在hprof文件所在目录，格式为：
```
hprof文件所在目录/hprof文件名_时间戳/
```

例如：
- 文件：`/home/user/heap.hprof`
- 报告：`/home/user/heap_20260126_163045/hprof_analysis.html`

## 卸载

删除以下文件即可卸载：

```bash
rm ~/.local/bin/mem-monitor-analyze.sh
rm ~/.local/bin/mem-monitor-1.0.0-all.jar
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

确保此目录包含 `mem-monitor-1.0.0-all.jar` 文件。如果文件缺失，请联系发布者获取完整版本。

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

## 功能特性

- ✅ 独立发布包，包含所有必需文件
- ✅ 显示分析进度对话框
- ✅ 自动生成报告到hprof文件所在目录
- ✅ 分析完成后自动打开HTML报告
- ✅ 错误提示和日志记录
