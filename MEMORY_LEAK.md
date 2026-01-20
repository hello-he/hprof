# Android 内存泄露详解

## 什么是内存泄露？

### 基本定义

**内存泄露 (Memory Leak)** 是指应用程序中不再使用的对象仍然被引用链持有，导致垃圾回收器 (GC) 无法回收这些对象，从而造成内存持续增长的现象。

### Android 中的内存泄露

在 Android 中，内存泄露特指：

> 一个对象已经完成了它的生命周期（如 Activity 已调用 `onDestroy()`），但由于存在从 **GC Root** 到该对象的引用链，导致该对象无法被垃圾回收器回收。

### 判断标准（来自 KOOM）

KOOM 使用以下标准判断对象是否泄露：

| 对象类型 | 判断条件 |
|---------|---------|
| **Activity** | `mFinished \|\| mDestroyed` 为 true，但仍可从 GC Root 到达 |
| **Fragment** | `mFragmentManager == null && mCalled == true`（生命周期回调已完成），但仍可从 GC Root 到达 |
| **其他对象** | 应该被回收但存在从 GC Root 到达的引用链 |

---

## 内存泄露的危害

1. **内存占用持续增长** - 可用内存逐渐减少
2. **应用性能下降** - 频繁触发 GC，导致卡顿
3. **OOM 崩溃** - 最终导致 `OutOfMemoryError` 崩溃
4. **应用被系统杀死** - 系统为了回收内存会杀死进程

---

## 常见的内存泄露类型

### 1. 静态引用泄露 (Static Reference Leak)

**问题：** 静态变量持有 Activity/Context 引用

```java
// ❌ 错误示例
public class MainActivity extends AppCompatActivity {
    private static List<Activity> leakedActivities = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        leakedActivities.add(this);  // Activity 无法被回收
    }
}

// ✅ 正确做法
public class MainActivity extends AppCompatActivity {
    private static List<WeakReference<Activity>> activityRefs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        activityRefs.add(new WeakReference<>(this));
    }
}
```

### 2. Handler 泄露 (Handler Leak)

**问题：** 匿名内部类持有外部类引用

```java
// ❌ 错误示例
public class MainActivity extends AppCompatActivity {
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // 隐式持有 MainActivity 引用
        }
    };
}

// ✅ 正确做法 - 使用静态内部类 + WeakReference
private static class StaticHandler extends Handler {
    private final WeakReference<MainActivity> activityRef;

    StaticHandler(MainActivity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    @Override
    public void handleMessage(Message msg) {
        MainActivity activity = activityRef.get();
        if (activity != null) {
            // 处理消息
        }
    }
}
```

### 3. 非静态内部类泄露 (Non-Static Inner Class Leak)

**问题：** 非静态内部类隐式持有外部类引用

```java
// ❌ 错误示例
public class MainActivity extends AppCompatActivity {
    class LeakTask extends Thread {
        // 隐式持有 MainActivity 引用
    }

    new LeakTask().start();
}

// ✅ 正确做法 - 使用静态内部类
private static class SafeTask extends Thread {
    // 不持有外部类引用
}
```

### 4. 单例模式泄露 (Singleton Leak)

**问题：** 单例持有 Context 引用

```java
// ❌ 错误示例
public class Singleton {
    private static Singleton instance;
    private Context context;

    private Singleton(Context context) {
        this.context = context;  // 持有 Activity 引用
    }

    public static Singleton getInstance(Context context) {
        if (instance == null) {
            instance = new Singleton(context);
        }
        return instance;
    }
}

// ✅ 正确做法 - 使用 Application Context
public static Singleton getInstance(Context context) {
    if (instance == null) {
        instance = new Singleton(context.getApplicationContext());
    }
    return instance;
}
```

### 5. 资源未关闭泄露 (Resource Leak)

**问题：** InputStream、Cursor、Bitmap 等资源未关闭

