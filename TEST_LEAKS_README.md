# 自动化泄露测试脚本使用指南

## 概述

`test_leaks.sh` 是一个完全自动化的测试脚本，用于：
1. 自动触发 demo APK 中的各种泄露类型
2. 自动 dump hprof 文件
3. 自动分析 hprof 文件
4. 自动验证检测结果

**无需手动操作**，脚本会自动完成所有步骤。

## 快速开始

### 1. 前置条件

- Android 设备已连接并开启 USB 调试
- 已安装 demo APK
- 已构建 mem-monitor JAR 文件（脚本会自动构建）

### 2. 运行所有测试

```bash
cd mem-monitor
./scripts/test_leaks.sh all
```

### 3. 运行单个测试

```bash
# Activity 泄露测试
./scripts/test_leaks.sh activity

# Fragment 泄露测试
./scripts/test_leaks.sh fragment

# View 泄露测试
./scripts/test_leaks.sh view

# ViewModel 泄露测试
./scripts/test_leaks.sh viewmodel

# Service 泄露测试
./scripts/test_leaks.sh service

# Dialog 泄露测试
./scripts/test_leaks.sh dialog

# Handler/Message 泄露测试
./scripts/test_leaks.sh handler_message

# BroadcastReceiver 泄露测试
./scripts/test_leaks.sh broadcast_receiver

# Animator 泄露测试
./scripts/test_leaks.sh animator

# Bitmap 泄露测试
./scripts/test_leaks.sh bitmap

# ByteArray 泄露测试
./scripts/test_leaks.sh bytearray
```

## 支持的测试类型

| 测试类型 | 命令参数 | 说明 |
|---------|---------|------|
| Activity 泄露 | `activity` | 测试已销毁的 Activity 仍被引用 |
| Fragment 泄露 | `fragment` | 测试已销毁的 Fragment 仍被引用 |
| View 泄露 | `view` | 测试 root view 持有已销毁 Activity 引用 |
| ViewModel 泄露 | `viewmodel` | 测试已清除的 ViewModel 仍被引用 |
| Service 泄露 | `service` | 测试已停止的 Service 仍被引用 |
| Dialog 泄露 | `dialog` | 测试已关闭的 Dialog 仍被引用 |
| Handler/Message 泄露 | `handler_message` | 测试 Message.obj 持有已销毁对象 |
| BroadcastReceiver 泄露 | `broadcast_receiver` | 测试已注册但未注销的 Receiver |
| Animator 泄露 | `animator` | 测试无限循环的 Animator 持有引用 |
| Bitmap 泄露 | `bitmap` | 测试大 Bitmap (>1M像素) |
| ByteArray 泄露 | `bytearray` | 测试大 ByteArray (>1MB) |
| 所有测试 | `all` | 运行所有测试用例 |

## 工作流程

每个测试用例的执行流程：

1. **检查设备连接** - 验证 Android 设备已连接
2. **检查/构建 JAR** - 如果 JAR 不存在，自动构建
3. **触发泄露** - 通过 Intent 自动触发对应的泄露类型
4. **等待泄露创建** - 等待泄露对象创建完成
5. **Dump Heap** - 自动执行 `adb shell am dumpheap -g -b png`（-g 触发 GC，-b png 包含 Bitmap 数据）
6. **拉取文件** - 自动从设备拉取 hprof 文件
7. **分析文件** - 自动运行 mem-monitor 分析
8. **验证结果** - 自动验证检测结果是否符合预期

## 输出文件

所有测试文件保存在 `/tmp/mem-monitor-test/` 目录：

```
/tmp/mem-monitor-test/
├── test_activity_leak.hprof              # Activity 泄露的 hprof 文件
├── test_fragment_leak.hprof              # Fragment 泄露的 hprof 文件
├── test_view_leak.hprof                  # View 泄露的 hprof 文件
├── ...
├── analysis_output_*/                    # 分析输出目录
│   ├── hprof_analysis.html               # HTML 报告
│   ├── hprof_analysis.txt                # 文本报告
│   └── bitmaps/                          # Bitmap 图片（如果提取）
└── analysis_result_*.txt                 # 分析结果日志
```

## 验证逻辑

脚本会自动验证：

