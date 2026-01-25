# Android Memory Monitor - 快速开始

快速监控和分析Android应用的内存问题。

## Demo APK 支持的泄露类型

| 分类 | 泄露类型 | 内存占用 |
|------|---------|---------|
| 📸 Bitmap | 1440x3200 Bitmap | ~18MB |
| 📸 Bitmap | 1920x1080 x10 | ~80MB |
| 📸 Bitmap | 2560x2560 超大 | ~26MB |
| 📦 ByteArray | 1MB x50 | 50MB |
| 📦 String | 1MB | ~1MB |
| 📦 IntArray | 4MB x10 | 40MB |
| 📦 LongArray | 8MB x10 | 80MB |
| 🧵 Thread | 10个长期线程 | 持续增长 |
| 🧵 Runnable | Handler持有 | 延迟释放 |
| 🧵 Timer | 未取消 | 持续运行 |
| 🏠 Activity | 静态引用 | 无法GC |
| 🏠 Context | 单例持有 | 无法GC |
| 🔧 内部类 | 非静态/匿名 | 持有外部引用 |
| 🔌 资源 | InputStream/Drawable | 未关闭 |
| 📚 集合 | ArrayList/静态集合 | 持续增长 |
| 🔄 循环引用 | 双向引用 | 无法GC |

## 一分钟快速开始

### 1. 构建工具

```bash
./gradlew shadowJar
```

### 2. 监控应用

```bash
# 终端1：启动监控（设置低阈值便于测试）
java -jar build/libs/mem-monitor-1.0.0-all.jar watch \
  -p com.example.app \
  --heap-threshold 100
```

### 3. 触发内存问题

```bash
# 终端2：运行Monkey让应用产生内存压力
adb shell monkey -p com.example.app 5000
```

### 4. 查看报告

当超过阈值时，工具会自动：
- Dump堆内存到 `reports/` 目录
- 截图保存现场

## 分析Hprof文件

```bash
java -jar build/libs/mem-monitor-1.0.0-all.jar analyze -f heap.hprof
```

工具会自动检测hprof文件是否包含Bitmap数据（来自 `am dumpheap -b`），如果包含则自动提取并生成报告。

输出：`reports/heap.hprof/bitmap_analysis.html` - 在浏览器中打开查看

## 常用命令

```bash
# 扫描应用内存状态
java -jar build/libs/mem-monitor-1.0.0-all.jar scan -p com.example.app

# 只提取大Bitmap
java -jar build/libs/mem-monitor-1.0.0-all.jar analyze -f heap.hprof --large-only

# 使用测试APK验证监控功能
cd demo && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
java -jar build/libs/mem-monitor-1.0.0-all.jar watch -p com.koom.leak --heap-threshold 50
```

## 测试脚本

```bash
# 自动Monkey测试
bash scripts/monkey_test.sh

# 自动Watch监控
bash scripts/watch_test.sh

# 同时运行（需要tmux）
bash scripts/run_test.sh
```

## 输出说明

```
reports/
├── test.hprof/              # 按hprof文件名分目录
│   ├── hprof_analysis.html   # 内存泄漏分析报告
│   ├── bitmaps/              # 提取的Bitmap图片
│   └── bitmap_analysis.html  # Bitmap重复检测报告
```

## 阈值建议

| 指标 | 警告阈值 | 危险阈值 |
|------|---------|---------|
| 堆内存 | 200MB | 512MB |
| 线程数 | 200 | 300 |
| 文件句柄 | 300 | 500 |

## 系统要求

- JDK 17+
- Android 5.0+ (API 21+)
- ADB已配置
- Android 14+ 支持Bitmap图片提取

详细文档请查看 [README.md](README.md)
