# 设备端内存监控

本文档介绍如何在 Android 设备上直接运行内存监控，**无需持续连接 PC**。

## 概述

本项目的监控能力以**设备端监控（device-watch）**为主：将监控脚本部署到设备上，在设备上独立运行，超阈值自动 dump hprof，之后可将 hprof 拉取到 PC 用 `analyze` 分析。

- 将监控脚本部署到设备上（`deploy-device-watch.sh`）
- 在设备上独立运行监控（可拔掉 USB）
- 当内存超过阈值时自动 dump hprof
- 将 hprof 拉取到 PC 后使用 `java -jar mem-analyze-*.jar -f xxx.hprof` 分析

适合以下场景：
- Monkey 测试时让监控在设备上持续运行
- 长时间测试时无需保持 adb 连接
- 多设备同时测试

## 快速开始

### 1. 部署监控脚本

脚本位于 **device-watch** 目录（与 scripts 同级）：

```bash
cd mem-analyze/device-watch
chmod +x deploy-device-watch.sh
./deploy-device-watch.sh
```

### 2. 在设备上启动监控

```bash
# 方式1: 通过 adb 直接启动（会保持前台运行）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app

# 方式2: 多包名监控（可多次指定 -p）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app -p com.other.app

# 方式3: 后台运行（适合 Monkey 测试）
adb shell "nohup sh /data/local/tmp/device-watch.sh -p com.example.app > /data/local/tmp/watch.log 2>&1 &"
```

### 2.1 停止监控

- **前台运行**：在运行 `device-watch.sh` 的终端按 **Ctrl+C** 即可正常停止。
- **后台运行**：使用 `pkill` 结束进程：
  ```bash
  adb shell "pkill -f device-watch.sh"
  ```

### 3. 执行 Monkey 测试

```bash
adb shell monkey -p com.example.app --throttle 300 -v 10000
```

### 4. 获取结果

```bash
# 查看监控日志
adb shell cat /data/local/tmp/watch.log

# 拉取 hprof 文件
adb pull /data/local/tmp/mem-analyze/ ./

# 分析 hprof
java -jar mem-analyze-1.0.0-all.jar -f heap_com.example.app_20260128_123456.hprof
```

## 命令参数

```
用法: sh /data/local/tmp/device-watch.sh -p <包名> [选项]

必填参数:
  -p <包名>       要监控的应用包名（可多次指定以监控多个应用）

可选参数:
  -t <阈值>       堆内存使用率阈值 (%)，默认: 80
  -i <间隔>       监控间隔 (秒)，默认: 10
  -n <次数>       连续超过阈值触发dump次数，默认: 3
  -o <目录>       输出目录，默认: /data/local/tmp/mem-analyze
  -m <次数>       最大监控次数，0=无限，默认: 0
  -g              dump 前执行 GC（默认启用）
  -b              包含 bitmap 数据 (Android 14+)
  --fd-threshold <数量>    FD 阈值，默认: 1000
  --thread-threshold <数量> 线程阈值，默认: 750（EMUI且≤8.0为450）
  --heap-high-watermark <百分比> 堆高水位线，默认: 90（立即触发）
  --heap-delta <MB>        堆增量阈值，默认: 350MB（立即触发）
  -h              显示帮助
```

## 使用示例

### 基本监控

```bash
# 监控 com.example.app，使用默认配置
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app
```

### 自定义阈值

```bash
# 堆内存超过 70% 时触发 dump
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app -t 70
```

### 更敏感的监控

```bash
# 5秒检查一次，连续2次超过阈值就触发 dump
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app -i 5 -n 2
```

### GC 选项说明

```bash
# 默认已启用 GC，dump 前会自动执行 GC，减少非泄露对象的干扰
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app

# -g 参数可显式指定（与默认行为相同，用于明确意图）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app -g
```

### 包含 Bitmap 数据 (Android 14+)

```bash
# 包含 Bitmap 图片数据（hprof 文件会更大）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app -g -b
```

### Monkey 测试配合后台监控

```bash
# 1. 后台启动监控
adb shell "nohup sh /data/local/tmp/device-watch.sh -p com.example.app -t 70 -g > /data/local/tmp/watch.log 2>&1 &"

# 2. 执行 Monkey
adb shell monkey -p com.example.app --throttle 200 -v 50000

# 3. 停止监控
adb shell "pkill -f device-watch.sh"

# 4. 查看结果
adb shell cat /data/local/tmp/watch.log
adb pull /data/local/tmp/mem-analyze/ ./
```

