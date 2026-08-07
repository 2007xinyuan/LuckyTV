package com.lucky.kidstv.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lucky.kidstv.R;
import com.lucky.kidstv.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.Random;

/**
 * 护眼休息页：全屏倒计时。
 * - 倒计时自然结束 → 直接继续播放（无需答题）
 * - 倒计时中点返回/OK 想提前继续 → 必须答对一道 100 以内加减法数学题才放行；
 *   答题中按返回 = 取消答题回到倒计时（继续休息）
 * finish 时重置累计播放时间并记录冷却起点，防止刚休息完立刻又来一轮。
 */
public class BreakActivity extends Activity {
    private TextView tvCountdown, tvBreakTitle, tvBreakHint;
    private LinearLayout llQuiz;
    private TextView tvQuizQuestion, tvQuizInput, tvQuizHint;
    private Handler mHandler = new Handler();
    private int totalSeconds;      // 休息总秒数
    private int remainSeconds;     // 剩余秒数
    private boolean quizShown = false;   // 是否进入答题模式
    private int quizA, quizB;            // 题目两个数
    private boolean quizAdd;             // true=加法 false=减法
    private int quizAnswer;              // 正确答案
    private StringBuilder quizInput = new StringBuilder();
    private Random mRandom = new Random();

    private Runnable tick = new Runnable() {
        @Override
        public void run() {
            remainSeconds--;
            if (remainSeconds <= 0) {
                remainSeconds = 0;
                tvCountdown.setText("00:00");
                finishBreak();
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
        llQuiz = findViewById(R.id.llQuiz);
        tvQuizQuestion = findViewById(R.id.tvQuizQuestion);
        tvQuizInput = findViewById(R.id.tvQuizInput);
        tvQuizHint = findViewById(R.id.tvQuizHint);

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

    // 休息结束（倒计时走完或答对题）：重置累计播放时间、记录冷却起点并返回播放页
    private void finishBreak() {
        Hawk.put(HawkConfig.PLAY_ACCUM_SECONDS, 0);
        Hawk.put(HawkConfig.LAST_BREAK_END_TS, System.currentTimeMillis());
        finish();
    }

    // ===== 答题模式 =====

    // 进入答题模式：暂停倒计时，生成 100 以内加减法题目
    private void showQuiz() {
        quizShown = true;
        mHandler.removeCallbacksAndMessages(null); // 暂停倒计时
        tvBreakTitle.setVisibility(View.GONE);
        tvBreakHint.setText("答对数学题才能提前继续播放，答错重新出题");
        llQuiz.setVisibility(View.VISIBLE);
        newQuiz();
    }

    // 取消答题：回到倒计时继续休息
    private void cancelQuiz() {
        quizShown = false;
        llQuiz.setVisibility(View.GONE);
        tvBreakTitle.setVisibility(View.VISIBLE);
        tvBreakHint.setText("休息一下，让眼睛放松放松");
        mHandler.postDelayed(tick, 1000); // 恢复倒计时
    }

    // 生成新题目：100 以内加减法（加法结果≤99，减法结果≥1）
    private void newQuiz() {
        quizInput.setLength(0);
        if (mRandom.nextBoolean()) {
            quizAdd = true;
            quizA = 10 + mRandom.nextInt(80);            // 10..89
            quizB = 1 + mRandom.nextInt(99 - quizA);     // 保证和≤99
            quizAnswer = quizA + quizB;
        } else {
            quizAdd = false;
            quizA = 20 + mRandom.nextInt(80);            // 20..99
            quizB = 1 + mRandom.nextInt(quizA - 1);      // 保证差≥1
            quizAnswer = quizA - quizB;
        }
        tvQuizQuestion.setText(quizA + (quizAdd ? " + " : " - ") + quizB + " = ?");
        updateQuizInputView();
    }

    private void updateQuizInputView() {
        if (quizInput.length() == 0) {
            tvQuizInput.setText("______");
        } else {
            tvQuizInput.setText(quizInput.toString());
        }
    }

    // 校验答案
    private void checkAnswer() {
        if (quizInput.length() == 0) return;
        try {
            int answer = Integer.parseInt(quizInput.toString());
            if (answer == quizAnswer) {
                Toast.makeText(this, "回答正确，休息结束，继续播放", Toast.LENGTH_SHORT).show();
                finishBreak();
            } else {
                Toast.makeText(this, "回答错误，重新出一道题", Toast.LENGTH_SHORT).show();
                newQuiz();
            }
        } catch (NumberFormatException e) {
            newQuiz();
        }
    }

    // 倒计时期间按返回/OK = 进入答题；答题中按返回 = 取消答题回倒计时
    @Override
    public void onBackPressed() {
        if (quizShown) {
            cancelQuiz();
        } else {
            showQuiz();
        }
    }

    // 遥控器按键：数字输入 / OK 确认 / 返回取消
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (quizShown) {
                // 答题模式
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    int digit = code - KeyEvent.KEYCODE_0;
                    appendDigit(digit);
                    return true;
                }
                if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
                    int digit = code - KeyEvent.KEYCODE_NUMPAD_0;
                    appendDigit(digit);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DEL) {
                    if (quizInput.length() > 0) {
                        quizInput.deleteCharAt(quizInput.length() - 1);
                        updateQuizInputView();
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE) {
                    checkAnswer();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BACK) {
                    cancelQuiz();
                    return true;
                }
                return true; // 答题模式下吞掉其他键，防止误触
            } else {
                // 倒计时模式：返回/OK/确认 = 尝试提前结束（需答题）
                if (code == KeyEvent.KEYCODE_BACK) {
                    showQuiz();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE ||
                        code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    showQuiz();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void appendDigit(int digit) {
        // 答案最多 3 位（100 以内），防止超长
        if (quizInput.length() >= 3) return;
        quizInput.append(digit);
        updateQuizInputView();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
