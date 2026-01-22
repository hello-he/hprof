package com.koom.leak;

import android.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 用于测试Fragment泄露的Fragment
 */
public class TestFragment extends Fragment {

    private static final String TAG = "TestFragment";
    private Bitmap leakedBitmap;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        TextView textView = new TextView(getActivity());
        textView.setText("这是测试Fragment");
        textView.setTextSize(20);
        textView.setTextColor(Color.BLACK);
        layout.addView(textView);

        TextView infoText = new TextView(getActivity());
        infoText.setText("此Fragment会被静态引用持有，\n造成内存泄露");
        infoText.setTextSize(14);
        infoText.setPadding(0, 20, 0, 20);
        layout.addView(infoText);

        // 创建一个大Bitmap，增加内存占用，便于检测
        leakedBitmap = Bitmap.createBitmap(1440, 3200, Bitmap.Config.ARGB_8888);
        leakedBitmap.eraseColor(Color.rgb(200, 100, 100));

        return layout;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 注意：leakedBitmap没有被释放，Fragment被静态引用持有
    }
}
