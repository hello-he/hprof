# 设备端内存监控

本文档介绍如何在 Android 设备上直接运行内存监控，无需持续连接 PC。

## 概述

传统的 `watch` 命令需要通过 PC 端的 adb 连接来执行监控。设备端监控提供了另一种方式：

- 将监控脚本部署到设备上
- 在设备上独立运行监控
- 当内存超过阈值时自动 dump hprof
- 之后再将 hprof 文件拉取到 PC 进行分析

这种方式适合以下场景：
- Monkey 测试时让监控在设备上持续运行
- 长时间测试时无需保持 adb 连接
- 多设备同时测试

## 快速开始

### 1. 部署监控脚本

```bash
cd mem-monitor/scripts
chmod +x deploy-device-watch.sh
./deploy-device-watch.sh
```

### 2. 在设备上启动监控

```bash
# 方式1: 通过 adb 直接启动（会保持前台运行）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app

# 方式2: 后台运行（适合 Monkey 测试）
adb shell "nohup sh /data/local/tmp/device-watch.sh -p com.example.app > /data/local/tmp/watch.log 2>&1 &"
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
adb pull /data/local/tmp/mem-monitor/ ./

# 分析 hprof
java -jar mem-monitor-1.0.0-all.jar analyze heap_com.example.app_20260128_123456.hprof
```

## 命令参数

```
用法: sh /data/local/tmp/device-watch.sh -p <包名> [选项]

必填参数:
  -p <包名>       要监控的应用包名

可选参数:
  -t <阈值>       堆内存使用率阈值 (%)，默认: 80
  -i <间隔>       监控间隔 (秒)，默认: 10
  -n <次数>       连续超过阈值触发dump次数，默认: 3
  -o <目录>       输出目录，默认: /data/local/tmp/mem-monitor
  -m <次数>       最大监控次数，0=无限，默认: 0
  -g              dump 前执行 GC
  -b              包含 bitmap 数据 (Android 14+)
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

### 带 GC 的 dump

```bash
# dump 前执行 GC，减少非泄露对象的干扰
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
adb pull /data/local/tmp/mem-monitor/ ./
```

### 限制监控次数

```bash
# 最多监控 100 次（约 1000 秒 = 16 分钟）
adb shell sh /data/local/tmp/device-watch.sh -p com.example.app -m 100
```

## 输出说明

### 监控日志

```
[0] 14:30:15 | 📊 堆: 45% (92160KB/204800KB) | 线程: 28 | FD: 156
[1] 14:30:25 | 📊 堆: 52% (106496KB/204800KB) (+14336KB) | 线程: 32 | FD: 162
[2] 14:30:35 | ⚠️  堆: 82% (167936KB/204800KB) (+61440KB) | 线程: 35 | FD: 170
    ↳ 超过阈值! (连续 1/3 次)
[3] 14:30:45 | ⚠️  堆: 85% (174080KB/204800KB) (+6144KB) | 线程: 36 | FD: 172
    ↳ 超过阈值! (连续 2/3 次)
[4] 14:30:55 | ⚠️  堆: 88% (180224KB/204800KB) (+6144KB) | 线程: 37 | FD: 175
    ↳ 超过阈值! (连续 3/3 次)

==========================================
  触发 Heap Dump
==========================================
  包名: com.example.app
  原因: 堆内存连续 3 次超过阈值 80%
  时间: 14:30:55
  输出: /data/local/tmp/mem-monitor/heap_com.example.app_20260128_143055.hprof
==========================================
```

### hprof 文件位置

```
/data/local/tmp/mem-monitor/
├── heap_com.example.app_20260128_143055.hprof
├── heap_com.example.app_20260128_150012.hprof
└── ...
```

## 与 PC 端 watch 的对比

| 特性 | PC 端 watch | 设备端 watch |
|------|-------------|--------------|
| 需要 adb 连接 | 持续需要 | 仅部署时需要 |
| 实时分析 | ✅ 自动分析 | ❌ 需手动拉取分析 |
| 快速内存增长检测 | ✅ 支持 | ❌ 不支持 |
| 截屏 | ✅ 自动截屏 | ❌ 不支持 |
| 资源占用 | PC 端 | 设备端 |
| Monkey 测试 | 需同时运行 | 可后台运行 |
| 多设备支持 | 需切换设备 | 每设备独立运行 |

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
adb shell rm -rf /data/local/tmp/mem-monitor/
adb shell rm /data/local/tmp/watch.log
```

## 完整工作流示例

```bash
#!/bin/bash
# Monkey 测试完整工作流

PACKAGE="com.example.app"
MONKEY_EVENTS=10000

# 1. 部署监控脚本
./scripts/deploy-device-watch.sh

# 2. 清理旧数据
adb shell rm -rf /data/local/tmp/mem-monitor/
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
adb pull /data/local/tmp/mem-monitor/ ./monkey_results/
adb pull /data/local/tmp/watch.log ./monkey_results/

# 9. 分析 hprof 文件
for hprof in ./monkey_results/mem-monitor/*.hprof; do
    if [ -f "$hprof" ]; then
        echo "分析: $hprof"
        java -jar build/libs/mem-monitor-1.0.0-all.jar analyze "$hprof" -o ./monkey_results/
    fi
done

echo "完成！结果保存在 ./monkey_results/"
```
