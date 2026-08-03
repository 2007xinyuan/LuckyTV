package com.lucky.kidstv.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.lucky.kidstv.R;

/**
 * 开屏页：显示开屏图片 1 秒后进入首页
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION = 1000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mGotoHome = new Runnable() {
        @Override
        public void run() {
            Intent intent = new Intent(SplashActivity.this, HomeActivity.class);
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView ivSplash = findViewById(R.id.ivSplash);
        ivSplash.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        mHandler.postDelayed(mGotoHome, SPLASH_DURATION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mGotoHome);
    }
}