```java
// ❌ 错误示例
InputStream is = null;
try {
    is = new FileInputStream(file);
    // 使用流
} catch (IOException e) {
    e.printStackTrace();
}
// 未关闭流

// ✅ 正确做法
InputStream is = null;
try {
    is = new FileInputStream(file);
    // 使用流
} catch (IOException e) {
    e.printStackTrace();
} finally {
    if (is != null) {
        try {
            is.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
```

### 6. 线程泄露 (Thread Leak)

**问题：** 线程未正确停止或 detached

```java
// ❌ 错误示例
public void startLeakingThread() {
    new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                Thread.sleep(200000);  // 长期运行
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }).start();
}

// ✅ 正确做法 - 管理 Thread 生命周期
private Thread workerThread;
private volatile boolean running = false;

public void start() {
    running = true;
    workerThread = new Thread(() -> {
        while (running) {
            // 工作逻辑
        }
    });
    workerThread.start();
}

public void stop() {
    running = false;
    if (workerThread != null) {
        workerThread.interrupt();
        workerThread = null;
    }
}

@Override
protected void onDestroy() {
    super.onDestroy();
    stop();
}
```

### 7. Timer 泄露 (Timer Leak)

**问题：** Timer 未取消

```java
// ❌ 错误示例
private Timer timer;

void startTimer() {
    timer = new Timer();
    timer.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
            // TimerTask 持有外部类引用
        }
    }, 0, 1000);
}
// 未在 onDestroy 中取消

// ✅ 正确做法
@Override
protected void onDestroy() {
    super.onDestroy();
    if (timer != null) {
        timer.cancel();
        timer = null;
    }
}
```

### 8. Drawable/View 泄露

**问题：** Drawable 持有 View 的引用

```java
// ❌ 错误示例
private static Drawable leakedDrawable;

void leakDrawable() {
    View view = new View(this);
    leakedDrawable = view.getBackground();  // Drawable 持有 View 引用
}

// ✅ 正确做法 - 及时清理
@Override
protected void onDestroy() {
    super.onDestroy();
    if (view != null) {
        view.setBackground(null);
    }
}
```

### 9. 集合对象泄露 (Collection Leak)

**问题：** 集合持续增长，对象未移除

```java
// ❌ 错误示例
private static List<Object> cache = new ArrayList<>();

void addToCache(Object obj) {
    cache.add(obj);  // 持续增长，从不清理
}

// ✅ 正确做法 - 使用 WeakHashMap 或 LRU 缓存
private static Map<String, Object> cache = new WeakHashMap<>();

// 或使用 LruCache
private static LruCache<String, Object> cache = new LruCache<>(100);
```

### 10. 循环引用泄露 (Circular Reference Leak)

**问题：** 对象之间互相引用

```java
// ❌ 问题场景
class NodeA {
    NodeB ref;
}

class NodeB {
    NodeA ref;
}

// 即使没有外部引用，A 和 B 互相持有也可能导致无法回收（取决于 GC 算法）

// ✅ 正确做法 - 避免强引用循环，使用弱引用
class NodeA {
    WeakReference<NodeB> ref;
}
```

---

## 内存泄露检测原理

### LeakCanary 检测原理

LeakCanary 的核心检测流程：

```
1. 监听对象生命周期
   ↓
2. 检查对象是否被销毁 (如 Activity.onDestroy())
   ↓
3. 触发 GC (System.gc())
   ↓
4. 检查对象是否仍然存在
   ↓
5. 如果存在，Dump 堆内存 (hprof)
   ↓
6. 分析 hprof，查找从 GC Root 到泄露对象的引用链
   ↓
7. 生成泄露报告
```

### KOOM 检测原理

KOOM 采用 **Copy-on-Write** 方案：

```
1. 暂停 ART VM
   ↓
2. Fork 子进程 (Copy-on-Write)
   ↓
3. 恢复 ART VM (主进程立即恢复，<20ms)
   ↓
4. 子进程 Dump hprof
   ↓
5. 分析 hprof（在子进程中）
   ↓
6. 生成泄露报告
```

