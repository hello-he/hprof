# Hprof 泄露检测测试指南

本文档说明如何为 `HprofAnalyzerLeakDetectionTest` 生成测试用的 hprof 文件，并运行测试验证每种泄露类型的检测功能。

## 测试文件结构

测试需要以下 hprof 文件（放在 `~/tmp/hprof/` 目录下）：

```
~/tmp/hprof/
├── activity_leak.hprof          # Activity 泄露测试
├── fragment_leak.hprof          # Fragment 泄露测试
├── dialog_leak.hprof            # Dialog 泄露测试
├── animator_leak.hprof          # Animator 泄露测试
├── bitmap_leak.hprof            # Bitmap 泄露测试
├── bytearray_leak.hprof         # ByteArray 泄露测试
├── all_leaks.hprof              # 综合测试（包含所有泄露类型）
└── test.hprof                   # 通用测试文件
```

## 生成测试 hprof 文件

### 前置条件

1. 已安装 Android SDK 和 adb
2. 已编译并安装 demo APK
3. 设备已连接并开启 USB 调试

### 步骤 1: 安装 Demo APK

```bash
cd mem-monitor/demo
./gradlew installDebug
```

### 步骤 2: 生成各种泄露类型的 hprof 文件

#### 2.1 Activity 泄露

```bash
# 1. 启动 demo APK
adb shell am start -n com.koom.leak/.MainActivity

# 2. 触发 Activity 泄露（点击"Activity泄露"按钮）
# 注意：这会退出 app

# 3. 重新打开 app（创建新的 MainActivity）
adb shell am start -n com.koom.leak/.MainActivity

# 4. Dump hprof（使用 -g 触发 GC，-b png 包含 Bitmap 数据）
adb shell am dumpheap -g -b png com.koom.leak /sdcard/activity_leak.hprof

# 5. 拉取文件
adb pull /sdcard/activity_leak.hprof ~/tmp/hprof/
adb shell rm /sdcard/activity_leak.hprof
```

#### 2.2 Fragment 泄露

```bash
# 1. 启动 demo APK
adb shell am start -n com.koom.leak/.MainActivity

# 2. 触发 Fragment 泄露（点击"Fragment泄露"按钮）
adb shell input tap <x> <y>  # 需要找到按钮坐标，或使用 UI Automator

# 3. 等待 Fragment 被移除（约 500ms）

# 4. Dump hprof（使用 -g 触发 GC，-b png 包含 Bitmap 数据）
adb shell am dumpheap -g -b png com.koom.leak /sdcard/fragment_leak.hprof

# 5. 拉取文件
adb pull /sdcard/fragment_leak.hprof ~/tmp/hprof/
adb shell rm /sdcard/fragment_leak.hprof
```

#### 2.3 Dialog 泄露

```bash
# 1. 启动 demo APK
adb shell am start -n com.koom.leak/.MainActivity

# 2. 触发 Dialog 泄露（点击"Dialog泄露"按钮）
adb shell input tap <x> <y>

# 3. 等待 Dialog 被关闭

# 4. Dump hprof（使用 -g 触发 GC，-b png 包含 Bitmap 数据）
adb shell am dumpheap -g -b png com.koom.leak /sdcard/dialog_leak.hprof

# 5. 拉取文件
adb pull /sdcard/dialog_leak.hprof ~/tmp/hprof/
adb shell rm /sdcard/dialog_leak.hprof
```

#### 2.4 Animator 泄露

```bash
# 1. 启动 demo APK
adb shell am start -n com.koom.leak/.MainActivity

# 2. 触发 Animator 泄露（点击"Animator泄露"按钮）
adb shell input tap <x> <y>

# 3. 等待 Animator 开始运行

# 4. Dump hprof（使用 -g 触发 GC，-b png 包含 Bitmap 数据）
adb shell am dumpheap -g -b png com.koom.leak /sdcard/animator_leak.hprof

# 5. 拉取文件
adb pull /sdcard/animator_leak.hprof ~/tmp/hprof/
adb shell rm /sdcard/animator_leak.hprof
```

#### 2.6 Bitmap 泄露

