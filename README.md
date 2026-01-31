# Android Memory Analyze (mem-analyze)

Android 内存泄露分析工具：**离线分析 hprof 文件**（analyze），以及**设备端内存监控**（device-watch，脚本内置到手机，无需持续连接 adb）。

> 📖 **新手必读：** 想了解什么是内存泄露？查看 [内存泄露详解 (MEMORY_LEAK.md)](MEMORY_LEAK.md)  
> 📊 **工具对比：** 与 KOOM / LeakCanary / Android Studio Profiler 在 analyze 模式下的优劣对比，见 [COMPARISON.md](COMPARISON.md)

## 功能特性

### 1. 离线 Hprof 分析 (`analyze`)

- 分析 hprof 文件，检测 Activity / Fragment / Animator / Service / 大 Bitmap / 大 ByteArray 等泄露
- 自动提取 Bitmap 图片（支持 Android 14+ `am dumpheap -g -b png`）
- 生成 HTML / 文本分析报告
- 检测重复 Bitmap，计算内存浪费

### 2. 设备端监控 (`device-watch`)

- **脚本内置到手机**：部署后可在设备上直接运行，**无需持续连接 adb**
- 监控应用堆内存、线程、文件句柄，超阈值自动 dump hprof
- 支持多包名、包名列表文件（`package_list.txt`）
- 推荐后台运行（`nohup ... > watch.log 2>&1 &`），可拔掉 USB
- 详见 [device-watch/README.md](device-watch/README.md) 与 [DEVICE_WATCH.md](DEVICE_WATCH.md)

### 3. Bitmap 提取（analyze 时可选）

- 支持 Android 14+ dumpData 压缩数据
- 自动检测重复 Bitmap
- 生成可视化 HTML 报告

## 项目结构

```
mem-analyze/
├── src/main/java/com/koom/monitor/
│   ├── Main.kt                      # CLI 入口
│   ├── analyzer/
│   │   ├── BitmapExtractor.kt      # Bitmap 提取、重复检测、报告生成
│   │   ├── HprofAnalyzer.kt        # Hprof 分析、泄露检测
│   │   └── HprofRawReader.kt       # Hprof 原始数据读取
│   ├── command/
│   │   └── AnalyzeCommand.kt       # analyze 命令
│   └── model/
│       └── BitmapModels.kt         # Bitmap/报告模型
├── device-watch/                    # 设备端监控（推荐）
│   ├── device-watch.sh             # 设备上运行的监控脚本
│   ├── deploy-device-watch.sh      # 部署到设备
│   ├── package_list.txt.example    # 包名列表示例
│   └── README.md
├── demo/                            # 泄露测试 APK（独立项目）
├── scripts/                         # 测试与辅助脚本
│   ├── monkey_test.sh
│   ├── run_test.sh                 # device-watch + Monkey 综合测试
│   ├── test_leaks.sh
│   └── ...
├── right-click-menu/                # 右键菜单分析 hprof
│   ├── mem-analyze-1.0.0-all.jar
│   ├── mem-analyze.sh
│   ├── install.sh
│   └── README.md
├── build/libs/
│   └── mem-analyze-1.0.0-all.jar   # 可执行 JAR
└── README.md
```

## 快速开始

### 1. 构建工具

```bash
cd /path/to/mem-analyze
./gradlew shadowJar
```

输出: `build/libs/mem-analyze-1.0.0-all.jar`

### 2. 离线分析 Hprof 文件

```bash
# 基本分析
java -jar build/libs/mem-analyze-1.0.0-all.jar analyze -f heap.hprof

# 自动检测并提取 Bitmap（若 hprof 包含 dumpheap -b 数据）
java -jar build/libs/mem-analyze-1.0.0-all.jar analyze -f heap.hprof

# 只提取大 Bitmap(>1M 像素)
java -jar build/libs/mem-analyze-1.0.0-all.jar analyze -f heap.hprof --large-only

# 指定输出目录
java -jar build/libs/mem-analyze-1.0.0-all.jar analyze -f heap.hprof -o ./reports
```

### 3. 设备端监控（推荐，无需持续连 adb）

部署脚本到设备后，在设备上运行监控，可拔掉 USB：

```bash
cd device-watch
./deploy-device-watch.sh package_list.txt   # 或不用列表，部署后手动传 -p 包名
adb shell "nohup sh /data/local/tmp/run-device-watch.sh > /data/local/tmp/watch.log 2>&1 &"
```

查看是否正常：`adb shell cat /data/local/tmp/watch.log`  
详见 [device-watch/README.md](device-watch/README.md)。

### 4. 安装右键菜单快捷方式（可选）

在 Linux 桌面环境中，可安装右键菜单，直接分析 hprof：

```bash
cd right-click-menu
chmod +x install.sh
./install.sh
```

安装后，在文件管理器中右键 `.hprof` →「打开方式」→「分析内存泄露」。  
详见 [right-click-menu/README.md](right-click-menu/README.md)。

## 命令行参数（analyze）

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-f, --hprof <file>` | hprof 文件路径 | 必填 |
| `-o, --output <dir>` | 输出目录 | ./reports |
| `--large-only` | 只提取大 Bitmap(>1M 像素) | false |

## 输出报告

### 目录结构

按 hprof 文件名分子目录：

```
reports/
├── test.hprof/
│   ├── hprof_analysis.html
│   ├── hprof_analysis.txt
│   ├── bitmaps/
│   ├── bitmap_analysis.txt
│   └── bitmap_analysis.html
└── ...
```

### 报告内容

- **Hprof 分析报告**：对象统计、按包分类、Bitmap 统计、泄露对象详情（Activity / Fragment / Animator 等）
- **Bitmap 分析报告**（存在时）：大 Bitmap、重复 Bitmap 组

## 内存泄露测试 Demo

`demo/` 为独立 Android 项目，包含多种泄露场景（Bitmap、Activity/Context、线程、内部类、资源等）。  
构建：`cd demo && ./gradlew assembleDebug`  
安装：`adb install -r demo/app/build/outputs/apk/debug/app-debug.apk`

## 测试脚本

- **Monkey 测试**：`bash scripts/monkey_test.sh`
- **综合测试（device-watch + Monkey）**：`bash scripts/run_test.sh`（部署 device-watch 后在设备上监控 + PC 跑 Monkey）
- 其他：`test_leaks.sh`、`test_normal.sh` 等见 `scripts/` 目录。

## 依赖说明

| 库 | 用途 | 版本 |
|-----|------|------|
| Shark | Hprof 分析 | 内置于 kshark 包 |
| Picocli | CLI 框架 | 4.7.5 |
| Okio | IO 库 | 1.17.6 |

## 常见问题

- **Bitmap 提取为空**：若 Bitmap 在 native 内存，会生成占位图；Android 14+ 建议用 `am dumpheap -g -b png` 获取 hprof。
- **设备端监控**：部署与用法见 [DEVICE_WATCH.md](DEVICE_WATCH.md)，日志见 `watch.log`。

## License

Apache License 2.0

## 致谢

- [Square Shark](https://square.github.io/shark/) - Hprof 分析库
- [Picocli](https://picocli.info/) - CLI 框架
