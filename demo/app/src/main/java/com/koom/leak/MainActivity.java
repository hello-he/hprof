package com.koom.leak;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private List<Bitmap> leakedBitmaps = new ArrayList<>();
    private int leakCount = 0;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        statusText = new TextView(this);
        statusText.setText("Bitmap Leak Demo\n\n点击下方按钮创建Bitmap泄露");
        statusText.setTextSize(18);
        layout.addView(statusText);

        Button leakButton = new Button(this);
        leakButton.setText("创建Bitmap泄露 (1440x3200)");
        leakButton.setOnClickListener(v -> createLeak());
        layout.addView(leakButton);

        Button multiLeakButton = new Button(this);
        multiLeakButton.setText("创建10个Bitmap泄露");
        multiLeakButton.setOnClickListener(v -> createMultipleLeaks());
        layout.addView(multiLeakButton);

        Button hugeLeakButton = new Button(this);
        hugeLeakButton.setText("创建超大Bitmap (2560x2560)");
        hugeLeakButton.setOnClickListener(v -> createHugeLeak());
        layout.addView(hugeLeakButton);

        setContentView(layout);
    }

    private void createLeak() {
        // 创建1440x3200的大Bitmap（常见手机分辨率）
        Bitmap bitmap = Bitmap.createBitmap(1440, 3200, Bitmap.Config.ARGB_8888);

        // 填充内容确保真实分配内存
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(leakCount * 20 % 256, 100, 200));
        canvas.drawPaint(paint);

        // 保存到静态集合，造成泄露
        leakedBitmaps.add(bitmap);
        leakCount++;

        updateStatus();
    }

    private void createMultipleLeaks() {
        for (int i = 0; i < 10; i++) {
            Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            paint.setColor(Color.rgb(200, (leakCount + i) * 15 % 256, 100));
            canvas.drawPaint(paint);
            leakedBitmaps.add(bitmap);
        }
        leakCount += 10;
        updateStatus();
    }

    private void createHugeLeak() {
        // 创建超大Bitmap
        Bitmap bitmap = Bitmap.createBitmap(2560, 2560, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(leakCount * 30 % 256, 50, 150));
        canvas.drawPaint(paint);

        leakedBitmaps.add(bitmap);
        leakCount++;

        updateStatus();
    }

    private void updateStatus() {
        long totalPixels = 0;
        for (Bitmap bmp : leakedBitmaps) {
            totalPixels += bmp.getWidth() * bmp.getHeight();
        }
        long totalMB = totalPixels * 4 / (1024 * 1024);

        statusText.setText("Bitmap Leak Demo\n\n" +
                "已泄露Bitmap数量: " + leakedBitmaps.size() + "\n" +
                "总占用内存: " + totalMB + " MB\n\n" +
                "继续点击按钮增加泄露...");
    }
}
