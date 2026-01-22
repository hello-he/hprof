package com.koom.leak;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.content.Intent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存泄露测试Demo
 * 包含多种常见的Android内存泄露场景
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LeakDemo";

    // 静态泄露集合
    private static List<Bitmap> leakedBitmaps = new ArrayList<>();
    private static List<byte[]> leakedByteArrays = new ArrayList<>();
    private static List<String> leakedStrings = new ArrayList<>();
    private static List<LeakRunnable> leakedRunnables = new ArrayList<>();
    private static List<ActivityHolder> activityLeakList = new ArrayList<>();
    private static List<Object> staticLeakList = new ArrayList<>();
    private static List<Drawable> drawableLeakList = new ArrayList<>();

    private Handler handler = new Handler(Looper.getMainLooper());
    private int leakBatchCount = 0;
    private boolean isLeaking = false;
    private TextView statusText;

    // 用于监控内存使用
    private Runtime runtime = Runtime.getRuntime();
    private TextView memoryInfo;

    // 各种泄露类型的引用
    private Timer leakTimer;
    private HandlerLeak handlerLeak;
    private InnerClassLeak innerClassLeak;
    private List<Runnable> leakedRunnablesList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        // 标题
        TextView titleText = new TextView(this);
        titleText.setText("内存泄露测试Demo");
        titleText.setTextSize(24);
        titleText.setTextColor(Color.BLACK);
        titleText.setPadding(0, 0, 0, 30);
        layout.addView(titleText);

        // 检测能力说明
        TextView capabilityText = new TextView(this);
        capabilityText.setText("🔍 工具可检测的泄露类型：\n" +
                "  ✅ Activity泄露 (已销毁但被引用)\n" +
                "  ✅ Fragment泄露 (已销毁但被引用)\n" +
                "  ✅ 大Bitmap (像素>1M)\n" + "  ✅ 大ByteArray (大小>1MB)\n" +
                "  ✅ 重复线程名 (统计显示)\n\n" +
                "⚠️  其他类型仅供参考，不会被检测为泄露");
        capabilityText.setTextSize(13);
        capabilityText.setPadding(20, 15, 20, 15);
        capabilityText.setBackgroundColor(Color.rgb(240, 248, 255));
        layout.addView(capabilityText);

        // 状态显示
        statusText = new TextView(this);
        statusText.setText("点击下方按钮创建不同类型的内存泄露");
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, 20);
        layout.addView(statusText);

        // 内存信息
        memoryInfo = new TextView(this);
        memoryInfo.setText("内存: 0 MB");
        memoryInfo.setTextSize(14);
        memoryInfo.setPadding(0, 0, 0, 30);
        memoryInfo.setTextColor(Color.rgb(100, 100, 100));
        layout.addView(memoryInfo);

        // ========== Bitmap泄露 ==========
        addSectionTitle(layout, "📸 Bitmap内存泄露 (超大Bitmap可被检测)");
        addLeakButton(layout, "Bitmap泄露 (1440x3200 可被检测)", v -> createBitmapLeak());
        addLeakButton(layout, "Bitmap泄露 (1920x1080) x10 可被检测", v -> createMultipleBitmapLeak());
        addLeakButton(layout, "Bitmap泄露 (2560x2560) 可被检测", v -> createHugeBitmapLeak());

        // ========== 基础数据类型泄露 ==========
        addSectionTitle(layout, "📦 基础数据类型泄露 (大ByteArray可被检测)");
        addLeakButton(layout, "ByteArray泄露 (1MB x50 可被检测)", v -> createByteArrayLeak());
        addLeakButton(layout, "String泄露 (1MB x50 仅演示)", v -> createStringLeak());
        addLeakButton(layout, "IntArray泄露 (4MB x10 仅演示)", v -> createIntArrayLeak());
        addLeakButton(layout, "LongArray泄露 (8MB x10 仅演示)", v -> createLongArrayLeak());

        // ========== 线程相关泄露 ==========
        addSectionTitle(layout, "🧵 线程相关泄露 (重复线程名可被统计)");
        addLeakButton(layout, "Thread泄露 (10个 仅演示)", v -> createThreadLeak());
        addLeakButton(layout, "Runnable泄露 (Handler持有 仅演示)", v -> createRunnableLeak());
        addLeakButton(layout, "Timer泄露 (未取消 仅演示)", v -> createTimerLeak());
        addLeakButton(layout, "🔥 重复线程名泄露 (20个同名 可被统计)", v -> createDuplicateThreadLeak());
        addLeakButton(layout, "🔥 多组重复线程 (5种x10 可被统计)", v -> createMultipleDuplicateThreadLeak());
        addLeakButton(layout, "🔥 增长型重复线程 (+10 可被统计)", v -> createGrowingDuplicateThreadLeak());

        // ========== Activity/Context泄露 ==========
        addSectionTitle(layout, "🏠 Activity/Context泄露");
        addLeakButton(layout, "🔥 Activity泄露 (可被检测)", v -> createActivityLeakAndExit());

        TextView activityTipText = new TextView(this);
        activityTipText.setText("💡 说明：点击后会退出app，重新打开可检测到泄露");
        activityTipText.setTextSize(12);
        activityTipText.setTextColor(Color.rgb(0, 150, 0));
        activityTipText.setPadding(0, 0, 0, 10);
        layout.addView(activityTipText);

        // 标注：其他按钮仅供演示，不会被工具检测为泄露
        addLeakButton(layout, "Context泄露 (仅演示，不会检测)", v -> createSingletonContextLeak());

        // ========== 内部类泄露 ==========
        addSectionTitle(layout, "🔧 内部类泄露");
        addLeakButton(layout, "非静态内部类泄露 (仅演示)", v -> createInnerClassLeak());
        addLeakButton(layout, "匿名内部类泄露 (仅演示)", v -> createAnonymousClassLeak());

        // 添加说明
        TextView innerClassTip = new TextView(this);
        innerClassTip.setText("💡 注：内部类泄露工具暂不支持检测\n这些按钮仅用于演示概念");
        innerClassTip.setTextSize(11);
        innerClassTip.setTextColor(Color.rgb(150, 150, 150));
        innerClassTip.setPadding(0, 0, 0, 10);
        layout.addView(innerClassTip);

        // ========== 资源泄露 ==========
        addSectionTitle(layout, "🔌 资源泄露");
        addLeakButton(layout, "InputStream泄露 (仅演示)", v -> createInputStreamLeak());
        addLeakButton(layout, "Drawable泄露 (仅演示)", v -> createDrawableLeak());

        // ========== 集合泄露 ==========
        addSectionTitle(layout, "📚 集合泄露");
        addLeakButton(layout, "ArrayList对象泄露 (仅演示)", v -> createArrayListLeak());
        addLeakButton(layout, "静态集合对象泄露 (仅演示)", v -> createStaticCollectionLeak());

        // ========== 循环引用泄露 ==========
        addSectionTitle(layout, "🔄 循环引用泄露");
        addLeakButton(layout, "双向引用循环泄露 (仅演示)", v -> createCircularReferenceLeak());

        // ========== 自动泄露 ==========
        addSectionTitle(layout, "🔥 自动模式");
        addLeakButton(layout, "自动泄露 (自动循环制造 仅演示)", v -> startAutoLeak());

        // 清空按钮
        addClearButton(layout);

        scrollView.addView(layout);
        setContentView(scrollView);
    }

    private void addSectionTitle(LinearLayout layout, String title) {
        TextView textView = new TextView(this);
        textView.setText(title);
        textView.setTextSize(14);
        textView.setTextColor(Color.rgb(50, 50, 150));
        textView.setPadding(0, 20, 0, 10);
        layout.addView(textView);
    }

    private void addLeakButton(LinearLayout layout, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        button.setTextSize(13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 10);
        layout.addView(button, params);
    }

    private void addClearButton(LinearLayout layout) {
        Button button = new Button(this);
        button.setText("🗑️ 清空所有泄露 (释放内存)");
        button.setTextColor(Color.rgb(0, 100, 0));
        button.setOnClickListener(v -> clearAllLeaks());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 20, 0, 0);
        layout.addView(button, params);
    }

    // ==================== Bitmap泄露 ====================

    private void createBitmapLeak() {
        Bitmap bitmap = Bitmap.createBitmap(1440, 3200, Bitmap.Config.ARGB_8888);
        fillBitmap(bitmap, leakBatchCount++);
        leakedBitmaps.add(bitmap);
        updateStatus();
        showToast("创建Bitmap泄露: ~18MB");
    }

    private void createMultipleBitmapLeak() {
        for (int i = 0; i < 10; i++) {
            Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            fillBitmap(bitmap, leakBatchCount++);
            leakedBitmaps.add(bitmap);
        }
        updateStatus();
        showToast("创建10个Bitmap泄露: ~80MB");
    }

    private void createHugeBitmapLeak() {
        Bitmap bitmap = Bitmap.createBitmap(2560, 2560, Bitmap.Config.ARGB_8888);
        fillBitmap(bitmap, leakBatchCount++);
        leakedBitmaps.add(bitmap);
        updateStatus();
        showToast("创建超大Bitmap泄露: ~26MB");
    }

    // ==================== 基础数据类型泄露 ====================

    private void createByteArrayLeak() {
        for (int i = 0; i < 50; i++) {
            leakedByteArrays.add(new byte[1024 * 1024]);
        }
        leakBatchCount += 50;
        updateStatus();
        showToast("创建ByteArray泄露: 50MB");
    }

    private void createStringLeak() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("LeakString Data " + i + ", ");
        }
        leakedStrings.add(sb.toString());
        leakBatchCount++;
        updateStatus();
        showToast("创建String泄露: ~1MB");
    }

    private void createIntArrayLeak() {
        for (int i = 0; i < 10; i++) {
            staticLeakList.add(new int[1024 * 1024]); // 4MB
        }
        leakBatchCount += 10;
        updateStatus();
        showToast("创建IntArray泄露: 40MB");
    }

    private void createLongArrayLeak() {
        for (int i = 0; i < 10; i++) {
            staticLeakList.add(new long[1024 * 1024]); // 8MB
        }
        leakBatchCount += 10;
        updateStatus();
        showToast("创建LongArray泄露: 80MB");
    }

    // ==================== 线程相关泄露 ====================

    private void createThreadLeak() {
        for (int i = 0; i < 10; i++) {
            LeakRunnable runnable = new LeakRunnable();
            Thread thread = new Thread(runnable, "LeakThread-" + i);
            leakedRunnables.add(runnable);
            thread.start();
        }
        leakBatchCount += 10;
        updateStatus();
        showToast("创建线程泄露: 10个线程");
    }

    private void createRunnableLeak() {
        // Handler持有Activity引用的Runnable，导致Activity无法释放
        Runnable leakedRunnable = new Runnable() {
            @Override
            public void run() {
                // 持有MainActivity的引用，延迟执行导致泄露
                System.gc();
                handler.postDelayed(this, 10000);
            }
        };
        leakedRunnablesList.add(leakedRunnable);
        handler.postDelayed(leakedRunnable, 10000);
        leakBatchCount++;
        updateStatus();
        showToast("创建Runnable泄露 (Handler持有)");
    }

    private void createTimerLeak() {
        if (leakTimer != null) {
            leakTimer.cancel();
        }
        leakTimer = new Timer();
        leakTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // 持有外部类引用
                Log.d(TAG, "Timer leak running...");
            }
        }, 0, 1000);
        leakBatchCount++;
        updateStatus();
        showToast("创建Timer泄露 (未取消)");
    }

    // ==================== Activity/Context泄露 ====================

    /**
     * 演示真正的Activity泄露
     * 1. 创建静态引用持有当前MainActivity
     * 2. 调用finish()并返回桌面
     * 3. 重新打开app时会创建新的MainActivity
     * 4. Dump Heap可以检测到旧的MainActivity泄露
     */
    private void createActivityLeakAndExit() {
        // 1. 创建静态引用持有当前MainActivity
        ActivityHolder holder = new ActivityHolder(this);
        activityLeakList.add(holder);

        // 2. 返回桌面
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);

        // 3. 调用finish()销毁当前Activity
        finish();

        showToast("MainActivity 已创建泄露并退出，请重新打开 app");
    }

    private void createStaticActivityLeak() {
        // 静态引用Activity，导致Activity无法被GC
        ActivityHolder holder = new ActivityHolder(this);
        activityLeakList.add(holder);
        leakBatchCount++;
        updateStatus();
        showToast("创建静态Activity引用泄露");
    }

    private void createSingletonContextLeak() {
        // 单例持有Context引用
        LeakSingleton.getInstance().setContext(this);
        leakBatchCount++;
        updateStatus();
        showToast("创建单例Context泄露");
    }

    // ==================== 内部类泄露 ====================

    private void createInnerClassLeak() {
        // 非静态内部类持有外部类引用
        innerClassLeak = new InnerClassLeak();
        innerClassLeak.startLeaking();
        leakBatchCount++;
        updateStatus();
        showToast("创建非静态内部类泄露");
    }

    private void createAnonymousClassLeak() {
        // 匿名内部类持有外部类引用
        handlerLeak = new HandlerLeak();
        handlerLeak.start();
        leakBatchCount++;
        updateStatus();
        showToast("创建匿名内部类泄露");
    }

    // ==================== 资源泄露 ====================

    private void createInputStreamLeak() {
        new Thread(() -> {
            try {
                // 打开InputStream但不关闭
                for (int i = 0; i < 100; i++) {
                    InputStream is = getAssets().open(" ic_launcher.png");
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] data = new byte[1024];
                    int len;
                    while ((len = is.read(data)) != -1) {
                        buffer.write(data, 0, len);
                    }
                    // 故意不关闭is
                    staticLeakList.add(buffer.toByteArray());
                }
                runOnUiThread(() -> {
                    leakBatchCount += 100;
                    updateStatus();
                    showToast("创建InputStream泄露");
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void createDrawableLeak() {
        // Drawable持有View的引用
        for (int i = 0; i < 50; i++) {
            View view = new View(this);
            view.setBackgroundColor(Color.rgb(i * 5, 100, 200));
            Drawable drawable = view.getBackground();
            drawableLeakList.add(drawable);
            staticLeakList.add(view);
        }
        leakBatchCount += 50;
        updateStatus();
        showToast("创建Drawable泄露: 50个");
    }

    // ==================== 集合泄露 ====================

    private void createArrayListLeak() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            list.add(new byte[1024]); // 1KB
        }
        staticLeakList.add(list);
        leakBatchCount++;
        updateStatus();
        showToast("创建ArrayList对象泄露: ~10MB");
    }

    private void createStaticCollectionLeak() {
        for (int i = 0; i < 1000; i++) {
            staticLeakList.add(new Object());
            staticLeakList.add(new byte[1024 * 10]); // 10KB
        }
        leakBatchCount += 1000;
        updateStatus();
        showToast("创建静态集合泄露: ~10MB");
    }

    // ==================== 循环引用泄露 ====================

    private void createCircularReferenceLeak() {
        for (int i = 0; i < 1000; i++) {
            Node nodeA = new Node("A" + i);
            Node nodeB = new Node("B" + i);
            nodeA.ref = nodeB;
            nodeB.ref = nodeA;
            staticLeakList.add(nodeA);
            staticLeakList.add(nodeB);
        }
        leakBatchCount += 2000;
        updateStatus();
        showToast("创建循环引用泄露: 2000个节点");
    }

    // ==================== 自动泄露 ====================

    private void startAutoLeak() {
        if (!isLeaking) {
            isLeaking = true;
            leakBatchCount = 0;
            showToast("开始自动制造内存泄露... (每2秒一次)");
            startAutoLeakLoop();
        }
    }

    private void startAutoLeakLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isLeaking) {
                    // 循环创建各种类型的泄露
                    switch (leakBatchCount % 8) {
                        case 0:
                            createBitmapLeak();
                            break;
                        case 1:
                            createByteArrayLeak();
                            break;
                        case 2:
                            createStringLeak();
                            break;
                        case 3:
                            createMultipleBitmapLeak();
                            break;
                        case 4:
                            createHugeBitmapLeak();
                            break;
                        case 5:
                            createThreadLeak();
                            break;
                        case 6:
                            createStaticCollectionLeak();
                            break;
                        case 7:
                            createIntArrayLeak();
                            break;
                    }

                    Toast.makeText(MainActivity.this, "已制造 " + leakBatchCount + " 批泄露", Toast.LENGTH_SHORT).show();

                    // 每2秒制造一次泄露
                    handler.postDelayed(this, 2000);
                }
            }
        }, 1000);
    }

    // ==================== 清空泄露 ====================

    private void clearAllLeaks() {
        // 清空静态集合
        leakedBitmaps.clear();
        leakedByteArrays.clear();
        leakedStrings.clear();
        activityLeakList.clear();
        staticLeakList.clear();
        drawableLeakList.clear();

        // 停止并清空线程
        for (LeakRunnable runnable : leakedRunnables) {
            runnable.stop();
        }
        leakedRunnables.clear();

        // 清理增长型线程
        for (Thread thread : growingThreads) {
            thread.interrupt();
        }
        growingThreads.clear();

        // 清空Handler
        handler.removeCallbacksAndMessages(null);
        leakedRunnablesList.clear();

        // 停止Timer
        if (leakTimer != null) {
            leakTimer.cancel();
            leakTimer = null;
        }

        // 停止内部类泄露
        if (innerClassLeak != null) {
            innerClassLeak.stop();
            innerClassLeak = null;
        }
        if (handlerLeak != null) {
            handlerLeak.stop();
            handlerLeak = null;
        }

        // 清空单例
        LeakSingleton.clearInstance();

        isLeaking = false;
        leakBatchCount = 0;

        // 建议GC
        System.gc();
        System.gc();

        updateStatus();
        showToast("已清空所有泄露，建议GC已执行");
    }

    private void fillBitmap(Bitmap bitmap, int seed) {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        int color = Color.rgb(
            (seed * 50) % 256,
            (seed * 30 + 100) % 256,
            (seed * 70 + 150) % 256
        );
        paint.setColor(color);
        canvas.drawPaint(paint);
    }

    private void updateStatus() {
        long totalPixels = 0;
        for (Bitmap bmp : leakedBitmaps) {
            totalPixels += bmp.getWidth() * bmp.getHeight();
        }

        long byteArrayMB = leakedByteArrays.size();

        long stringMB = 0;
        for (String s : leakedStrings) {
            stringMB += s.length();
        }

        long bitmapMB = totalPixels * 4 / (1024 * 1024);

        long collectionMB = staticLeakList.size() * 10 / 1024; // 估算

        long totalMB = bitmapMB + byteArrayMB + stringMB / (1024 * 1024) + collectionMB;

        int threadCount = leakedRunnables.size();

        StringBuilder sb = new StringBuilder();
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("内存泄露统计\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Bitmap数量: ").append(leakedBitmaps.size()).append("\n");
        sb.append("Bitmap占用: ").append(bitmapMB).append(" MB\n");
        sb.append("ByteArray数量: ").append(leakedByteArrays.size()).append("\n");
        sb.append("ByteArray占用: ").append(byteArrayMB).append(" MB\n");
        sb.append("String数量: ").append(leakedStrings.size()).append("\n");
        sb.append("String占用: ~").append(stringMB / 1024).append(" KB\n");
        sb.append("集合对象数: ").append(staticLeakList.size()).append("\n");
        sb.append("Activity泄露: ").append(activityLeakList.size()).append("\n");
        sb.append("Drawable泄露: ").append(drawableLeakList.size()).append("\n");
        sb.append("泄露线程数: ").append(threadCount).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("总占用: ~").append(totalMB).append(" MB\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (isLeaking) {
            sb.append("\n🔥 自动泄露运行中...");
        }

        statusText.setText(sb.toString());

        // 更新内存信息
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        long usedMB = used / (1024 * 1024);
        long maxMB = max / (1024 * 1024);
        memoryInfo.setText(String.format("JVM内存: %d MB / %d MB (已使用: %.1f%%)",
            usedMB, maxMB, (used * 100.0 / max)));
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ==================== 内部类定义 ====================

    /**
     * Activity持有者，用于测试Activity泄露
     */
    private static class ActivityHolder {
        private final AppCompatActivity activity;

        ActivityHolder(AppCompatActivity activity) {
            this.activity = activity;
        }
    }

    /**
     * 单例泄露，持有Context引用
     */
    private static class LeakSingleton {
        private static LeakSingleton instance;
        private Context context;

        private LeakSingleton() {}

        public static LeakSingleton getInstance() {
            if (instance == null) {
                instance = new LeakSingleton();
            }
            return instance;
        }

        public void setContext(Context context) {
            this.context = context;
        }

        public static void clearInstance() {
            instance = null;
        }
    }

    /**
     * 非静态内部类，持有外部类引用
     */
    private class InnerClassLeak {
        private List<byte[]> data = new ArrayList<>();
        private boolean running = true;

        void startLeaking() {
            new Thread(() -> {
                while (running) {
                    try {
                        data.add(new byte[1024 * 10]);
                        Thread.sleep(1000);
                        if (data.size() > 1000) {
                            data.clear();
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }).start();
        }

        void stop() {
            running = false;
        }
    }

    /**
     * Handler泄露，持有Activity引用
     */
    private class HandlerLeak {
        private Handler innerHandler = new Handler(Looper.getMainLooper());
        private Runnable runnable = new Runnable() {
            @Override
            public void run() {
                innerHandler.postDelayed(this, 5000);
            }
        };

        void start() {
            innerHandler.post(runnable);
        }

        void stop() {
            innerHandler.removeCallbacks(runnable);
        }
    }

    /**
     * 循环引用节点
     */
    private static class Node {
        String name;
        Node ref;

        Node(String name) {
            this.name = name;
        }
    }

    /**
     * 可泄漏的线程，用于测试线程泄漏
     */
    private static class LeakRunnable implements Runnable {
        private volatile boolean running = true;
        private final List<byte[]> data = new ArrayList<>();

        @Override
        public void run() {
            while (running) {
                try {
                    for (int i = 0; i < 100; i++) {
                        data.add(new byte[1024 * 10]); // 10KB
                    }
                    Thread.sleep(1000);

                    if (data.size() > 10000) {
                        data.clear();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        public void stop() {
            running = false;
        }
    }

    /**
     * 创建重复名字的线程泄露
     * 模拟常见的线程池管理不当导致的线程泄露
     */
    private void createDuplicateThreadLeak() {
        // 场景1: 创建多个同名的工作线程 (常见于线程池管理不当)
        for (int i = 0; i < 20; i++) {
            new Thread(new LeakRunnable(), "WorkerThread").start();
        }
        leakBatchCount += 20;
        updateStatus();
        showToast("创建重复线程泄露: 20个WorkerThread");
    }

    /**
     * 创建多种重复名字的线程泄露
     */
    private void createMultipleDuplicateThreadLeak() {
        // 场景2: 多种类型的重复线程名
        String[] threadNames = {
            "OkHttp Dispatcher",  // 网络请求线程
            "Executor-Service",   // 线程池
            "AsyncTask",          // 异步任务
            "Timer-Thread",       // 定时器线程
            "Connection-Thread"   // 连接线程
        };

        for (String name : threadNames) {
            for (int i = 0; i < 10; i++) {
                new Thread(new LeakRunnable(), name).start();
            }
        }
        leakBatchCount += threadNames.length * 10;
        updateStatus();
        showToast("创建多组重复线程泄露: " + threadNames.length + "种 x 10个");
    }

    /**
     * 创建无限增长的同名线程
     */
    private static List<Thread> growingThreads = new ArrayList<>();
    private void createGrowingDuplicateThreadLeak() {
        // 场景3: 每次点击增加10个同名线程
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(new LeakRunnable(), "Growing-Leak-Thread-" + (growingThreads.size() / 10));
            growingThreads.add(t);
            t.start();
        }
        leakBatchCount += 10;
        updateStatus();
        showToast("创建增长型重复线程: +10个 (共" + growingThreads.size() + "个)");
    }

}
