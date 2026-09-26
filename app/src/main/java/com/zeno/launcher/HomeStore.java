package com.zeno.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class HomeStore {
    private static final String PREFS = "zeno_home_store";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SEEDED = "seeded";

    private final SharedPreferences prefs;

    HomeStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<HomeItem> load() {
        ArrayList<HomeItem> result = new ArrayList<>();
        String raw = prefs.getString(KEY_ITEMS, "[]");
        if (raw == null || raw.trim().isEmpty()) return result;

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;

                HomeItem item = new HomeItem();
                item.id = json.optString("id", item.id);
                item.type = json.optString("type", HomeItem.TYPE_APP);
                item.label = json.optString("label", "");
                item.packageName = json.optString("packageName", "");
                item.componentName = json.optString("componentName", "");
                item.page = Math.max(0, json.optInt("page", 0));
                item.x = (float) json.optDouble("x", 0.5d);
                item.y = (float) json.optDouble("y", 0.5d);
                item.widthDp = Math.max(1, json.optInt("widthDp", 92));
                item.heightDp = Math.max(1, json.optInt("heightDp", 110));
                item.appWidgetId = json.optInt("appWidgetId", -1);

                JSONArray packages = json.optJSONArray("folderPackages");
                if (packages != null) {
                    for (int p = 0; p < packages.length(); p++) {
                        String pkg = packages.optString(p, "");
                        if (!pkg.isEmpty() && !item.folderPackages.contains(pkg)) {
                            item.folderPackages.add(pkg);
                        }
                    }
                }

                result.add(item);
            }
        } catch (Exception ignored) {
            // Si une ancienne sauvegarde est illisible, Zeno repart avec un accueil vide
            // au lieu de bloquer le lancement du launcher.
        }
        return result;
    }

    void save(List<HomeItem> items) {
        JSONArray array = new JSONArray();
        if (items != null) {
            for (HomeItem item : items) {
                if (item == null) continue;
                try {
                    JSONObject json = new JSONObject();
                    json.put("id", item.id);
                    json.put("type", item.type);
                    json.put("label", item.label);
                    json.put("packageName", item.packageName);
                    json.put("componentName", item.componentName);
                    json.put("page", item.page);
                    json.put("x", item.x);
                    json.put("y", item.y);
                    json.put("widthDp", item.widthDp);
                    json.put("heightDp", item.heightDp);
                    json.put("appWidgetId", item.appWidgetId);

                    JSONArray packages = new JSONArray();
                    for (String pkg : item.folderPackages) {
                        if (pkg != null && !pkg.isEmpty()) packages.put(pkg);
                    }
                    json.put("folderPackages", packages);
                    array.put(json);
                } catch (Exception ignored) {
                }
            }
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    HomeItem find(List<HomeItem> items, String id) {
        if (items == null || id == null) return null;
        for (HomeItem item : items) {
            if (item != null && id.equals(item.id)) return item;
        }
        return null;
    }

    void remove(List<HomeItem> items, String id) {
        if (items == null || id == null) return;
        Iterator<HomeItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            HomeItem item = iterator.next();
            if (item != null && id.equals(item.id)) {
                iterator.remove();
                return;
            }
        }
    }

    void markSeeded() {
        prefs.edit().putBoolean(KEY_SEEDED, true).apply();
    }
}
