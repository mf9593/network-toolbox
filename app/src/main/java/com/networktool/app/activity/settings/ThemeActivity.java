package com.networktool.app.activity.settings;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.adapter.ThemeColorAdapter;
import com.networktool.app.model.ThemeColor;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 主题配色页面
 *
 * 预设颜色和随机主题均以卡片形式展示，点击后立即应用。
 * 随机主题每次点击都会生成新的颜色，并同步刷新预览、按钮和卡片选中态。
 */
public class ThemeActivity extends AppCompatActivity implements ThemeColorAdapter.OnColorClickListener {

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private ThemeColorAdapter adapter;
    private List<ThemeColor> colorList;
    private MaterialButton refreshRandomButton;
    private MaterialCardView currentThemeCard;
    private MaterialCardView currentColorSwatch;
    private TextView currentThemeName;
    private TextView currentThemeHex;
    private ThemeColor randomTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("主题配色");
        }

        initViews();
        applyToolbarTheme();
        setupStatusBar();
        loadColors();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int spanCount = screenWidthDp >= 600 ? 4 : (screenWidthDp >= 480 ? 3 : 2);
        recyclerView.setLayoutManager(new GridLayoutManager(this, spanCount));

        refreshRandomButton = findViewById(R.id.refreshRandomButton);
        currentThemeCard = findViewById(R.id.currentThemeCard);
        currentColorSwatch = findViewById(R.id.currentColorSwatch);
        currentThemeName = findViewById(R.id.currentThemeName);
        currentThemeHex = findViewById(R.id.currentThemeHex);

        refreshRandomButton.setOnClickListener(v -> applyRandomTheme());
    }

    private void applyToolbarTheme() {
        toolbar.setBackgroundColor(Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
    }

    /**
     * 加载预设颜色和随机主题卡片，并还原当前选中项。
     */
    private void loadColors() {
        colorList = new ArrayList<>();
        for (int i = 0; i < ThemeManager.PRESET_COLORS.length; i++) {
            int color = ThemeManager.PRESET_COLORS[i];
            int darkColor = ThemeManager.darkenColor(color, 0.85f);
            colorList.add(new ThemeColor(ThemeManager.PRESET_COLOR_NAMES[i], color, darkColor));
        }

        randomTheme = new ThemeColor("随机主题", true);
        randomTheme.setRandomColor(ThemeManager.getThemeColor());
        colorList.add(randomTheme);

        int selectedIndex = ThemeManager.getCurrentColorIndex();
        if (selectedIndex >= 0 && selectedIndex < ThemeManager.PRESET_COLORS.length) {
            colorList.get(selectedIndex).setSelected(true);
        } else {
            randomTheme.setSelected(true);
        }

        adapter = new ThemeColorAdapter(colorList, this);
        recyclerView.setAdapter(adapter);

        updateCurrentThemeSummary();
        updateRandomButton(ThemeManager.getThemeColor());
    }

    /**
     * 生成一个适合作为主题色的随机颜色。
     * 控制饱和度和明度，避免出现纯黑、纯白或低辨识度的颜色。
     */
    private int generateRandomColor() {
        Random random = new Random();
        int currentColor = ThemeManager.getThemeColor();
        int candidate = currentColor;
        for (int i = 0; i < 20; i++) {
            float hue = random.nextFloat() * 360f;
            float saturation = 0.58f + random.nextFloat() * 0.27f;
            float value = 0.68f + random.nextFloat() * 0.20f;
            candidate = Color.HSVToColor(new float[]{hue, saturation, value});
            if (candidate != currentColor
                    && colorDistance(candidate, currentColor) > 70
                    && !isPresetColor(candidate)) {
                break;
            }
        }
        return candidate;
    }

    private double colorDistance(int first, int second) {
        int red = Color.red(first) - Color.red(second);
        int green = Color.green(first) - Color.green(second);
        int blue = Color.blue(first) - Color.blue(second);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private boolean isPresetColor(int color) {
        for (int presetColor : ThemeManager.PRESET_COLORS) {
            if (presetColor == color) {
                return true;
            }
        }
        return false;
    }

    private void applyRandomTheme() {
        int color = generateRandomColor();
        applyTheme(color, true, "已生成随机主题 " + ThemeManager.colorToHex(color));
    }

    /**
     * 应用主题色并同步所有可见控件，保证按钮、预览和卡片选中状态一致。
     */
    private void applyTheme(int color, boolean selectRandom, String feedback) {
        ThemeManager.setThemeColor(color);

        for (ThemeColor item : colorList) {
            item.setSelected(false);
        }
        if (selectRandom) {
            randomTheme.setRandomColor(color);
            randomTheme.setSelected(true);
        } else {
            for (ThemeColor item : colorList) {
                if (!item.isRandom() && item.getColor() == color) {
                    item.setSelected(true);
                    break;
                }
            }
        }

        adapter.notifyDataSetChanged();
        applyToolbarTheme();
        setupStatusBar();
        updateCurrentThemeSummary();
        updateRandomButton(color);

        setResult(RESULT_OK, new Intent().putExtra("theme_changed", true));

        if (feedback != null && !feedback.isEmpty()) {
            Toast.makeText(this, feedback, Toast.LENGTH_SHORT).show();
            refreshRandomButton.announceForAccessibility(feedback);
        }
    }

    /**
     * 刷新顶部当前主题预览。
     */
    private void updateCurrentThemeSummary() {
        int color = ThemeManager.getThemeColor();
        int selectedIndex = ThemeManager.getCurrentColorIndex();
        String name = selectedIndex >= 0
                ? ThemeManager.PRESET_COLOR_NAMES[selectedIndex] : "随机主题";

        ThemeManager.setupResultCard(currentThemeCard, this);
        currentColorSwatch.setCardBackgroundColor(color);
        currentThemeName.setText(name);
        currentThemeHex.setText(ThemeManager.colorToHex(color));
        currentColorSwatch.setContentDescription("当前主题色 " + ThemeManager.colorToHex(color));
    }

    /**
     * 随机刷新按钮跟随当前主题色，文字和图标自动使用对比色。
     */
    private void updateRandomButton(int color) {
        int contrastColor = ThemeManager.getContrastColor(color);
        refreshRandomButton.setBackgroundTintList(ColorStateList.valueOf(color));
        refreshRandomButton.setTextColor(contrastColor);
        refreshRandomButton.setIconTint(ColorStateList.valueOf(contrastColor));
    }

    @Override
    public void onColorClick(ThemeColor themeColor, int position) {
        if (themeColor.isRandom()) {
            // 随机主题卡片不是“选中当前随机色”，而是每次点击都生成新颜色。
            applyRandomTheme();
            return;
        }

        applyTheme(themeColor.getColor(), false,
                "已应用「" + themeColor.getName() + "」");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
