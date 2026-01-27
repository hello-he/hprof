# Android Memory Monitor

Android内存监控工具，通过ADB shell监控Android应用的内存、线程、文件句柄，并支持自动dump和Bitmap提取分析。

> 📖 **新手必读：** 想了解什么是内存泄露？查看 [内存泄露详解 (MEMORY_LEAK.md)](MEMORY_LEAK.md)

## 功能特性

### 1. 离线Hprof分析 (`analyze`)
- 分析hprof文件，检测内存泄漏
- 自动提取Bitmap图片（支持Android 14+ `am dumpheap -g -b png`）
- 生成HTML/文本分析报告
- 检测重复Bitmap，计算内存浪费

### 2. 实时监控 (`watch`)
- 监控应用堆内存使用
- 监控线程数量
- 监控文件句柄数量
- 超阈值自动dump堆内存
- 自动截屏保存现场

### 3. 单次扫描 (`scan`)
- 快速扫描多个应用
- 显示当前内存状态
- 识别潜在风险

### 4. Bitmap提取
- 支持Android 14+ dumpData压缩数据
- 自动检测重复Bitmap
- 生成可视化HTML报告

## 项目结构

```
mem-monitor/
├── src/main/java/com/koom/monitor/
│   ├── Main.kt                      # CLI入口
│   ├── adb/
│   │   └── AdbClient.kt           # ADB通信封装，支持dumpheap
│   ├── analyzer/
│   │   ├── BitmapExtractor.kt     # Bitmap提取、重复检测、报告生成
│   │   ├── HprofAnalyzer.kt      # Hprof文件分析、泄漏检测
│   │   └── HprofRawReader.kt      # Hprof原始数据读取
│   ├── command/
│   │   ├── AnalyzeCommand.kt     # analyze命令实现
│   │   ├── ScanCommand.kt        # scan命令实现
│   │   └── WatchCommand.kt       # watch命令实现
│   └── model/
│       ├── MonitorConfig.kt      # 监控配置模型
│       └── MetricsSnapshot.kt    # 内存/线程/FD快照
├── demo/                          # Bitmap泄露测试APK（独立项目）
│   └── app/src/main/java/com/koom/leak/
│       └── MainActivity.java      # 测试Activity，创建Bitmap泄露
├── scripts/                       # 测试脚本
│   ├── monkey_test.sh             # Monkey测试脚本
│   ├── watch_test.sh              # Watch监控脚本
│   └── run_test.sh                # 综合测试脚本
├── right-click-menu/              # 右键菜单快捷方式
│   ├── mem-monitor-analyze.sh     # 分析脚本
│   ├── install.sh                 # 安装脚本
│   └── README.md                  # 安装说明
├── build/libs/
│   └── mem-monitor-1.0.0-all.jar # 可执行JAR
└── README.md
```

## 快速开始

### 1. 构建工具

```bash
cd /path/to/mem-monitor
./gradlew shadowJar
```

输出: `build/libs/mem-monitor-1.0.0-all.jar`

### 2. 离线分析Hprof文件

```bash
# 基本分析
java -jar build/libs/mem-monitor-1.0.0-all.jar analyze -f heap.hprof

# 自动检测并提取Bitmap（如果hprof包含dumpheap -b的bitmap数据）
java -jar build/libs/mem-monitor-1.0.0-all.jar analyze -f heap.hprof

# 只提取大Bitmap(>1M像素)
java -jar build/libs/mem-monitor-1.0.0-all.jar analyze -f heap.hprof --large-only

# 指定输出目录
java -jar build/libs/mem-monitor-1.0.0-all.jar analyze -f heap.hprof -o ./reports
```

### 3. 安装右键菜单快捷方式（可选）

在Linux桌面环境中，可以安装右键菜单快捷方式，方便直接分析hprof文件：

```bash
cd right-click-menu
chmod +x install.sh
./install.sh
```

安装后，在文件管理器中右键点击`.hprof`文件，选择"打开方式" → "分析内存泄露"即可。

详细说明请查看 [right-click-menu/README.md](right-click-menu/README.md)

### 3. 实时监控应用

```bash
# 监控单个应用
java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p com.example.app

# 设置阈值并监控
java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p com.example.app \
  --heap-threshold 512 \
  --thread-threshold 300 \
  --fd-threshold 500

# 指定ADB路径
java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p com.example.app --adb /path/to/adb
```

### 4. 单次扫描

```bash
# 扫描多个应用
java -jar build/libs/mem-monitor-1.0.0-all.jar scan -p com.example.app,com.another.app

# 扫描并设置阈值
java -jar build/libs/mem-monitor-1.0.0-all.jar scan -p com.example.app \
  --heap-threshold 400 --thread-threshold 200
```

