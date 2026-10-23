package com.networktool.app.activity.tool;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 应用内文件选择器。
 *
 * 不经过系统 DocumentsUI，直接浏览共享存储中的备份文件，
 * 便于选择 .json、.xml、.bak 等系统选择器可能过滤掉的文件。
 */
public class WifiFilePickerActivity extends AppCompatActivity {

    public static final String EXTRA_START_DIR = "start_dir";
    public static final String EXTRA_FILE_PATH = "file_path";

    private static final String PREFS_NAME = "wifi_file_picker";
    private static final String KEY_LAST_DIR = "last_dir";

    private MaterialToolbar toolbar;
    private MaterialButton upButton;
    private MaterialButton rootPathButton;
    private MaterialButton downloadPathButton;
    private TextView pathText;
    private RecyclerView fileRecyclerView;
    private View emptyView;
    private TextView emptyText;

    private SharedPreferences preferences;
    private File rootDir;
    private File currentDir;
    private FileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_file_picker);

        setupStatusBar();
        setupToolbar();
        initViews();
        setupList();

        File start = resolveStartDirectory();
        loadDirectory(start);
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        upButton = findViewById(R.id.upButton);
        rootPathButton = findViewById(R.id.rootPathButton);
        downloadPathButton = findViewById(R.id.downloadPathButton);
        pathText = findViewById(R.id.pathText);
        fileRecyclerView = findViewById(R.id.fileRecyclerView);
        emptyView = findViewById(R.id.emptyView);
        emptyText = findViewById(R.id.emptyText);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        int accentColor = ThemeManager.getThemeColor();
        upButton.setTextColor(accentColor);
        upButton.setIconTint(ColorStateList.valueOf(accentColor));
        rootPathButton.setTextColor(accentColor);
        downloadPathButton.setTextColor(accentColor);
        pathText.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));
        emptyText.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));

        upButton.setOnClickListener(v -> {
            if (currentDir != null) {
                File parent = currentDir.getParentFile();
                if (parent != null && isInsideRoot(parent)) {
                    loadDirectory(parent);
                }
            }
        });
        rootPathButton.setOnClickListener(v -> openQuickPath(rootDir));
        downloadPathButton.setOnClickListener(v -> openQuickPath(
                new File(rootDir, "Download")));
    }

    private void openQuickPath(File dir) {
        if (dir != null && dir.isDirectory() && isInsideRoot(dir)) {
            loadDirectory(dir);
        } else {
            Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupList() {
        adapter = new FileAdapter();
        fileRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileRecyclerView.setAdapter(adapter);
    }

    private File resolveStartDirectory() {
        rootDir = Environment.getExternalStorageDirectory();
        if (rootDir == null) {
            rootDir = new File("/storage/emulated/0");
        }

        String requested = getIntent().getStringExtra(EXTRA_START_DIR);
        if (requested != null && !requested.trim().isEmpty()) {
            File preferred = new File(requested.trim());
            if (preferred.isDirectory() && isInsideRoot(preferred)) {
                return preferred;
            }
        }

        String last = preferences.getString(KEY_LAST_DIR, null);
        if (last != null) {
            File lastDir = new File(last);
            if (lastDir.isDirectory() && isInsideRoot(lastDir)) {
                return lastDir;
            }
        }

        // 默认从内部存储根目录打开；页面顶部提供 Download 快捷入口
        return rootDir;
    }

    private void loadDirectory(File dir) {
        if (dir == null) {
            return;
        }

        File target;
        try {
            target = dir.getCanonicalFile();
        } catch (Exception e) {
            target = dir;
        }

        if (!isInsideRoot(target)) {
            Toast.makeText(this, "不能访问该目录", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!target.isDirectory() || !target.canRead()) {
            currentDir = target;
            pathText.setText(formatPath(target));
            upButton.setEnabled(target.getParentFile() != null
                    && isInsideRoot(target.getParentFile()));
            adapter.setItems(Collections.emptyList());
            showEmpty("无法读取此目录，请检查文件访问权限");
            return;
        }

        currentDir = target;
        if (preferences != null) {
            preferences.edit().putString(KEY_LAST_DIR, target.getAbsolutePath()).apply();
        }
        pathText.setText(formatPath(target));
        File parent = target.getParentFile();
        upButton.setEnabled(parent != null && isInsideRoot(parent));

        File[] children = target.listFiles();
        if (children == null) {
            adapter.setItems(Collections.emptyList());
            showEmpty("无法读取此目录，请检查文件访问权限");
            return;
        }

        List<File> items = new ArrayList<>();
        for (File child : children) {
            if (child == null || child.getName().startsWith(".")) {
                continue;
            }
            items.add(child);
        }

        Collections.sort(items, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            return left.getName().compareToIgnoreCase(right.getName());
        });

        adapter.setItems(items);
        if (items.isEmpty()) {
            showEmpty("此目录没有可读取的文件");
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private boolean isInsideRoot(File file) {
        if (file == null || rootDir == null) {
            return false;
        }
        try {
            String path = file.getCanonicalPath();
            String root = rootDir.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (Exception e) {
            String path = file.getAbsolutePath();
            String root = rootDir.getAbsolutePath();
            return path.equals(root) || path.startsWith(root + File.separator);
        }
    }

    private String formatPath(File file) {
        String path = file.getAbsolutePath();
        String root = rootDir.getAbsolutePath();
        if (path.equals(root)) {
            return "内部存储";
        }
        if (path.startsWith(root + File.separator)) {
            return "内部存储/" + path.substring(root.length() + 1);
        }
        return path;
    }

    private void showEmpty(String message) {
        emptyText.setText(message);
        emptyView.setVisibility(View.VISIBLE);
    }

    private void returnFile(File file) {
        Intent result = new Intent();
        result.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (currentDir != null) {
            File parent = currentDir.getParentFile();
            if (parent != null && isInsideRoot(parent)) {
                loadDirectory(parent);
                return;
            }
        }
        super.onBackPressed();
    }

    private class FileAdapter extends RecyclerView.Adapter<FileViewHolder> {

        private final List<File> items = new ArrayList<>();

        void setItems(List<File> files) {
            items.clear();
            items.addAll(files);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_wifi_file_picker, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            File file = items.get(position);
            boolean directory = file.isDirectory();

            holder.name.setText(file.getName());
            holder.info.setText(directory ? "文件夹" : fileInfo(file));
            holder.icon.setImageResource(directory ? R.drawable.ic_folder : R.drawable.ic_file);
            int iconColor = directory
                    ? ThemeManager.getThemeColor()
                    : ThemeManager.getOnSurfaceVariantColor(WifiFilePickerActivity.this);
            holder.icon.setImageTintList(ColorStateList.valueOf(iconColor));
            holder.arrow.setImageTintList(ColorStateList.valueOf(
                    ThemeManager.getOnSurfaceVariantColor(WifiFilePickerActivity.this)));

            holder.card.setOnClickListener(v -> {
                if (directory) {
                    loadDirectory(file);
                } else {
                    returnFile(file);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private String fileInfo(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "文件";
        return extension + " · " + Formatter.formatFileSize(this, file.length());
    }

    private static class FileViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final ImageView arrow;
        final TextView name;
        final TextView info;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.fileCard);
            icon = itemView.findViewById(R.id.fileIcon);
            arrow = itemView.findViewById(R.id.fileArrow);
            name = itemView.findViewById(R.id.fileName);
            info = itemView.findViewById(R.id.fileInfo);
        }
    }
}