### 限制监控次数

```bash
# 最多监控 100 次（约 1000 秒 = 16 分钟）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app -m 100
```

### FD 和 Thread 阈值监控（参考 KOOM）

```bash
# 监控 FD 泄露（默认阈值 1000）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app --fd-threshold 500

# 监控线程泄露（默认阈值 750，EMUI且≤8.0为450）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app --thread-threshold 200

# 同时监控多个指标
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app \
  --fd-threshold 500 \
  --thread-threshold 200 \
  -t 70
```

### Heap 剧烈增长检测（参考 KOOM）

```bash
# 堆内存超过 85% 立即触发（默认 90%）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app --heap-high-watermark 85

# 堆内存单次增长超过 200MB 立即触发（默认 350MB）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app --heap-delta 200

# 组合使用：同时监控使用率和增量
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app \
  -t 80 \
  --heap-high-watermark 90 \
  --heap-delta 350
```

## 输出说明

### 监控日志

```
[0] 14:30:15 | 📊 堆: 45% (92160KB/204800KB) | 线程: 28 | FD: 156
[1] 14:30:25 | 📊 堆: 52% (106496KB/204800KB) (+14336KB) | 线程: 32 | FD: 162
[2] 14:30:35 | ⚠️  堆: 82% (167936KB/204800KB) (+61440KB) | 线程: 35 | FD: 170
    ↳ 超过阈值! (连续: 堆:1/3)
[3] 14:30:45 | ⚠️  堆: 85% (174080KB/204800KB) (+6144KB) | 线程: 36 | FD: 172
    ↳ 超过阈值! (连续: 堆:2/3)
[4] 14:30:55 | ⚠️  堆: 88% (180224KB/204800KB) (+6144KB) | 线程: 37 | FD: 175
    ↳ 超过阈值! (连续: 堆:3/3)

==========================================
  触发 Heap Dump
==========================================
  包名: com.example.app
  原因: 文件句柄连续 3 次超过阈值 1000
  时间: 14:30:55
  输出: /data/local/tmp/mem-analyze/heap_com.example.app_20260128_143055.hprof
  FD 信息: /data/local/tmp/mem-analyze/fd_com.example.app_20260128_143055.txt
  正在 dump FD 信息...
  ✓ FD 信息已保存: /data/local/tmp/mem-analyze/fd_com.example.app_20260128_143055.txt (共 1050 个 FD)
    FD 统计: 文件=450, socket=300, pipe=200, anon=100
    最常见的文件 FD (前10个):
      1. [15次] /data/data/com.example.app/files/cache1
      2. [12次] /data/data/com.example.app/files/cache2
      3. [8次] /system/lib/libc.so
      ...
==========================================
```

### hprof 文件位置

```
/data/local/tmp/mem-analyze/
├── heap_com.example.app_20260128_143055.hprof
├── fd_com.example.app_20260128_143055.txt      # FD 触发时生成
├── heap_com.example.app_20260128_150012.hprof
└── ...
```

**注意**：
- 当因 **FD 阈值**触发 dump 时，会同时生成 `fd_*.txt` 文件
- `hprof` 文件**不包含** FD 信息，需要查看对应的 `fd_*.txt` 文件
- `fd_*.txt` 文件包含所有文件描述符的路径列表（按字母排序，参考 KOOM）

## 说明

使用**设备端 device-watch**：部署后可在设备上运行，无需持续连接 adb，适合 Monkey 测试与多设备场景。分析在 PC 端使用 `-f <hprof>` 完成。

## 监控机制说明（参考 KOOM）

### 触发条件

脚本支持以下 5 种触发 dump 的条件：

1. **堆内存使用率阈值**（连续超过）
   - 默认阈值：80%
   - 浮动范围：±5%（避免 GC 导致的误判）
   - 触发条件：连续 3 次超过阈值

2. **FD（文件描述符）阈值**（连续超过）
   - 默认阈值：1000
   - 浮动范围：±50（避免临时波动）
   - 触发条件：连续 3 次超过阈值

3. **Thread（线程数）阈值**（连续超过）
   - 默认阈值：750（EMUI 且 Android ≤ 8.0 为 450）
   - 浮动范围：±50
   - 触发条件：连续 3 次超过阈值