1. **泄露计数** - 检查对应的泄露类型计数是否 > 0
2. **泄露对象** - 检查泄露对象列表是否包含对应类型
3. **泄露原因** - 检查泄露原因描述是否正确
4. **报告完整性** - 检查报告是否包含必要信息

## 示例输出

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  测试: Activity泄露检测
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
➜ 检查设备连接...
✓ 设备已连接
➜ 启动app并触发Activity泄露...
➜ 执行dumpheap: test_activity_leak.hprof
✓ hprof文件已保存到: /tmp/mem-monitor-test/test_activity_leak.hprof
➜ 分析hprof文件: /tmp/mem-monitor-test/test_activity_leak.hprof
➜ 执行命令: java -jar .../mem-monitor-1.0.0-all.jar analyze --hprof="..." --output="..."
✓ 分析完成
➜ 验证泄露检测结果...
✓ 检测到Activity泄露 (1个)
✓ 测试通过 (1/1)
```

## 故障排查

### 问题：设备未连接

```
✗ 未检测到Android设备
```

**解决**：
1. 检查设备是否通过 USB 连接
2. 运行 `adb devices` 确认设备已连接
3. 确认 USB 调试已开启

### 问题：APK 未安装

```
✗ com.koom.leak 未安装
```

**解决**：
```bash
cd mem-monitor/demo
./gradlew installDebug
```

### 问题：JAR 文件不存在

脚本会自动构建，如果构建失败：
```bash
cd mem-monitor
./gradlew shadowJar
```

### 问题：分析失败

查看详细日志：
```bash
cat /tmp/mem-monitor-test/analysis_result_*.txt
```

### 问题：检测不到泄露

可能原因：
1. 泄露对象已被 GC（等待时间不够）
2. 泄露对象没有从 GC Root 可达
3. 字段值设置不正确

**解决**：
1. 增加等待时间（修改脚本中的 `sleep` 时间）
2. 检查 demo APK 中的泄露实现
3. 查看 hprof 文件确认泄露对象是否存在

## 高级用法

### 自定义输出目录

修改脚本中的 `TEST_OUTPUT_DIR` 变量：

```bash
TEST_OUTPUT_DIR="/path/to/custom/output"
```

### 自定义等待时间

修改脚本中各测试用例的 `sleep` 时间，以适应不同的设备性能。

### 批量测试

可以创建一个包装脚本来运行所有测试并生成报告：

```bash
#!/bin/bash
cd mem-monitor
./scripts/test_leaks.sh all 2>&1 | tee test_results.log
```

## 与单元测试集成

这个脚本可以与 `HprofAnalyzerLeakDetectionTest.kt` 配合使用：

1. **脚本生成测试数据** - 使用 `test_leaks.sh` 生成各种泄露的 hprof 文件
2. **单元测试验证** - 使用 `HprofAnalyzerLeakDetectionTest` 验证检测逻辑

将生成的 hprof 文件复制到 `~/tmp/hprof/` 目录：

```bash
# 复制测试文件到单元测试期望的位置
mkdir -p ~/tmp/hprof
cp /tmp/mem-monitor-test/test_*.hprof ~/tmp/hprof/

# 重命名以匹配单元测试期望的文件名
mv ~/tmp/hprof/test_activity_leak.hprof ~/tmp/hprof/activity_leak.hprof
mv ~/tmp/hprof/test_fragment_leak.hprof ~/tmp/hprof/fragment_leak.hprof
# ... 等等

# 运行单元测试
cd mem-monitor
./gradlew test --tests HprofAnalyzerLeakDetectionTest
```

## 持续集成

可以在 CI/CD 环境中使用此脚本：

```yaml
# GitHub Actions 示例
- name: Run Leak Detection Tests
  run: |
    cd mem-monitor
    ./test_leaks.sh all
```

## 注意事项

1. **Activity 和 View 泄露测试**：需要退出 app 后重新打开，确保旧的 Activity 被销毁
2. **Fragment 泄露测试**：需要等待 Fragment 被移除（约 500ms）
3. **Dialog 泄露测试**：需要等待 Dialog 被关闭
4. **Handler/Message 泄露测试**：需要等待 Message 被发送到队列
5. **Animator 泄露测试**：需要等待 Animator 开始运行

所有等待时间已在脚本中设置，通常不需要调整。