**KOOM 的优势：** 主进程冻结时间从 ~20 秒减少到 <20ms

### GC Root 类型

从 GC Root 开始搜索，能到达的对象就是存活对象：

| GC Root 类型 | 说明 |
|-------------|------|
| **静态变量** | 类的静态字段 |
| **JNI Local/Global** | JNI 中的本地/全局引用 |
| **Thread** | 活动线程及其栈帧 |
| **正在运行的线程** | Thread 对象及其引用 |
| **Monitor** | 等待锁的线程 |

---

## 引用类型与内存泄露

### Java 四种引用类型

| 引用类型 | GC 回收时机 | 用途 |
|---------|------------|------|
| **强引用 (Strong)** | 永不回收 | 默认引用类型 |
| **软引用 (Soft)** | 内存不足时回收 | 缓存 |
| **弱引用 (Weak)** | 每次 GC 都会回收 | 避免泄露 |
| **虚引用 (Phantom)** | 无法通过引用获取对象 | 跟踪对象回收 |

### 避免内存泄露的最佳实践

```java
// 1. 使用 WeakReference 持有 Context
private WeakReference<Context> contextRef;

// 2. 使用 WeakHashMap 作为缓存
Map<String, Object> cache = new WeakHashMap<>();

// 3. 使用 LruCache
LruCache<String, Bitmap> bitmapCache = new LruCache<>(cacheSize);

// 4. 使用 Application Context 而非 Activity Context
getApplicationContext();  // 生命周期与进程相同
```

---

## 常见引用链类型

KOOM/Shark 识别的引用类型：

| 引用类型 | 说明 |
|---------|------|
| **INSTANCE_FIELD** | 实例字段引用 |
| **STATIC_FIELD** | 静态字段引用（最常见泄露原因）|
| **ARRAY_ENTRY** | 数组元素引用 |
| **LOCAL** | 局部变量引用 |

---

## 内存泄露检测工具对比

| 工具 | 特点 | 优势 | 劣势 |
|-----|------|------|------|
| **LeakCanary** | 自动检测，易集成 | 集成简单，报告详细 | 仅开发环境使用 |
| **KOOM** | Fork 方案，生产可用 | 低延迟，适合线上 | 集成复杂 |
| **Android Profiler** | 官方工具 | 可视化分析 | 需手动操作 |
| **MAT** | 离线分析 | 功能强大 | 需要专业分析能力 |

---

## 参考资料

### LeakCanary
- [LeakCanary 官方文档](https://square.github.io/leakcanary/)
- [LeakCanary GitHub](https://github.com/square/leakcanary)

### KOOM
- [KOOM - Kwai OOM Killer](https://github.com/Kwai/koom)
- Java Heap Leak Monitor - 基于 Fork 的堆内存检测
- Native Heap Leak Monitor - Native 内存泄露检测
- Thread Leak Monitor - 线程泄露检测

### 相关文章
- [Understanding Memory Leaks & How LeakCanary Can Help](https://www.droidcon.com/2025/01/21/understanding-memory-leaks-in-android-how-leakcanary-can-help/)
- [Top 7 Android Memory Leaks and How to Avoid Them in 2025](https://artemasoyan.medium.com/top-7-android-memory-leaks-and-how-to-avoid-them-in-2025-b77e15a7b62e)
- [Memory Leaks in Android: A Guide for Android Developers](https://proandroiddev.com/memory-leaks-in-android-a-guide-for-android-developers-448fa86ced27)

---

## 总结

内存泄露是 Android 开发中必须重视的问题：

1. **理解泄露类型** - 熟悉常见的 10+ 种泄露模式
2. **使用检测工具** - 开发期用 LeakCanary，线上用 KOOM
3. **遵循最佳实践** - 及时释放资源，避免不必要的强引用
4. **定期分析** - 使用 Profiler 和 MAT 定期分析内存使用

**核心原则：** 当对象不再需要时，确保没有从 GC Root 到该对象的引用链存在。