## 命令行参数

### analyze 命令

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-f, --hprof <file>` | hprof文件路径 | 必填 |
| `-o, --output <dir>` | 输出目录 | ./reports |
| `--large-only` | 只提取大Bitmap(>1M像素) | false |

### watch 命令

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-p, --package <name>` | 应用包名 | 必填 |
| `--heap-threshold <MB>` | 堆内存阈值(MB) | 512 |
| `--thread-threshold <count>` | 线程数阈值 | 300 |
| `--fd-threshold <count>` | 文件句柄阈值 | 500 |
| `--adb <path>` | ADB路径 | 自动查找 |
| `-o, --output <dir>` | 输出目录 | ./reports |
| `--interval <seconds>` | 监控间隔(秒) | 5 |

### scan 命令

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-p, --package <names>` | 应用包名(逗号分隔) | 必填 |
| `--heap-threshold <MB>` | 堆内存阈值 | 512 |
| `--thread-threshold <count>` | 线程数阈值 | 300 |
| `--fd-threshold <count>` | 文件句柄阈值 | 500 |

## 输出报告

### 目录结构

输出按hprof文件名自动分目录，避免多个hprof分析结果混乱：

```
reports/
├── test.hprof/                   # 以hprof文件名创建子目录
│   ├── hprof_analysis_20260120_210846.html
│   ├── hprof_analysis_20260120_210846.txt
│   ├── bitmaps/
│   │   ├── bitmap_34530232_4c22e739_52x52.png
│   │   ├── bitmap_35992936_4c22e739_52x52.png
│   │   └── ...
│   ├── bitmap_analysis.txt
│   └── bitmap_analysis.html
└── another.hprof/
    ├── hprof_analysis_xxx.html
    └── ...
```

### 报告内容

**Hprof分析报告** (`hprof_analysis_xxx.html`)
- 📊 对象统计（总实例数、总类数等）
- 📱 按包分类（Android、AndroidX、Java、Kotlin等）
- 🖼️ Bitmap统计（数量、大Bitmap数量、提取目录路径）
- 🚨 泄漏对象详情（Activity、Fragment、Bitmap）

**Bitmap分析报告** (`bitmap_analysis.html`)
- 🔴 大Bitmap展示（>1M像素，带尺寸和内存占用）
- 🖼️ 所有Bitmap画廊
- 🔄 重复Bitmap组（SHA-256哈希、内存浪费统计）

## 内存泄露测试Demo

### 功能

`demo/` 目录包含一个独立的Android测试APK，涵盖多种常见的Android内存泄露场景：

#### 📸 Bitmap内存泄露
- **Bitmap泄露 (1440x3200)**: 创建常见手机分辨率的大Bitmap (~18MB)
- **Bitmap泄露 (1920x1080) x10**: 批量创建FHD分辨率Bitmap (~80MB)
- **Bitmap泄露 (2560x2560)**: 创建超大Bitmap测试极端情况 (~26MB)

#### 📦 基础数据类型泄露
- **ByteArray泄露 (1MB x50)**: 创建50个1MB字节数组 (50MB)
- **String泄露 (1MB x50)**: 创建大字符串对象 (~1MB)
- **IntArray泄露 (4MB x10)**: 创建整数数组泄露 (40MB)
- **LongArray泄露 (8MB x10)**: 创建长整数数组泄露 (80MB)

#### 🧵 线程相关泄露
- **Thread泄露 (10个长期运行线程)**: 创建持续运行的线程
- **Runnable泄露 (Handler持有)**: Handler持有Activity引用的Runnable
- **Timer泄露 (未取消)**: Timer未正确取消导致的泄露

#### 🏠 Activity/Context泄露
- **静态Activity引用泄露**: 静态集合持有Activity引用
- **Context泄露 (单例持有)**: 单例模式持有Context引用

#### 🔧 内部类泄露
- **非静态内部类泄露**: 非静态内部类持有外部类引用
- **匿名内部类泄露**: 匿名内部类持有外部类引用

#### 🔌 资源泄露
- **InputStream泄露 (未关闭)**: 打开流但未关闭
- **Drawable泄露 (静态引用)**: Drawable持有View引用

#### 📚 集合泄露
- **ArrayList对象泄露**: 集合中添加大量对象
- **静态集合对象泄露**: 静态集合持续增长

#### 🔄 循环引用泄露
- **双向引用循环泄露**: 对象之间互相引用导致的无法GC

#### 🔥 自动模式
- **自动泄露**: 自动循环制造各种类型的泄露 (每2秒一次)

界面实时显示：
- 已泄露对象数量统计
- 各类型内存占用(MB)
- JVM内存使用情况

### 构建

```bash
cd demo
./gradlew assembleDebug
```

输出: `demo/app/build/outputs/apk/debug/app-debug.apk`

### 安装

```bash
adb install -r demo/app/build/outputs/apk/debug/app-debug.apk
```

## 测试脚本

### 1. Monkey测试 (`scripts/monkey_test.sh`)

随机触发应用操作，测试应用稳定性并产生Bitmap泄露：

```bash
bash scripts/monkey_test.sh
```

功能：
- 自动检查设备连接
- 自动安装/更新应用
- 运行5000个随机事件
- 生成详细报告

### 2. Watch监控 (`scripts/watch_test.sh`)

持续监控应用健康状态：

```bash
bash scripts/watch_test.sh
```

功能：
- 监控堆内存、线程、文件句柄
- 超阈值自动dump
- 自动截屏
- 保存分析报告

### 3. 综合测试 (`scripts/run_test.sh`)

同时运行Monkey和Watch（需要tmux）：

```bash
bash scripts/run_test.sh
```

会在tmux中创建两个窗口：
- 左侧：Watch监控
- 右侧：Monkey测试

### 手动运行示例

```bash
# 终端1：启动监控
java -jar build/libs/mem-monitor-1.0.0-all.jar watch \
  -p com.koom.leak \
  --heap-threshold 80 \
  --thread-threshold 200 \
  -o ./reports