```bash
# 1. 启动 demo APK
adb shell am start -n com.koom.leak/.MainActivity

# 2. 触发 Bitmap 泄露（点击"Bitmap泄露"按钮）
adb shell input tap <x> <y>

# 3. Dump hprof（使用 -g 触发 GC，-b png 包含 Bitmap 数据）
adb shell am dumpheap -g -b png com.koom.leak /sdcard/bitmap_leak.hprof

# 4. 拉取文件
adb pull /sdcard/bitmap_leak.hprof ~/tmp/hprof/
adb shell rm /sdcard/bitmap_leak.hprof
```

#### 2.7 ByteArray 泄露

```bash
# 1. 启动 demo APK
adb shell am start -n com.koom.leak/.MainActivity

# 2. 触发 ByteArray 泄露（点击"ByteArray泄露"按钮）
adb shell input tap <x> <y>

# 3. Dump hprof（使用 -g 触发 GC，-b png 包含 Bitmap 数据）
adb shell am dumpheap -g -b png com.koom.leak /sdcard/bytearray_leak.hprof

# 4. 拉取文件
adb pull /sdcard/bytearray_leak.hprof ~/tmp/hprof/
adb shell rm /sdcard/bytearray_leak.hprof
```

#### 2.8 综合测试（所有泄露类型）

```bash
# 1. 启动 demo APK
adb shell am start -n com.koom.leak/.MainActivity

# 2. 依次触发所有泄露类型
# 注意：需要手动点击所有泄露按钮，或使用 UI Automator 脚本

# 3. Dump hprof（使用 -g 触发 GC，-b png 包含 Bitmap 数据）
adb shell am dumpheap -g -b png com.koom.leak /sdcard/all_leaks.hprof

# 4. 拉取文件
adb pull /sdcard/all_leaks.hprof ~/tmp/hprof/
adb shell rm /sdcard/all_leaks.hprof
```

## 运行测试

### 运行所有测试

```bash
cd mem-monitor
./gradlew test --tests HprofAnalyzerLeakDetectionTest
```

### 运行特定测试

```bash
# 测试 Activity 泄露检测
./gradlew test --tests HprofAnalyzerLeakDetectionTest.testActivityLeakDetection

# 测试 Fragment 泄露检测
./gradlew test --tests HprofAnalyzerLeakDetectionTest.testFragmentLeakDetection

# 测试所有泄露类型
./gradlew test --tests HprofAnalyzerLeakDetectionTest.testAllLeakTypesDetection
```

### 查看测试报告

```bash
# 测试报告位置
cat build/reports/tests/test/index.html
```

## 使用 UI Automator 自动化生成测试文件

可以创建一个 UI Automator 脚本来自动触发各种泄露：

```kotlin
// 示例：使用 UI Automator 2
device.findObject(UiSelector().text("Activity泄露")).click()
Thread.sleep(1000)
// ... 其他操作
```

## 注意事项

1. **Activity 泄露测试**：需要退出 app 后重新打开，确保旧的 Activity 被销毁但仍被引用
2. **Fragment 泄露测试**：需要等待 Fragment 被移除（约 500ms）
3. **Dialog 泄露测试**：需要等待 Dialog 被关闭
4. **Animator 泄露测试**：需要等待 Animator 开始运行
5. **Bitmap 泄露测试**：确保使用 `-g -b png` 参数 dump hprof 以包含 Bitmap 数据

## 验证测试结果

每个测试都会输出：
- ✅ 成功：检测到对应的泄露类型
- ⚠️ 跳过：测试文件不存在（需要先生成）
- ❌ 失败：检测逻辑有问题

测试通过的标准：
1. 对应的泄露类型计数 > 0
2. 泄露对象列表包含对应的类型
3. 泄露原因描述正确
4. 报告生成正常

## 故障排查

### 问题：测试文件不存在

**解决**：按照上述步骤生成对应的 hprof 文件

### 问题：检测不到泄露

**可能原因**：
1. hprof 文件生成时机不对（泄露对象已被 GC）
2. 泄露对象没有从 GC Root 可达
3. 字段值设置不正确（如 mCleared、mDestroyed 等）

**解决**：
1. 确保在触发泄露后立即 dump hprof
2. 确保泄露对象被静态引用持有
3. 检查 demo APK 中的泄露实现是否正确

### 问题：测试失败

**解决**：
1. 查看测试输出，确认具体失败原因
2. 检查 hprof 文件是否有效
3. 验证分析器代码逻辑是否正确
