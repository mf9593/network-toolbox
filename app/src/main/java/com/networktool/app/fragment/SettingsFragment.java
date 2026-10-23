package com.networktool.app.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.activity.settings.AboutActivity;
import com.networktool.app.activity.settings.ClearBrowserCacheActivity;
import com.networktool.app.activity.settings.DeviceInfoActivity;
import com.networktool.app.activity.settings.HelpActivity;
import com.networktool.app.activity.settings.PermissionManagerActivity;
import com.networktool.app.activity.settings.PrivacyPolicyActivity;
import com.networktool.app.activity.settings.ThemeActivity;
import com.networktool.app.activity.settings.UpdateLogActivity;
import com.networktool.app.activity.settings.UserAgreementActivity;
import com.networktool.app.activity.tool.WebViewActivity;
import com.networktool.app.adapter.SettingAdapter;
import com.networktool.app.BuildConfig;
import com.networktool.app.model.SettingItem;
import com.networktool.app.model.SettingSection;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.UpdateChecker;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：网络与权限、外观、系统、隐私与关于。
 */
public class SettingsFragment extends Fragment implements SettingAdapter.OnSettingClickListener {

    private RecyclerView recyclerView;
    private SettingAdapter adapter;
    private SettingItem themeModeItem;
    private static final int THEME_REQUEST_CODE = 100;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        setupSettings();
    }

    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setPadding(0, dp(4), 0, dp(16));
        recyclerView.setClipToPadding(false);
    }

    private void setupSettings() {
        List<SettingSection> sections = new ArrayList<>();

        List<SettingItem> permissionItems = new ArrayList<>();
        permissionItems.add(new SettingItem("权限管理", "查看并管理应用权限",
                R.drawable.ic_lock, R.drawable.ic_arrow_forward, this::onPermissionManager));
        sections.add(new SettingSection("网络与权限", permissionItems));

        List<SettingItem> appearanceItems = new ArrayList<>();
        themeModeItem = new SettingItem("深色模式", ThemeManager.getThemeModeLabel(),
                R.drawable.ic_dark_mode, R.drawable.ic_arrow_forward, this::onThemeMode);
        appearanceItems.add(themeModeItem);
        appearanceItems.add(new SettingItem("主题配色", "更换应用强调色",
                R.drawable.ic_palette, R.drawable.ic_arrow_forward, this::onTheme));
        sections.add(new SettingSection("外观", appearanceItems));

        List<SettingItem> systemItems = new ArrayList<>();
        systemItems.add(new SettingItem("设备信息", "型号、系统、版本与硬件信息",
                R.drawable.ic_device_info, R.drawable.ic_arrow_forward, this::onDeviceInfo));
        systemItems.add(new SettingItem("清理浏览器缓存", "释放内置浏览器占用空间",
                R.drawable.ic_browser, R.drawable.ic_arrow_forward, this::onClearBrowserCache));
        sections.add(new SettingSection("系统", systemItems));

        List<SettingItem> privacyItems = new ArrayList<>();
        privacyItems.add(new SettingItem("隐私政策", "了解本地数据处理方式",
                R.drawable.ic_privacy, R.drawable.ic_arrow_forward, this::onPrivacyPolicy));
        privacyItems.add(new SettingItem("用户协议", "使用本应用前请阅读",
                R.drawable.ic_agreement, R.drawable.ic_arrow_forward, this::onUserAgreement));
        sections.add(new SettingSection("数据与隐私", privacyItems));

        List<SettingItem> aboutItems = new ArrayList<>();
        aboutItems.add(new SettingItem("检查更新", "检查 GitHub 上的最新版本",
                R.drawable.ic_refresh, R.drawable.ic_arrow_forward, this::onCheckUpdate));
        aboutItems.add(new SettingItem("更新日志", "查看版本更新内容",
                R.drawable.ic_update_log, R.drawable.ic_arrow_forward, this::onUpdateLog));
        aboutItems.add(new SettingItem("使用帮助", "工具说明与常见问题",
                R.drawable.ic_help, R.drawable.ic_arrow_forward, this::onHelp));
        aboutItems.add(new SettingItem("反馈问题", "加入反馈群",
                R.drawable.ic_feedback, R.drawable.ic_arrow_forward, this::onFeedback));
        aboutItems.add(new SettingItem("关于软件", "版本、协议与开源信息",
                R.drawable.ic_about, R.drawable.ic_arrow_forward, this::onAbout));
        sections.add(new SettingSection("关于与帮助", aboutItems));

        adapter = new SettingAdapter(sections, this);
        recyclerView.setAdapter(adapter);
    }

    @Override
    public void onSettingClick(SettingItem setting) {
        if (setting.getClickListener() != null) {
            setting.getClickListener().onSettingClick(setting);
        }
    }

    private void onClearBrowserCache(SettingItem setting) {
        startActivity(new Intent(requireContext(), ClearBrowserCacheActivity.class));
    }

    private void onTheme(SettingItem setting) {
        startActivityForResult(new Intent(requireContext(), ThemeActivity.class), THEME_REQUEST_CODE);
    }

    private void onThemeMode(SettingItem setting) {
        int current = ThemeManager.getThemeMode();
        String[] modes = {"跟随系统", "浅色", "深色"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("深色模式")
                .setSingleChoiceItems(modes, current, (dialog, which) -> {
                    dialog.dismiss();
                    ThemeManager.setThemeMode(which);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void onPermissionManager(SettingItem setting) {
        startActivity(new Intent(requireContext(), PermissionManagerActivity.class));
    }

    private void onDeviceInfo(SettingItem setting) {
        startActivity(new Intent(requireContext(), DeviceInfoActivity.class));
    }

    private void onCheckUpdate(SettingItem setting) {
        if (!isAdded()) return;
        if (!UpdateChecker.isConfigured()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("尚未配置更新仓库")
                    .setMessage("当前 APK 还没有填写 GitHub 开源仓库地址。\n\n"
                            + "请在 gradle.properties 中设置 github.owner 和 github.repo 后重新构建。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show();
        UpdateChecker.check(requireContext(), new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info) {
                if (!isAdded()) return;
                if (info.newer) {
                    String notes = info.notes == null ? "" : info.notes.trim();
                    if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
                    String message = "当前版本：" + BuildConfig.VERSION_NAME
                            + "\n最新版本：" + info.version
                            + (notes.isEmpty() ? "" : "\n\n" + notes);
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("发现新版本")
                            .setMessage(message)
                            .setPositiveButton("打开发布页", (dialog, which) ->
                                    openInWebView(info.releaseUrl, "最新版本"))
                            .setNegativeButton("稍后", null)
                            .show();
                } else {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("检查更新")
                            .setMessage("当前已是最新版本\n\n版本号：" + BuildConfig.VERSION_NAME)
                            .setPositiveButton("知道了", null)
                            .show();
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("检查更新失败")
                        .setMessage(message)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    private void openInWebView(String url, String title) {
        try {
            if (url == null || url.isEmpty()) return;
            Intent intent = new Intent(requireContext(), WebViewActivity.class);
            intent.putExtra(WebViewActivity.EXTRA_URL, url);
            intent.putExtra(WebViewActivity.EXTRA_TITLE, title);
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开链接");
        }
    }

    private void onUpdateLog(SettingItem setting) {
        startActivity(new Intent(requireContext(), UpdateLogActivity.class));
    }

    private void onPrivacyPolicy(SettingItem setting) {
        startActivity(new Intent(requireContext(), PrivacyPolicyActivity.class));
    }

    private void onUserAgreement(SettingItem setting) {
        startActivity(new Intent(requireContext(), UserAgreementActivity.class));
    }

    private void onHelp(SettingItem setting) {
        startActivity(new Intent(requireContext(), HelpActivity.class));
    }

    private void onFeedback(SettingItem setting) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=559711374&card_type=group&source=qrcode"));
            startActivity(intent);
        } catch (Exception e) {
            showToast("没有安装 QQ");
        }
    }

    private void onAbout(SettingItem setting) {
        startActivity(new Intent(requireContext(), AboutActivity.class));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (themeModeItem != null) {
            themeModeItem.setSubtitle(ThemeManager.getThemeModeLabel());
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == THEME_REQUEST_CODE && data != null
                && data.getBooleanExtra("theme_changed", false)) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (getActivity() != null) {
                getActivity().setResult(1);
            }
        }
    }

    private void showToast(String message) {
        try {
            if (isAdded()) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