# 终端2：运行Monkey
adb shell monkey -p com.koom.leak \
  --throttle 200 \
  --pct-touch 60 \
  -v 3000
```

## 依赖说明

| 库 | 用途 | 版本 |
|-----|------|------|
| Shark | Hprof分析 | 内置于kshark包 |
| Picocli | CLI框架 | 4.7.5 |
| Okio | IO库 | 1.17.6 |
| ImageIO | 图片处理 | JDK内置 |

## 开发说明

### 构建

```bash
./gradlew shadowJar
```

### 运行测试

```bash
./gradlew test
```

### 测试数据

测试使用 `/home/dk/tmp/hprof/test.hprof` 作为样例数据。

## dumpheap 参数说明

工具在 watch 模式下会自动使用以下参数执行 dumpheap：

```bash
adb shell am dumpheap -g -b png <package> /sdcard/heap.hprof
```

参数说明：
- **`-g`**: 在 dump 前触发一次垃圾回收（GC），清理可回收对象，获得更准确的内存快照
- **`-b png`**: 包含 Bitmap 数据（Android 14+），将 Bitmap 的压缩图片数据（PNG/JPEG/WEBP）包含在 hprof 文件的 `Bitmap.dumpData` 静态字段中

工具会自动检测并读取这些数据，无需手动指定参数。

对于不支持该选项的系统，工具会：
1. 尝试读取Java堆中的Bitmap mBuffer字段
2. 如果数据在native内存中，创建占位图记录尺寸和配置信息

## 常见问题

### Q: ADB连接失败
```
确保：
1. 设备已通过USB连接
2. 已开启USB调试模式
3. 已授权此电脑进行调试
```

### Q: Monkey测试时应用崩溃
```
检查应用是否已启动：
adb shell pm list packages | grep <package_name>
```

### Q: Watch监控不触发dump
```
检查阈值是否设置过高，建议：
- heap-threshold: 80-200 MB
- thread-threshold: 150-300
- fd-threshold: 200-500
```

### Q: Bitmap提取为空图片
```
可能原因：
1. Bitmap数据在native内存中，工具会创建占位图
2. 确保使用 `am dumpheap -g -b png` 获取hprof（Android 14+）
```

## 版本历史

### v1.1.0 (2025-01-20)
- 扩展Demo APK，新增20+种内存泄露类型
- 支持Activity/Context泄露测试
- 支持线程/Runnable/Timer泄露测试
- 支持内部类/匿名内部类泄露测试
- 支持资源(InputStream/Drawable)泄露测试
- 支持集合/循环引用泄露测试
- 支持自动循环泄露模式

### v1.0.0 (2025-01-20)
- 初始版本
- 支持hprof离线分析
- 支持Bitmap提取和重复检测
- 支持实时监控和watch功能
- 支持Android 14+ dumpData
- 添加基础Bitmap泄露测试APK
- 添加Monkey测试脚本

## License

Apache License 2.0

## 致谢

- [Square Shark](https://square.github.io/shark/) - Hprof分析库
- [Picocli](https://picocli.info/) - CLI框架
