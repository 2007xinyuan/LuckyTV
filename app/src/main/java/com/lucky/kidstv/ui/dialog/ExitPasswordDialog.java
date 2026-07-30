package com.lucky.kidstv.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.lucky.kidstv.R;

import org.jetbrains.annotations.NotNull;

public class ExitPasswordDialog extends BaseDialog {

    private EditText etPassword;
    private TextView tvError;
    private OnPasswordListener listener;

    public ExitPasswordDialog(@NonNull @NotNull Context context, OnPasswordListener listener) {
        super(context);
        this.listener = listener;
        setContentView(R.layout.dialog_exit_password);
        setCanceledOnTouchOutside(false);

        etPassword = findViewById(R.id.etPassword);
        tvError = findViewById(R.id.tvError);
        TextView btnConfirm = findViewById(R.id.btnConfirm);
        TextView btnCancel = findViewById(R.id.btnCancel);

        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pwd = etPassword.getText().toString().trim();
                if ("2025".equals(pwd)) {
                    dismiss();
                    if (ExitPasswordDialog.this.listener != null) {
                        ExitPasswordDialog.this.listener.onCorrect();
                    }
                } else {
                    tvError.setVisibility(View.VISIBLE);
                    etPassword.setText("");
                    etPassword.requestFocus();
                }
            }
        });

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (ExitPasswordDialog.this.listener != null) {
                    ExitPasswordDialog.this.listener.onCancel();
                }
            }
        });
    }

    public interface OnPasswordListener {
        void onCorrect();
        void onCancel();
    }
}
