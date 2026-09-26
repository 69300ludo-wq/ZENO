package com.zeno.launcher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

final class AppInfo {
    final String label;
    final String packageName;
    final ComponentName componentName;
    final Drawable icon;

    AppInfo(String label, String packageName, ComponentName componentName, Drawable icon) {
        this.label = label == null ? "" : label;
        this.packageName = packageName == null ? "" : packageName;
        this.componentName = componentName;
        this.icon = icon;
    }
}
