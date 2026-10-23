package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.widget.Toast;

import com.networktool.app.activity.BaseToolActivity;

/**
 * ONVIF视频配置Activity（Stub实现）
 * ONVIF库暂不可用，功能暂时禁用
 */
public class OnvifVideoConfigActivity extends BaseToolActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("视频配置（暂不可用）");
        Toast.makeText(this, "ONVIF视频配置功能暂不可用，请使用基础视频配置", Toast.LENGTH_LONG).show();
        finish();
    }
}