package com.zeno.launcher;

import java.util.ArrayList;
import java.util.UUID;

final class HomeItem {
    static final String TYPE_APP = "app";
    static final String TYPE_FOLDER = "folder";
    static final String TYPE_WIDGET = "widget";
    static final String TYPE_ALL_APPS = "all_apps";

    String id = UUID.randomUUID().toString();
    String type = TYPE_APP;
    String label = "";
    String packageName = "";
    String componentName = "";
    int page = 0;
    float x = 0.5f;
    float y = 0.5f;
    int widthDp = 92;
    int heightDp = 110;
    int appWidgetId = -1;
    final ArrayList<String> folderPackages = new ArrayList<>();
}
