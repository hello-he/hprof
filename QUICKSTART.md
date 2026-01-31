# Android Memory Analyze (mem-analyze) - 快速开始

快速分析 Android 应用内存泄露：**离线分析 hprof** + **设备端监控**（device-watch，无需持续连 adb）。

## Demo APK 支持的泄露类型

| 分类 | 泄露类型 | 内存占用 |
|------|---------|---------|
| 📸 Bitmap | 1440x3200 Bitmap | ~18MB |
| 📸 Bitmap | 1920x1080 x10 | ~80MB |
| 📦 ByteArray | 1MB x50 | 50MB |
| 🧵 Thread / Runnable / Timer | 多种 | 持续增长 |
| 🏠 Activity / Context | 静态引用、单例持有 | 无法 GC |
| 🔧 内部类 / 资源 / 集合 / 循环引用 | 多种 | 见 Demo |

## 一分钟快速开始

### 1. 构建工具

```bash
./gradlew shadowJar
```

### 2. 设备端监控（推荐）

部署 device-watch 到设备后，在设备上运行监控，可拔掉 USB：

```bash
cd device-watch
./deploy-device-watch.sh
adb shell "nohup sh /data/local/tmp/device-watch.sh -p com.example.app > /data/local/tmp/watch.log 2>&1 &"
```

查看是否正常：`adb shell cat /data/local/tmp/watch.log`

### 3. 触发内存问题

```bash
adb shell monkey -p com.example.app 5000
```

### 4. 分析 Hprof 文件

从设备拉取 dump 出的 hprof 后，在本机分析：

```bash
java -jar build/libs/mem-analyze-1.0.0-all.jar analyze -f heap.hprof
```

工具会自动检测 hprof 是否包含 Bitmap 数据（来自 `am dumpheap -g -b png`），若包含则自动提取并生成报告。

**参数说明**：
- `-g`: dump 前触发 GC，获得更准确快照
- `-b png`: 包含 Bitmap 数据（Android 14+）

## 常用命令

```bash
# 离线分析
java -jar build/libs/mem-analyze-1.0.0-all.jar analyze -f heap.hprof

# 只提取大 Bitmap
java -jar build/libs/mem-analyze-1.0.0-all.jar analyze -f heap.hprof --large-only

# 综合测试（device-watch + Monkey）
bash scripts/run_test.sh
```

## 测试脚本

```bash
# Monkey 测试
bash scripts/monkey_test.sh

# 综合测试（设备端监控 + Monkey）
bash scripts/run_test.sh

# 泄露自动化测试
./scripts/test_leaks.sh all
```

## 输出说明

```
reports/
├── test.hprof/
│   ├── hprof_analysis.html   # 泄露分析报告
│   ├── bitmaps/              # 提取的 Bitmap 图片
│   └── bitmap_analysis.html # Bitmap 重复检测（存在时）
```

## 系统要求

- JDK 17+
- Android 5.0+ (API 21+)
- 设备端监控：部署时需 adb；运行后可不连 USB
- Android 14+ 支持 Bitmap 图片提取

详细文档请查看 [README.md](README.md)、[DEVICE_WATCH.md](DEVICE_WATCH.md)。
