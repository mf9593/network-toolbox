package com.networktool.app.util;

import android.content.Context;

public class ScanSpeedConfig {
    private static final String PREFS_NAME = "app_settings";
    private static final String PREF_SCAN_SPEED = "scan_speed";
    public static final String SPEED_FAST = "fast";
    public static final String SPEED_STANDARD = "standard";
    public static final String SPEED_SLOW = "slow";

    public static String getScanSpeed(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SCAN_SPEED, SPEED_STANDARD);
    }

    public static int getThreadPoolSize(String speed) {
        switch (speed) {
            case SPEED_FAST: return 16;
            case SPEED_SLOW: return 4;
            default: return 8;
        }
    }

    public static int getPingConcurrency(String speed) {
        switch (speed) {
            case SPEED_FAST: return 64;
            case SPEED_SLOW: return 16;
            default: return 32;
        }
    }

    public static int getHttpConcurrency(String speed) {
        switch (speed) {
            case SPEED_FAST: return 32;
            case SPEED_SLOW: return 8;
            default: return 16;
        }
    }

    public static long getTimeoutSeconds(String speed) {
        switch (speed) {
            case SPEED_FAST: return 2;
            case SPEED_SLOW: return 5;
            default: return 3;
        }
    }
}
