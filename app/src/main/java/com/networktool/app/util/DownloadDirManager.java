package com.networktool.app.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * 下载目录管理工具
 *
 * 管理文件下载路径，默认在 Download/网络工具箱 目录下
 */
public class DownloadDirManager {

    private static final String TAG = "DownloadDirManager";
    private static final String DOWNLOAD_DIR_NAME = "网络工具箱";

    private Context context;
    private static DownloadDirManager instance;

    private DownloadDirManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 获取单例实例
     * @param context 上下文
     * @return DownloadDirManager 实例
     */
    public static synchronized DownloadDirManager getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadDirManager(context);
        }
        return instance;
    }

    /**
     * 获取下载目录
     * 如果目录不存在则创建
     * @return 下载目录 File 对象
     */
    public File getDownloadDir() {
        File downloadDir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOAD_DIR_NAME
        );

        if (!downloadDir.exists()) {
            boolean created = downloadDir.mkdirs();
            if (created) {
                Log.d(TAG, "Download directory created: " + downloadDir.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create download directory");
            }
        }

        return downloadDir;
    }

    /**
     * 获取图片下载目录
     * @return 图片目录 File 对象
     */
    public File getImageDir() {
        File imageDir = new File(getDownloadDir(), "images");
        if (!imageDir.exists()) {
            imageDir.mkdirs();
        }
        return imageDir;
    }

    /**
     * 获取文件下载目录
     * @return 文件目录 File 对象
     */
    public File getFileDir() {
        File fileDir = new File(getDownloadDir(), "files");
        if (!fileDir.exists()) {
            fileDir.mkdirs();
        }
        return fileDir;
    }

    /**
     * 生成唯一的文件名
     * @param prefix 文件名前缀
     * @param extension 文件扩展名
     * @return 唯一文件名
     */
    public String generateUniqueFileName(String prefix, String extension) {
        long timestamp = System.currentTimeMillis();
        return prefix + "_" + timestamp + "." + extension;
    }

    /**
     * 清理下载目录
     */
    public void cleanDownloadDir() {
        File downloadDir = getDownloadDir();
        if (downloadDir.exists() && downloadDir.isDirectory()) {
            File[] files = downloadDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteFile(file);
                }
            }
        }
    }

    /**
     * 删除文件
     * @param file 要删除的文件
     */
    private void deleteFile(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteFile(f);
                }
            }
            file.delete();
        } else {
            file.delete();
        }
    }
}