4. **堆高水位线**（立即触发）
   - 默认阈值：90%
   - 触发条件：堆内存使用率 ≥ 90% 时**立即触发**（无需连续）

5. **堆增量阈值**（立即触发）
   - 默认阈值：350MB
   - 触发条件：单次监控间隔内堆内存增长 > 350MB 时**立即触发**

### 浮动范围机制

为避免因 GC、临时波动导致的误判，脚本使用浮动范围机制：

- **Heap**：允许 5% 的下降（例如从 85% 降到 80% 仍算连续）
- **FD/Thread**：允许 50 的下降（例如从 1050 降到 1000 仍算连续）

只有当指标**持续超过阈值**且**下降不超过浮动范围**时，才会累计计数。

### FD 信息 dump（重要）

当因 **FD 阈值**触发 dump 时，脚本会：

1. **自动 dump FD 信息**到 `fd_<包名>_<时间戳>.txt` 文件
2. **在日志中显示**：
   - FD 总数统计
   - 按类型分类（文件、socket、pipe、anon）
   - 最常见的文件 FD 路径（前 10 个，带出现次数）

**为什么需要单独 dump FD？**
- `hprof` 文件只包含 Java 堆内存信息
- **不包含**文件描述符（FD）信息
- FD 泄露无法通过 hprof 分析，必须查看 `fd_*.txt` 文件

**FD 文件格式**（参考 KOOM）：
- 每行一个文件描述符路径
- 按字母排序
- 包含所有 FD 类型：文件、socket、pipe、匿名 inode 等

**分析 FD 泄露**：
```bash
# 拉取 FD 文件
adb pull /data/local/tmp/mem-analyze/fd_com.example.app_20260128_143055.txt ./

# 查看最常见的 FD（出现次数最多的路径）
cat fd_com.example.app_20260128_143055.txt | grep "^/" | sort | uniq -c | sort -rn | head -20

# 查找可能的泄露（大量重复的路径）
cat fd_com.example.app_20260128_143055.txt | grep "^/" | sort | uniq -c | sort -rn | awk '$1 > 10 {print}'
```

## 故障排除

### 应用未运行

```
[14:30:15] ⏳ 等待应用启动: com.example.app
```

监控脚本会自动等待应用启动。

### 权限问题

如果 dump 失败，可能需要检查：
1. 应用是否允许被 dump（某些系统应用可能不允许）
2. 设备存储空间是否充足

### 停止监控

```bash
# 方式1: 在终端按 Ctrl+C

# 方式2: 远程停止
adb shell "pkill -f device-watch.sh"
```

### 清理输出文件

```bash
adb shell rm -rf /data/local/tmp/mem-analyze/
adb shell rm /data/local/tmp/watch.log
```

## 完整工作流示例

```bash
#!/bin/bash
# Monkey 测试完整工作流

PACKAGE="com.example.app"
MONKEY_EVENTS=10000

# 1. 部署监控脚本（脚本位于 device-watch 目录）
./device-watch/deploy-device-watch.sh

# 2. 清理旧数据
adb shell rm -rf /data/local/tmp/mem-analyze/
adb shell rm -f /data/local/tmp/watch.log

# 3. 启动应用
adb shell am start -n $PACKAGE/.MainActivity

# 4. 后台启动内存监控
adb shell "nohup sh /data/local/tmp/device-watch.sh -p $PACKAGE -t 70 -g > /data/local/tmp/watch.log 2>&1 &"
echo "内存监控已启动"

# 5. 执行 Monkey 测试
echo "开始 Monkey 测试..."
adb shell monkey -p $PACKAGE --throttle 300 -v $MONKEY_EVENTS

# 6. 等待最后一次监控完成
sleep 15

# 7. 停止监控
adb shell "pkill -f device-watch.sh"
echo "监控已停止"

# 8. 获取结果
mkdir -p ./monkey_results
adb pull /data/local/tmp/mem-analyze/ ./monkey_results/
adb pull /data/local/tmp/watch.log ./monkey_results/

# 9. 分析 hprof 文件
for hprof in ./monkey_results/mem-analyze/*.hprof; do
    if [ -f "$hprof" ]; then
        echo "分析: $hprof"
        java -jar build/libs/mem-analyze-1.0.0-all.jar -f "$hprof" -o ./monkey_results/
    fi
done

echo "完成！结果保存在 ./monkey_results/"
```
