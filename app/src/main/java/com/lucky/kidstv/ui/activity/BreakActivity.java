package com.lucky.kidstv.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.lucky.kidstv.R;
import com.lucky.kidstv.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.Random;

/**
 * 护眼休息页：全屏倒计时 + 退出需做一道 100 以内加减法（填空题，遥控器数字键输入）
 * 由 PlayActivity 在累计播放达到上限后启动；答对后 finish 并重置累计时间。
 */
public class BreakActivity extends Activity {
    private TextView tvCountdown, tvBreakTitle, tvBreakHint, tvQuizQuestion, tvQuizInput, tvQuizResult;
    private View llQuiz;
    private Handler mHandler = new Handler();
    private int totalSeconds;      // 休息总秒数
    private int remainSeconds;     // 剩余秒数
    private boolean quizMode = false;

    private int ansA, ansB, ansResult; // 题目 a ? b = result
    private boolean isAdd = true;
    private StringBuilder input = new StringBuilder();
    private Random random = new Random();

    private Runnable tick = new Runnable() {
        @Override
        public void run() {
            remainSeconds--;
            if (remainSeconds <= 0) {
                remainSeconds = 0;
                tvCountdown.setText("00:00");
                enterQuiz();
                return;
            }
            tvCountdown.setText(formatTime(remainSeconds));
            mHandler.postDelayed(tick, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_break);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvBreakTitle = findViewById(R.id.tvBreakTitle);
        tvBreakHint = findViewById(R.id.tvBreakHint);
        tvQuizQuestion = findViewById(R.id.tvQuizQuestion);
        tvQuizInput = findViewById(R.id.tvQuizInput);
        tvQuizResult = findViewById(R.id.tvQuizResult);
        llQuiz = findViewById(R.id.llQuiz);

        int breakMinutes = Hawk.get(HawkConfig.BREAK_MINUTES, 5);
        if (breakMinutes < 1) breakMinutes = 1;
        totalSeconds = breakMinutes * 60;
        remainSeconds = totalSeconds;
        tvCountdown.setText(formatTime(remainSeconds));
        mHandler.postDelayed(tick, 1000);
    }

    private String formatTime(int sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    // 倒计时结束：显示题目
    private void enterQuiz() {
        quizMode = true;
        llQuiz.setVisibility(View.VISIBLE);
        tvBreakTitle.setText("休息结束");
        tvBreakHint.setText("答对下面的题，就可以继续看啦");
        tvCountdown.setVisibility(View.GONE);
        generateQuiz();
    }

    private void generateQuiz() {
        isAdd = random.nextBoolean();
        if (isAdd) {
            ansA = random.nextInt(90) + 1;   // 1-90
            ansB = random.nextInt(100 - ansA) + 1; // 和 <= 100
            ansResult = ansA + ansB;
            tvQuizQuestion.setText(ansA + " + " + ansB + " = ?");
        } else {
            ansA = random.nextInt(90) + 10;  // 10-99
            ansB = random.nextInt(ansA);     // 0 ~ ansA-1，保证非负
            ansResult = ansA - ansB;
            tvQuizQuestion.setText(ansA + " - " + ansB + " = ?");
        }
        input.setLength(0);
        tvQuizInput.setText("");
        tvQuizResult.setText("");
    }

    private void appendDigit(int d) {
        if (input.length() >= 3) return; // 100 以内最多 3 位
        input.append(d);
        tvQuizInput.setText(input.toString());
    }

    private void backspace() {
        if (input.length() > 0) {
            input.deleteCharAt(input.length() - 1);
            tvQuizInput.setText(input.toString());
        }
    }

    private void submit() {
        if (input.length() == 0) {
            tvQuizResult.setText("请先输入答案");
            return;
        }
        int answer = Integer.parseInt(input.toString());
        if (answer == ansResult) {
            // 答对：重置累计时间并退出休息
            Hawk.put(HawkConfig.PLAY_ACCUM_SECONDS, 0);
            tvQuizResult.setText("✅ 回答正确！");
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, 800);
        } else {
            tvQuizResult.setText("❌ 不对哦，再试一次");
            input.setLength(0);
            tvQuizInput.setText("");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            // 数字键 0-9
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                if (quizMode) {
                    appendDigit(code - KeyEvent.KEYCODE_0);
                    return true;
                }
            } else if (code == KeyEvent.KEYCODE_NUMPAD_0 || code == KeyEvent.KEYCODE_NUMPAD_1 ||
                    code == KeyEvent.KEYCODE_NUMPAD_2 || code == KeyEvent.KEYCODE_NUMPAD_3 ||
                    code == KeyEvent.KEYCODE_NUMPAD_4 || code == KeyEvent.KEYCODE_NUMPAD_5 ||
                    code == KeyEvent.KEYCODE_NUMPAD_6 || code == KeyEvent.KEYCODE_NUMPAD_7 ||
                    code == KeyEvent.KEYCODE_NUMPAD_8 || code == KeyEvent.KEYCODE_NUMPAD_9) {
                if (quizMode) {
                    appendDigit(code - KeyEvent.KEYCODE_NUMPAD_0);
                    return true;
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE) {
                if (quizMode) {
                    submit();
                    return true;
                }
            } else if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_BACK) {
                if (quizMode && code == KeyEvent.KEYCODE_DEL) {
                    backspace();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // 倒计时期间按返回 = 提前结束休息（仍需答题）；答题期间按返回 = 删除一位
    @Override
    public void onBackPressed() {
        if (!quizMode) {
            enterQuiz();
        } else {
            backspace();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
