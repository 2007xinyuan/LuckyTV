package com.lucky.kidstv.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.lucky.kidstv.R;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
    }
}