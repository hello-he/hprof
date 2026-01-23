package com.koom.normal;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.app.Fragment;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 正常使用场景Demo
 * 包含所有15个测试用例的正常版本（不产生泄露）
 * 用于证伪jar分析功能，确保不会误报正常使用场景
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NormalDemo";
    private Handler handler = new Handler(Looper.getMainLooper());

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
        titleText.setText("正常使用场景Demo");
        titleText.setTextSize(24);
        titleText.setTextColor(Color.BLACK);
        titleText.setPadding(0, 0, 0, 30);
        layout.addView(titleText);

        // 说明
        TextView infoText = new TextView(this);
        infoText.setText("✅ 所有场景都是正常使用，不应该被检测为泄露\n" +
                "用于验证jar分析功能不会误报");
        infoText.setTextSize(12);
        infoText.setPadding(20, 15, 20, 15);
        infoText.setBackgroundColor(Color.rgb(240, 255, 240));
        layout.addView(infoText);

        // 添加按钮
        addSectionTitle(layout, "📸 Bitmap正常使用");
        addButton(layout, "Bitmap正常使用 (1440x3200)", v -> createNormalBitmap());
        addButton(layout, "多个Bitmap正常使用 (1920x1080 x10)", v -> createNormalMultipleBitmap());
        addButton(layout, "超大Bitmap正常使用 (2560x2560)", v -> createNormalHugeBitmap());

        addSectionTitle(layout, "📦 ByteArray正常使用");
        addButton(layout, "ByteArray正常使用 (1MB x50)", v -> createNormalByteArray());

        addSectionTitle(layout, "🧵 线程正常使用");
        addButton(layout, "重复线程正常使用 (不同线程名)", v -> createNormalDuplicateThread());
        addButton(layout, "多组线程正常使用 (不同线程名)", v -> createNormalMultipleDuplicateThread());

        addSectionTitle(layout, "🏠 Activity/Fragment正常使用");
        addButton(layout, "Activity正常使用", v -> createNormalActivity());
        addButton(layout, "Fragment正常使用", v -> createNormalFragment());

        addSectionTitle(layout, "🖼️ View/ViewModel/Service/Dialog正常使用");
        addButton(layout, "View正常使用", v -> createNormalView());
        addButton(layout, "ViewModel正常使用", v -> createNormalViewModel());
        addButton(layout, "Service正常使用", v -> createNormalService());
        addButton(layout, "Dialog正常使用", v -> createNormalDialog());
        addButton(layout, "Handler/Message正常使用", v -> createNormalHandlerMessage());
        addButton(layout, "BroadcastReceiver正常使用", v -> createNormalBroadcastReceiver());
        addButton(layout, "Animator正常使用", v -> createNormalAnimator());

        scrollView.addView(layout);
        setContentView(scrollView);

        // 处理Intent触发（用于自动化测试）
        handleIntentAction();
    }

    private void addSectionTitle(LinearLayout layout, String title) {
        TextView textView = new TextView(this);
        textView.setText(title);
        textView.setTextSize(16);
        textView.setTextColor(Color.rgb(0, 100, 200));
        textView.setPadding(0, 20, 0, 10);
        layout.addView(textView);
    }

    private void addButton(LinearLayout layout, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 5, 0, 5);
        layout.addView(button, params);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ==================== Bitmap正常使用 ====================

    private void createNormalBitmap() {
        // 创建Bitmap但不添加到静态列表，允许GC回收
        Bitmap bitmap = Bitmap.createBitmap(1440, 3200, Bitmap.Config.ARGB_8888);
        fillBitmap(bitmap, 0);
        // 显式设置为null，允许GC回收
        bitmap = null;
        // 触发GC（仅用于测试，实际应用中不需要）
        System.gc();
        // 不添加到静态列表，使用后允许GC回收
        showToast("创建Bitmap正常使用: ~18MB (不添加到静态列表，已释放)");
    }

    private void createNormalMultipleBitmap() {
        // 创建多个Bitmap但不添加到静态列表
        for (int i = 0; i < 10; i++) {
            Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            fillBitmap(bitmap, i);
            // 显式设置为null，允许GC回收
            bitmap = null;
        }
        // 触发GC（仅用于测试，实际应用中不需要）
        System.gc();
        showToast("创建10个Bitmap正常使用: ~80MB (不添加到静态列表，已释放)");
    }

    private void createNormalHugeBitmap() {
        // 创建超大Bitmap但不添加到静态列表
        Bitmap bitmap = Bitmap.createBitmap(2560, 2560, Bitmap.Config.ARGB_8888);
        fillBitmap(bitmap, 0);
        // 显式设置为null，允许GC回收
        bitmap = null;
        // 触发GC（仅用于测试，实际应用中不需要）
        System.gc();
        showToast("创建超大Bitmap正常使用: ~26MB (不添加到静态列表，已释放)");
    }

    private void fillBitmap(Bitmap bitmap, int index) {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(100 + index * 10, 150, 200));
        canvas.drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), paint);
    }

    // ==================== ByteArray正常使用 ====================

    private void createNormalByteArray() {
        // 创建ByteArray但不添加到静态列表
        for (int i = 0; i < 50; i++) {
            byte[] data = new byte[1024 * 1024]; // 1MB
            // 使用数据但不添加到静态列表
            data[0] = (byte) i;
            // 显式设置为null，允许GC回收
            data = null;
        }
        // 触发GC（仅用于测试，实际应用中不需要）
        System.gc();
        showToast("创建ByteArray正常使用: 50MB (不添加到静态列表，已释放)");
    }

    // ==================== 线程正常使用 ====================

    private void createNormalDuplicateThread() {
        // 使用不同的线程名，不创建同名线程
        for (int i = 0; i < 20; i++) {
            final int index = i;
            new Thread("WorkerThread-" + index) {
                @Override
                public void run() {
                    // 正常执行任务
                    Log.d(TAG, "WorkerThread-" + index + " running");
                }
            }.start();
        }
        showToast("创建20个不同线程名的线程 (正常使用)");
    }

    private void createNormalMultipleDuplicateThread() {
        // 使用不同的线程名，不创建同名线程
        String[] baseNames = {
            "OkHttp-Dispatcher",
            "Executor-Service",
            "AsyncTask",
            "Timer-Thread",
            "Connection-Thread"
        };

        for (String baseName : baseNames) {
            for (int i = 0; i < 10; i++) {
                final String threadName = baseName + "-" + i;
                new Thread(threadName) {
                    @Override
                    public void run() {
                        Log.d(TAG, threadName + " running");
                    }
                }.start();
            }
        }
        showToast("创建多组不同线程名的线程 (正常使用)");
    }

    // ==================== Activity正常使用 ====================

    private void createNormalActivity() {
        // 正常启动Activity，不持有静态引用
        Intent intent = new Intent(this, NormalActivity.class);
        startActivity(intent);
        showToast("正常启动Activity (不持有静态引用)");
    }

    public static class NormalActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            TextView textView = new TextView(this);
            textView.setText("正常Activity");
            setContentView(textView);
            
            // 正常finish，不持有静态引用
            new Handler(Looper.getMainLooper()).postDelayed(() -> finish(), 1000);
        }
    }

    // ==================== Fragment正常使用 ====================

    private void createNormalFragment() {
        // 正常启动Activity，在其中添加Fragment
        Intent intent = new Intent(this, NormalFragmentActivity.class);
        startActivity(intent);
        showToast("正常使用Fragment (不持有静态引用)");
    }

    public static class NormalFragmentActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            
            // 创建Fragment并正常添加到Activity
            NormalFragment fragment = new NormalFragment();
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment)
                    .commitAllowingStateLoss();
            
            // 正常finish，Fragment会正常移除，不持有静态引用
            new Handler(Looper.getMainLooper()).postDelayed(() -> finish(), 2000);
        }
    }

    public static class NormalFragment extends Fragment {
        @Override
        public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            TextView textView = new TextView(getActivity());
            textView.setText("正常Fragment");
            return textView;
        }
    }

    // ==================== View正常使用 ====================

    private void createNormalView() {
        // 正常启动Activity，在其中创建View并添加到View层次结构
        Intent intent = new Intent(this, NormalViewActivity.class);
        startActivity(intent);
        showToast("正常使用View (添加到Activity的View层次结构)");
    }

    public static class NormalViewActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            
            // 创建View并正常添加到Activity的View层次结构
            FrameLayout rootView = new FrameLayout(this);
            rootView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            setContentView(rootView);
            
            // 正常finish，View会正常移除，不持有静态引用
            new Handler(Looper.getMainLooper()).postDelayed(() -> finish(), 2000);
        }
    }

    // ==================== ViewModel正常使用 ====================

    private void createNormalViewModel() {
        // 正常创建和使用ViewModel，不调用markCleared后仍持有引用
        TestViewModel viewModel = new ViewModelProvider(this).get(TestViewModel.class);
        // 正常使用ViewModel，不添加到静态列表
        showToast("正常使用ViewModel (不添加到静态列表)");
    }

    public static class TestViewModel extends ViewModel {
        // 正常ViewModel，不调用markCleared
    }

    // ==================== Service正常使用 ====================

    private void createNormalService() {
        // 正常启动和停止Service，不调用markDestroyed后仍持有引用
        Intent intent = new Intent(this, TestService.class);
        startService(intent);
        
        // 正常停止Service
        handler.postDelayed(() -> {
            stopService(new Intent(this, TestService.class));
            showToast("正常使用Service (已停止，不添加到静态列表)");
        }, 1000);
    }

    public static class TestService extends Service {
        @Override
        public void onCreate() {
            super.onCreate();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            // 正常销毁，不添加到静态列表
        }

        @Override
        public android.os.IBinder onBind(Intent intent) {
            return null;
        }
    }

    // ==================== Dialog正常使用 ====================

    private void createNormalDialog() {
        // 创建Dialog并正常显示和关闭
        Dialog dialog = new Dialog(this);
        dialog.setTitle("Normal Dialog");
        dialog.setContentView(new TextView(this));
        
        // 正常显示
        dialog.show();
        
        // 正常关闭，不添加到静态列表
        handler.postDelayed(() -> {
            dialog.dismiss();
            showToast("正常使用Dialog (已关闭，不添加到静态列表)");
        }, 1000);
    }

    // ==================== Handler/Message正常使用 ====================

    private void createNormalHandlerMessage() {
        // 使用静态内部类Handler（不持有Activity引用）
        NormalHandler normalHandler = new NormalHandler();
        
        // 创建Message
        Message message = normalHandler.obtainMessage();
        message.what = 1;
        // 不设置obj为Activity引用
        
        // 发送到队列（60秒后执行）
        normalHandler.sendMessageDelayed(message, 60000);
        
        // 不添加到静态列表
        showToast("正常使用Handler/Message (静态内部类，不持有Activity引用)");
    }

    /**
     * 静态内部类Handler，不持有外部类（MainActivity）引用
     */
    static class NormalHandler extends Handler {
        public NormalHandler() {
            super(Looper.getMainLooper());
        }
        
        @Override
        public void handleMessage(Message msg) {
            // 静态内部类不持有外部类引用
        }
    }

    // ==================== BroadcastReceiver正常使用 ====================

    private BroadcastReceiver normalReceiver;
    
    private void createNormalBroadcastReceiver() {
        // 创建BroadcastReceiver
        normalReceiver = new NormalBroadcastReceiver();
        
        // 正常注册
        IntentFilter filter = new IntentFilter("com.koom.normal.TEST_ACTION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(normalReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(normalReceiver, filter);
        }
        
        // 正常注销，不添加到静态列表
        handler.postDelayed(() -> {
            if (normalReceiver != null) {
                unregisterReceiver(normalReceiver);
                normalReceiver = null;
                showToast("正常使用BroadcastReceiver (已注销，不添加到静态列表)");
            }
        }, 1000);
    }

    /**
     * 静态内部类BroadcastReceiver，不持有外部类引用
     */
    static class NormalBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 静态内部类不持有外部类引用
        }
    }

    // ==================== Animator正常使用 ====================

    private void createNormalAnimator() {
        // 创建View
        View view = new View(this);
        
        // 创建Animator，设置有限次数的重复（不无限循环）
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
        animator.setRepeatCount(0); // 不无限循环
        animator.setDuration(1000);
        animator.start();
        
        // 正确取消动画，不添加到静态列表
        handler.postDelayed(() -> {
            animator.cancel();
            showToast("正常使用Animator (已取消，不无限循环，不添加到静态列表)");
        }, 2000);
    }

    // ==================== Intent处理 ====================

    private void handleIntentAction() {
        Intent intent = getIntent();
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "handleIntentAction: action=" + action);

        switch (action) {
            case "com.koom.normal.action.BITMAP":
                createNormalBitmap();
                break;
            case "com.koom.normal.action.MULTIPLE_BITMAP":
                createNormalMultipleBitmap();
                break;
            case "com.koom.normal.action.HUGE_BITMAP":
                createNormalHugeBitmap();
                break;
            case "com.koom.normal.action.BYTEARRAY":
                createNormalByteArray();
                break;
            case "com.koom.normal.action.DUPLICATE_THREAD":
                createNormalDuplicateThread();
                break;
            case "com.koom.normal.action.MULTIPLE_DUPLICATE_THREAD":
                createNormalMultipleDuplicateThread();
                break;
            case "com.koom.normal.action.ACTIVITY":
                createNormalActivity();
                break;
            case "com.koom.normal.action.FRAGMENT":
                createNormalFragment();
                break;
            case "com.koom.normal.action.VIEW":
                createNormalView();
                break;
            case "com.koom.normal.action.VIEWMODEL":
                createNormalViewModel();
                break;
            case "com.koom.normal.action.SERVICE":
                createNormalService();
                break;
            case "com.koom.normal.action.DIALOG":
                createNormalDialog();
                break;
            case "com.koom.normal.action.HANDLER_MESSAGE":
                createNormalHandlerMessage();
                break;
            case "com.koom.normal.action.BROADCAST_RECEIVER":
                createNormalBroadcastReceiver();
                break;
            case "com.koom.normal.action.ANIMATOR":
                createNormalAnimator();
                break;
        }
    }
}
