# Android Memory Monitor

Android内存监控工具，通过ADB shell监控Android应用的内存、线程、文件句柄，并支持自动dump和Bitmap提取分析。

## 功能特性

### 1. 离线Hprof分析 (`analyze`)
- 分析hprof文件，检测内存泄漏
- 自动提取Bitmap图片（支持Android 14+ dumpData）
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
- 支持Android 14+ `am dumpheap -b png`压缩数据
- 自动检测重复Bitmap
- 生成可视化报告

## 项目结构

```
mem-monitor/
├── src/main/java/com/koom/monitor/
│   ├── Main.kt                 # CLI入口
│   ├── adb/
│   │   └── AdbClient.kt        # ADB通信封装
│   ├── analyzer/
│   │   ├── BitmapExtractor.kt # Bitmap提取和分析
│   │   ├── HprofAnalyzer.kt    # Hprof文件分析
│   │   └── HprofRawReader.kt   # Hprof原始数据读取
│   ├── command/
│   │   ├── AnalyzeCommand.kt   # analyze命令
│   │   ├── ScanCommand.kt      # scan命令
│   │   └── WatchCommand.kt     # watch命令
│   └── model/
│       ├── MonitorConfig.kt    # 监控配置
│       └── MetricsSnapshot.kt  # 指标快照
├── demo/                       # Bitmap泄露测试APK
└── build/libs/
    └── mem-monitor-1.0.0-all.jar  # 可执行JAR
```

## 使用方法

### 前置要求
- JDK 17+
- ADB已配置
- Android设备已连接并授权USB调试

### 离线分析Hprof文件

```bash
# 基本分析
java -jar mem-monitor-1.0.0-all.jar analyze -f heap.hprof

# 提取Bitmap并生成报告
java -jar mem-monitor-1.0.0-all.jar analyze -f heap.hprof --extract-bitmaps

# 只提取大Bitmap(>1M像素)
java -jar mem-monitor-1.0.0-all.jar analyze -f heap.hprof --extract-bitmaps --large-only

# 指定输出目录
java -jar mem-monitor-1.0.0-all.jar analyze -f heap.hprof -o ./reports
```

### 实时监控应用

```bash
# 监控单个应用
java -jar mem-monitor-1.0.0-all.jar watch -p com.example.app

# 设置阈值并监控
java -jar mem-monitor-1.0.0-all.jar watch -p com.example.app \
  --heap-threshold 512 \
  --thread-threshold 300 \
  --fd-threshold 500

# 指定ADB路径
java -jar mem-monitor-1.0.0-all.jar watch -p com.example.app --adb /path/to/adb
```

### 单次扫描

```bash
# 扫描多个应用
java -jar mem-monitor-1.0.0-all.jar scan -p com.example.app,com.another.app

# 扫描并设置阈值
java -jar mem-monitor-1.0.0-all.jar scan -p com.example.app \
  --heap-threshold 400 --thread-threshold 200
```

## 输出报告

### 目录结构（按hprof文件名区分）

```
reports/
├── heap.hprof/                # 以hprof文件名创建子目录
│   ├── hprof_analysis_xxx.html
│   ├── hprof_analysis_xxx.txt
│   ├── bitmaps/
│   │   ├── bitmap_xxx.png
│   │   └── ...
│   ├── bitmap_analysis.txt
│   └── bitmap_analysis.html
└── another.hprof/
    └── ...
```

### 报告内容

**Hprof分析报告** (hprof_analysis_xxx.html)
- 对象统计
- 包分类统计
- Bitmap统计（包含提取目录路径）
- 泄漏对象详情

**Bitmap分析报告** (bitmap_analysis.html)
- 总Bitmap数量
- 大Bitmap列表(>1M像素)
- 重复Bitmap组
- 内存浪费统计
- 可视化图片展示

## Bitmap测试Demo

### 构建

```bash
cd demo
./gradlew assembleDebug
```

### 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 运行Monkey测试

```bash
# 简单monkey测试
adb shell monkey -p com.koom.leak 1000

# 生成详细报告
adb shell monkey -p com.koom.leak --throttle 500 -v 1000 > monkey_report.txt
```

### 同时监控

```bash
# 终端1：运行monkey
adb shell monkey -p com.koom.leak 5000

# 终端2：运行监控
java -jar mem-monitor-1.0.0-all.jar watch -p com.koom.leak
```

## 依赖说明

- **Shark库**: Square的Hprof分析库（内置于kshark包）
- **Picocli**: CLI框架
- **Okio**: Square的IO库
- **ImageIO**: Java图片处理

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

## 版本历史

### v1.0.0
- 初始版本
- 支持hprof离线分析
- 支持Bitmap提取和重复检测
- 支持实时监控和watch功能
- 支持Android 14+ dumpData

## License

Apache License 2.0
