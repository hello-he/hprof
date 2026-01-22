# TDD自动化测试结果

## 测试概述
通过TDD驱动开发，使用Android自动化测试触发demo app的泄露项，并通过`adb shell am dumpheap`获取hprof文件，最终通过jar工具检测具体的泄露项。

## Demo APK中可被检测的泄露类型

### 1. Bitmap泄露 (可被检测)
- **测试用例**: `com.koom.leak.action.BITMAP_LEAK`
- **泄露描述**: 创建1440x3200 (约18MB)的大Bitmap
- **检测条件**: 像素 > 1M
- **测试结果**: ✅ 通过 - 检测到大Bitmap泄露

### 2. 多个Bitmap泄露 (可被检测)
- **测试用例**: `com.koom.leak.action.MULTIPLE_BITMAP_LEAK`
- **泄露描述**: 创建10个1920x1080的大Bitmap
- **检测条件**: 像素 > 1M
- **测试结果**: ✅ 通过 - 检测到10个大Bitmap

### 3. 超大Bitmap泄露 (可被检测)
- **测试用例**: `com.koom.leak.action.HUGE_BITMAP_LEAK`
- **泄露描述**: 创建2560x2560 (约26MB)的超大Bitmap
- **检测条件**: 像素 > 1M
- **测试结果**: ✅ 通过 - 检测到超大Bitmap泄露

### 4. ByteArray泄露 (可被检测)
- **测试用例**: `com.koom.leak.action.BYTEARRAY_LEAK`
- **泄露描述**: 创建50个1MB的ByteArray
- **检测条件**: 大小 > 1MB
- **测试结果**: ✅ 通过 - 检测到50个大ByteArray

### 5. 重复线程名泄露 (可被统计)
- **测试用例**: `com.koom.leak.action.DUPLICATE_THREAD_LEAK`
- **泄露描述**: 创建20个名为"WorkerThread"的线程
- **检测方式**: 线程名统计
- **测试结果**: ✅ 通过 - 检测到20个WorkerThread

### 6. 多组重复线程泄露 (可被统计)
- **测试用例**: `com.koom.leak.action.MULTIPLE_DUPLICATE_THREAD_LEAK`
- **泄露描述**: 创建5种x10个的同名线程
- **检测方式**: 线程名统计
- **测试结果**: ✅ 通过 - 检测到多组重复线程

### 7. Activity泄露 (可被检测)
- **测试用例**: `com.koom.leak.action.ACTIVITY_LEAK_AND_EXIT`
- **泄露描述**: 创建静态引用持有MainActivity，然后finish()
- **检测条件**: 已销毁但被GC Root引用
- **测试结果**: ✅ 通过 - 检测到MainActivity泄露

## 使用方法

### 运行单个测试
```bash
cd /home/dk/workspaces/koom/mem-monitor
bash test_leaks.sh bitmap           # 测试Bitmap泄露
bash test_leaks.sh bytearray        # 测试ByteArray泄露
bash test_leaks.sh duplicate_threads # 测试重复线程名
bash test_leaks.sh activity         # 测试Activity泄露
```

### 运行所有测试
```bash
bash test_leaks.sh all
```

## 测试流程
1. 检查设备连接
2. 强制停止app
3. 通过Intent触发特定泄露
4. 等待泄露创建完成
5. 执行`adb shell am dumpheap`获取hprof
6. 拉取hprof文件到本地
7. 使用jar工具分析hprof
8. 验证泄露检测结果

## Demo中仅供演示的泄露类型

以下泄露类型在demo中仅用于演示概念，**不会被工具检测为泄露**：

- Context泄露 (仅演示)
- 内部类泄露 (仅演示)
- 资源泄露 (InputStream, Drawable) (仅演示)
- 集合泄露 (仅演示)
- 循环引用泄露 (仅演示)
- Runnable泄露 (仅演示)
- Timer泄露 (仅演示)
- 普通Thread泄露 (非重复名字) (仅演示)
- 自动泄露模式 (仅演示)

## 检测能力总结

| 泄露类型 | 检测方式 | 状态 |
|---------|---------|------|
| Activity/Fragment泄露 | GC Root分析 | ✅ 支持 |
| 大Bitmap泄露 | 像素计数 (>1M) | ✅ 支持 |
| 大ByteArray泄露 | 大小检查 (>1MB) | ✅ 支持 |
| 重复线程名 | 统计显示 | ✅ 支持 |
| Context泄露 | - | ❌ 不支持 |
| 内部类泄露 | - | ❌ 不支持 |
| 资源泄露 | - | ❌ 不支持 |
| 集合泄露 | - | ❌ 不支持 |
| 循环引用泄露 | - | ❌ 不支持 |
