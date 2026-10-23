package com.networktool.app.activity;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.networktool.app.util.ThemeManager;

/**
 * 二级页面基类
 *
 * 统一透明标题栏、状态栏、返回按钮和文字颜色。
 */
public abstract class BaseToolActivity extends AppCompatActivity {

    protected Toolbar toolbar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * 设置统一的透明 Toolbar
     */
    protected void setupToolbar(Toolbar toolbar, String title) {
        this.toolbar = toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title);
        }
        applyStandardToolbar();
    }

    /**
     * 标题栏与状态栏跟随系统明暗模式
     */
    protected void applyStandardToolbar() {
        if (toolbar != null) {
            toolbar.setBackgroundColor(Color.TRANSPARENT);
            int onSurface = ThemeManager.getOnSurfaceColor(this);
            toolbar.setTitleTextColor(onSurface);
            if (toolbar.getNavigationIcon() != null) {
                toolbar.getNavigationIcon().setTint(onSurface);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    /**
     * 应用主题色到按钮，并自动设置对比文字色
     */
    protected void applyThemeToButtons(int themeColor, MaterialButton... buttons) {
        for (MaterialButton button : buttons) {
            if (button != null) {
                ThemeManager.applyPrimaryButton(button);
            }
        }
    }

    /**
     * 应用主题色到文字
     */
    protected void applyThemeToTextViews(int themeColor, android.widget.TextView... textViews) {
        int accessible = ThemeManager.getAccessibleThemeColor(this);
        for (android.widget.TextView textView : textViews) {
            if (textView != null) {
                textView.setTextColor(accessible);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyStandardToolbar();
    }
}
