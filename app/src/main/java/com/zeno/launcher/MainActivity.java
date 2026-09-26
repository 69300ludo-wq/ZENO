package com.zeno.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.app.SearchManager;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    // ZENO FINAL BASE 1 : architecture launcher + Zeno vivant + tiroir isolé.
    private static final int APPWIDGET_HOST_ID = 7341;
    private static final int REQ_BIND_WIDGET = 4201;
    private static final int REQ_CONFIG_WIDGET = 4202;
    private static final int REQ_ZENO_SPEECH = 4301;

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<HomeItem> homeItems = new ArrayList<>();

    // PERFORMANCE V1 : évite de rescanner toutes les applications à chaque retour dans Zeno.
    private long lastAppsLoadMs = 0L;
    private static final long APPS_REFRESH_INTERVAL_MS = 60_000L;

    private PackageManager packageManager;
    private SharedPreferences settings;
    private HomeStore homeStore;
    private FrameLayout root;
    private FrameLayout stage;
    private FrameLayout currentHomePageView;
    private AppWidgetManager appWidgetManager;
    private AppWidgetHost appWidgetHost;
    private TextToSpeech zenoTts;

    private int currentPage = 0;
    private int pendingWidgetId = -1;
    private boolean drawerOpen = false;
    private boolean editMode = false;
    private boolean preferencesOpen = false;
    private boolean hideAppsOpen = false;

    // ZENO VIVANT V1 : robot léger sur l'écran d'accueil.
    private View zenoRobotView;
    private final Handler zenoHandler = new Handler(Looper.getMainLooper());
    private boolean zenoAnimationRunning = false;
    // ZENO V3 : mode repos/reveil, sans animation lourde permanente.
    private boolean zenoSleeping = false;
    private long zenoLastInteractionMs = 0L;
    private static final long ZENO_IDLE_SLEEP_MS = 10_000L; // ZENO FINAL TEST : repos visible rapidement
    private Runnable zenoSleepCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // V2 FIX : portrait uniquement pendant la phase de stabilisation.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        packageManager = getPackageManager();
        settings = getSharedPreferences("zeno_settings", MODE_PRIVATE);
        homeStore = new HomeStore(this);
        homeItems.addAll(homeStore.load());

        appWidgetManager = AppWidgetManager.getInstance(this);
        appWidgetHost = new AppWidgetHost(this, APPWIDGET_HOST_ID);
        zenoTts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) zenoTts.setLanguage(Locale.FRENCH);
        });

        loadApps(true);
        // ECRAN_ACCUEIL reste vide au premier lancement : aucun remplissage automatique.

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                stage.setPadding(0, bars.top, 0, bars.bottom);
            } else {
                stage.setPadding(
                        0,
                        insets.getSystemWindowInsetTop(),
                        0,
                        insets.getSystemWindowInsetBottom()
                );
            }
            return insets;
        });

        setContentView(root);
        renderHome(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            appWidgetHost.startListening();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        zenoAnimationRunning = false;
        zenoHandler.removeCallbacksAndMessages(null);
        zenoSleepCheck = null;
        try {
            appWidgetHost.stopListening();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps(false);

        // ZENO FINAL : Android peut suspendre les animations lorsque l’écran s’éteint.
        // Au retour après empreinte/PIN, on reconstruit uniquement l’accueil si nécessaire
        // et on redémarre proprement la vie de Zeno. Le tiroir reste totalement isolé.
        if (!drawerOpen && !preferencesOpen && !hideAppsOpen && !editMode) {
            if (zenoRobotView == null || !zenoAnimationRunning) {
                renderHome(0);
            } else {
                zenoSleeping = false;
                zenoLastInteractionMs = SystemClock.elapsedRealtime();
                zenoRobotView.animate().cancel();
                zenoRobotView.setAlpha(1f);
                zenoRobotView.setScaleX(1f);
                zenoRobotView.setScaleY(1f);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if(zenoTts!=null){ zenoTts.stop(); zenoTts.shutdown(); }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (hideAppsOpen) {
            hideAppsOpen = false;
            showPreferences();
            return;
        }
        if (preferencesOpen) {
            preferencesOpen = false;
            renderHome(0);
            return;
        }
        if (drawerOpen) {
            drawerOpen = false;
            renderHome(0);
            return;
        }
        if (editMode) {
            editMode = false;
            renderHome(0);
            return;
        }
        // Un launcher reste sur l'accueil lorsque l'utilisateur appuie sur Retour.
    }

    private void seedReferenceHomeIfNeeded() {
        // Une seule migration : crée un accueil 5 colonnes proche de la référence.
        // Les boutons Android triangle / cercle / carré restent entièrement gérés
        // par le téléphone et ne sont jamais dessinés par Zeno.
        if (settings.getBoolean("reference_home_layout_v1", false)) return;

        homeItems.clear();

        // Ligne 1
        addReferenceFolder("Google", 0, 0,
                "Gmail", "Google Maps", "Maps", "Google Photos", "Photos", "Drive", "Google");
        addReferenceApp(0, 1, "Play Store", "Google Play Store");
        addReferenceApp(0, 2, "Contacts");
        addReferenceApp(0, 3, "Téléphone", "Phone");
        addReferenceApp(0, 4, "Messages");

        // Ligne 2
        addReferenceFolder("Outil", 1, 0,
                "Calculatrice", "Boussole", "Sécurité", "Fichiers", "Gestionnaire de fichiers", "Météo");
        addReferenceFolder("Images", 1, 1,
                "Galerie", "Photos", "Mi Vidéo", "Appareil photo", "Camera");
        addReferenceApp(1, 2, "Téléchargements", "Téléchargement", "Downloads");
        addReferenceApp(1, 3, "Magnéto", "Enregistreur", "Recorder");
        addReferenceApp(1, 4, "YouTube");

        // Ligne 3
        addReferenceApp(2, 0, "Paramètres", "Settings");
        addReferenceApp(2, 1, "Google");
        addReferenceApp(2, 2, "Appareil photo", "Camera");
        addReferenceApp(2, 3, "Horloge", "Clock");
        addReferenceApp(2, 4, "Lecteur de musique", "Musique", "Music");

        // Ligne 4 : dossiers
        addReferenceFolder("Loisirs", 3, 0,
                "Netflix", "Prime Video", "Disney+", "YouTube");
        addReferenceFolder("Musique", 3, 1,
                "Spotify", "Shazam", "YouTube Music", "Musique", "Music");
        addReferenceFolder("Social", 3, 2,
                "Facebook", "Messenger", "Discord", "Snapchat", "WhatsApp");
        addReferenceFolder("logiciel", 3, 3,
                "LinkedIn", "Drive", "Fichiers", "Scanner", "ApowerMirror");
        addReferenceFolder("Jeux", 3, 4,
                "Centre de jeux", "Game Center", "Call of Duty", "Top Fishing");

        // Ligne 5
        addReferenceApp(4, 0, "RF ONLINE NEXT", "RF Online");
        addReferenceApp(4, 1, "Gunship Battle");
        addReferenceApp(4, 2, "Top Fishing");
        addReferenceApp(4, 3, "Zeno Launcher", "Zeno");

        homeStore.save(homeItems);
        homeStore.markSeeded();
        settings.edit()
                .putInt("home_pages", 1)
                .putBoolean("reference_home_layout_v1", true)
                .apply();
    }

    private void addReferenceApp(int row, int column, String... names) {
        AppInfo app = findAppByNames(names);
        if (app == null) return;

        HomeItem item = new HomeItem();
        item.type = HomeItem.TYPE_APP;
        item.label = app.label;
        item.packageName = app.packageName;
        item.componentName = app.componentName.flattenToString();
        item.page = 0;
        setReferenceGridPosition(item, row, column);
        homeItems.add(item);
    }

    private void addReferenceFolder(String label, int row, int column, String... memberNames) {
        HomeItem folder = new HomeItem();
        folder.type = HomeItem.TYPE_FOLDER;
        folder.label = label;
        folder.page = 0;
        setReferenceGridPosition(folder, row, column);

        for (String name : memberNames) {
            AppInfo app = findAppByNames(name);
            if (app != null && !folder.folderPackages.contains(app.packageName)) {
                folder.folderPackages.add(app.packageName);
            }
            if (folder.folderPackages.size() >= 4) break;
        }

        homeItems.add(folder);
    }

    private void setReferenceGridPosition(HomeItem item, int row, int column) {
        // 5 colonnes, avec une zone libre en haut pour la barre de recherche.
        float[] xs = {0f, 0.25f, 0.50f, 0.75f, 1f};
        float[] ys = {0.10f, 0.285f, 0.47f, 0.655f, 0.84f};
        item.x = xs[Math.max(0, Math.min(4, column))];
        item.y = ys[Math.max(0, Math.min(4, row))];
        item.widthDp = 76;
        item.heightDp = 92;
    }

    private AppInfo findAppByNames(String... names) {
        if (names == null) return null;

        // D'abord une égalité exacte, puis une recherche partielle.
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            for (AppInfo app : allApps) {
                if (app.label.equalsIgnoreCase(name.trim())) return app;
            }
        }

        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            String needle = name.trim().toLowerCase(Locale.getDefault());
            for (AppInfo app : allApps) {
                if (app.label.toLowerCase(Locale.getDefault()).contains(needle)) return app;
            }
        }
        return null;
    }

    private void loadApps() {
        loadApps(false);
    }

    private void loadApps(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && !allApps.isEmpty() && (now - lastAppsLoadMs) < APPS_REFRESH_INTERVAL_MS) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolves;
        try {
            resolves = packageManager.queryIntentActivities(intent, 0);
        } catch (Exception e) {
            resolves = new ArrayList<>();
        }

        List<AppInfo> fresh = new ArrayList<>();
        for (ResolveInfo r : resolves) {
            if (r.activityInfo == null) continue;
            CharSequence cs = r.loadLabel(packageManager);
            String label = cs == null ? r.activityInfo.packageName : cs.toString();
            ComponentName component = new ComponentName(
                    r.activityInfo.packageName,
                    r.activityInfo.name
            );
            Drawable icon;
            try {
                icon = r.loadIcon(packageManager);
            } catch (Exception e) {
                icon = getDrawable(android.R.drawable.sym_def_app_icon);
            }
            fresh.add(new AppInfo(label, r.activityInfo.packageName, component, icon));
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        fresh.sort((a, b) -> collator.compare(a.label, b.label));

        allApps.clear();
        allApps.addAll(fresh);
        lastAppsLoadMs = SystemClock.elapsedRealtime();
    }

    private void renderHome(int direction) {
        // Repartir d'un etat propre avant de recreer Zeno sur l'accueil.
        zenoAnimationRunning = false;
        zenoSleeping = false;
        zenoHandler.removeCallbacksAndMessages(null);
        zenoSleepCheck = null;
        zenoRobotView = null;
        // L'accueil peut afficher le fond d'ecran ; le tiroir, lui, repasse le root en noir opaque.
        root.setBackgroundColor(Color.TRANSPARENT);
        stage.setBackgroundColor(Color.TRANSPARENT);
        drawerOpen = false;
        preferencesOpen = false;
        hideAppsOpen = false;
        stage.removeAllViews();

        // Sur l'accueil, on laisse de nouveau apparaître le fond d'écran.
        getWindow().setStatusBarColor(Color.BLACK);
        // Triangle / cercle / carré : navigation Android normale du téléphone.
        // Zeno ne les dessine pas et ne les masque pas.
        getWindow().setNavigationBarColor(Color.BLACK);

        final FrameLayout home = new FrameLayout(this);
        home.setBackgroundColor(Color.TRANSPARENT);
        currentHomePageView = home;

        // ZENO FINAL : les 8 univers validés sont de vrais fonds intégrés au launcher.
        addZenoThemeBackground(home);

        GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        return true;
                    }

                    @Override
                    public void onLongPress(android.view.MotionEvent e) {
                        if (!editMode) showPersonalizationMenu();
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent e1,
                                           android.view.MotionEvent e2,
                                           float velocityX,
                                           float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();

                        if (Math.abs(dy) > Math.abs(dx) && dy < -dp(80)) {
                            showAppsDrawer();
                            return true;
                        }

                        if (Math.abs(dx) > dp(80) && Math.abs(dx) > Math.abs(dy)) {
                            switchPage(dx < 0 ? 1 : -1);
                            return true;
                        }
                        return false;
                    }
                });

        home.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
        home.setOnDragListener((v, event) -> handleHomeDrag(home, event));

        for (HomeItem item : new ArrayList<>(homeItems)) {
            if (item.page != currentPage) continue;
            View child = createHomeItemView(item);
            if (child == null) continue;
            child.setTag(item.id);
            home.addView(child, new FrameLayout.LayoutParams(
                    dp(item.widthDp),
                    dp(item.heightDp)
            ));
            child.post(() -> positionHomeItem(home, child, item));
        }

        // ECRAN_ACCUEIL : aucune barre de recherche, aucun micro, aucun menu ⋮
        // et aucun indicateur de page. Le fond + les éléments ajoutés par l’utilisateur seulement.

        stage.addView(home, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // ZENO FINAL : ancien robot mobile retiré. Le Zeno fixe de la maquette sera intégré à l’accueil final.

        if (direction != 0) animatePageIn(home, direction);
    }

    private void addZenoLivingRobot(FrameLayout home) {
        final int robotSize = dp(178); // V3 : robot visiblement plus grand.
        final FrameLayout platform = new FrameLayout(this);
        platform.setClickable(false);
        platform.setFocusable(false);
        platform.setBackground(rounded(0x5522BFE8, 0xFF62E8FF, 44));
        platform.setAlpha(0.80f);
        FrameLayout.LayoutParams platformLp = new FrameLayout.LayoutParams(dp(196), dp(42));
        platformLp.gravity = Gravity.TOP | Gravity.LEFT;
        home.addView(platform, platformLp);

        final FrameLayout robot = new FrameLayout(this);
        robot.setClickable(true);
        robot.setFocusable(true);
        robot.setContentDescription("Zeno vivant");
        robot.setBackground(rounded(0x88030A12, 0xFF19B9FF, 38));
        robot.setElevation(dp(10));
        robot.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView head = text("◉ ᴗ ◉", 30, 0xFFEAFBFF);
        head.setGravity(Gravity.CENTER);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams headLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74));
        headLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        robot.addView(head, headLp);

        TextView label = text("ZENO", 12, 0xFF8EEBFF);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        labelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        robot.addView(label, labelLp);

        final View core = new View(this);
        core.setBackground(rounded(0xFF48D9FF, 0xFFBDF6FF, 20));
        FrameLayout.LayoutParams coreLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        coreLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        coreLp.bottomMargin = dp(34);
        robot.addView(core, coreLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(robotSize, robotSize);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        home.addView(robot, lp);
        zenoRobotView = robot;

        home.post(() -> robot.post(() -> {
            if (home.getWidth() <= 0 || home.getHeight() <= 0 || robot.getWidth() <= 0) return;
            float savedX = settings.getFloat("zeno_v3_robot_x", settings.getFloat("zeno_v2_robot_x", -1f));
            float savedY = settings.getFloat("zeno_v3_robot_y", settings.getFloat("zeno_v2_robot_y", -1f));
            float maxX = Math.max(0, home.getWidth() - robot.getWidth());
            float maxY = Math.max(0, home.getHeight() - robot.getHeight());
            boolean valid = savedX >= 0 && savedY >= dp(36) && savedX <= maxX && savedY <= maxY;
            float x = valid ? savedX : maxX / 2f;
            float y = valid ? savedY : Math.min(maxY, Math.max(dp(90), home.getHeight() * 0.46f));
            robot.setX(clamp(x, 0, maxX));
            robot.setY(clamp(y, 0, maxY));
            // V3 : la plateforme est une base fixe, indépendante du robot.
            float baseX = settings.getFloat("zeno_v3_platform_x", Math.max(0, (home.getWidth() - platform.getWidth()) / 2f));
            float baseY = settings.getFloat("zeno_v3_platform_y", Math.max(dp(80), home.getHeight() * 0.70f));
            platform.setX(clamp(baseX, 0, Math.max(0, home.getWidth() - platform.getWidth())));
            platform.setY(clamp(baseY, dp(40), Math.max(dp(40), home.getHeight() - platform.getHeight())));
            startZenoIdleV3(robot, core, head, platform);
            zenoLastInteractionMs = SystemClock.elapsedRealtime();
            scheduleZenoSleep(home, robot, core, head);
        }));

        robot.setOnTouchListener((v, event) -> {
            if (drawerOpen) return false;
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                zenoWake(robot, core, head);
                zenoLastInteractionMs = SystemClock.elapsedRealtime();
                final float downX = event.getRawX();
                final float downY = event.getRawY();
                final float startX = v.getX();
                final float startY = v.getY();
                final boolean[] moved = {false};
                v.setTag(new float[]{downX, downY, startX, startY});
                return true;
            }
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                Object tag = v.getTag();
                if (tag instanceof float[]) {
                    float[] a = (float[]) tag;
                    float dx = event.getRawX() - a[0];
                    float dy = event.getRawY() - a[1];
                    float maxX = Math.max(0, home.getWidth() - v.getWidth());
                    float maxY = Math.max(0, home.getHeight() - v.getHeight());
                    v.setX(clamp(a[2] + dx, 0, maxX));
                    v.setY(clamp(a[3] + dy, 0, maxY));
                    return true;
                }
            }
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                Object tag = v.getTag();
                float[] a = tag instanceof float[] ? (float[]) tag : null;
                boolean moved = a != null && (Math.abs(event.getRawX() - a[0]) > dp(6) || Math.abs(event.getRawY() - a[1]) > dp(6));
                settings.edit().putFloat("zeno_v3_robot_x", v.getX()).putFloat("zeno_v3_robot_y", v.getY()).apply();
                if (!moved) {
                    reactZenoV3(head, core);
                    showZenoQuickMenu();
                }
                return true;
            }
            return true;
        });
    }

    private void scheduleZenoSleep(FrameLayout home, View robot, View core, TextView face) {
        if (zenoSleepCheck != null) zenoHandler.removeCallbacks(zenoSleepCheck);
        zenoSleepCheck = new Runnable() {
            @Override public void run() {
                if (!zenoAnimationRunning || robot != zenoRobotView || !robot.isShown()) return;
                if (!zenoSleeping && SystemClock.elapsedRealtime() - zenoLastInteractionMs >= ZENO_IDLE_SLEEP_MS) {
                    zenoSleep(robot, core, face);
                }
                zenoHandler.postDelayed(this, 3000);
            }
        };
        zenoHandler.postDelayed(zenoSleepCheck, 3000);
    }

    private void zenoWake(View robot, View core, TextView face) {
        zenoSleeping = false;
        zenoLastInteractionMs = SystemClock.elapsedRealtime();
        face.setText("◉ ◡ ◉");
        core.animate().alpha(1f).scaleX(1.35f).scaleY(1.35f).setDuration(110)
                .withEndAction(() -> core.animate().scaleX(1f).scaleY(1f).setDuration(180).start()).start();
        robot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    private void zenoSleep(View robot, View core, TextView face) {
        zenoSleeping = true;
        face.setText("— ᴗ —");
        robot.animate().alpha(0.86f).scaleX(0.96f).scaleY(0.96f).setDuration(500).start();
        core.animate().alpha(0.35f).scaleX(0.82f).scaleY(0.82f).setDuration(500).start();
    }

    private void startZenoIdleV3(View robot, View core, TextView face, View platform) {
        zenoAnimationRunning = true;
        zenoSleeping = false;
        Runnable pulse = new Runnable() {
            boolean up = true;
            @Override public void run() {
                if (!zenoAnimationRunning || robot != zenoRobotView || !robot.isShown()) return;
                if (!zenoSleeping) {
                    robot.animate().scaleY(up ? 0.992f : 1f).setDuration(900).start();
                    core.animate().alpha(up ? 0.72f : 1f).scaleX(up ? 0.92f : 1f).scaleY(up ? 0.92f : 1f).setDuration(850).start();
                }
                up = !up;
                zenoHandler.postDelayed(this, 1000);
            }
        };
        Runnable blink = new Runnable() {
            @Override public void run() {
                if (!zenoAnimationRunning || robot != zenoRobotView || !robot.isShown()) return;
                if (!zenoSleeping) {
                    face.setText("— ᴗ —");
                    zenoHandler.postDelayed(() -> {
                        if (zenoAnimationRunning && robot == zenoRobotView && robot.isShown() && !zenoSleeping) face.setText("◉ ᴗ ◉");
                    }, 140);
                }
                zenoHandler.postDelayed(this, 3900);
            }
        };
        zenoHandler.removeCallbacksAndMessages(null);
        zenoHandler.postDelayed(pulse, 450);
        zenoHandler.postDelayed(blink, 2100);
    }

    private void reactZenoV3(TextView face, View core) {
        zenoWake(zenoRobotView, core, face);
        face.setText("◉ ◡ ◉");
        zenoHandler.postDelayed(() -> {
            if (zenoAnimationRunning && zenoRobotView != null && !zenoSleeping) face.setText("◉ ᴗ ◉");
        }, 850);
    }

private void showZenoQuickMenu() {
        final Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(rounded(0xF20A0F16, 0xFF27C8FF, 26));

        TextView title = text(settings.getString("zeno_name", "Zeno"), 22, 0xFFF0FBFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView state = text("Je suis là ✦", 15, 0xFFB8EFFF);
        state.setGravity(Gravity.CENTER);
        panel.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        String[] actions = {"Zeno Evolution", "Personnaliser Zeno", "Applications", "Widgets", "Thèmes", "Réglages"};
        for (String action : actions) {
            TextView item = text(action, 17, 0xFFE8F7FF);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(16), 0);
            item.setBackground(rounded(0x441B6A88, 0x6638D8FF, 18));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            ilp.topMargin = dp(8);
            panel.addView(item, ilp);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                if ("Zeno Evolution".equals(action)) showZenoEvolutionHub();
                else if ("Personnaliser Zeno".equals(action)) showZenoIdentitySettings();
                else if ("Applications".equals(action)) showAppsDrawer();
                else if ("Widgets".equals(action)) showWidgetGallery();
                else if ("Thèmes".equals(action)) showZenoThemeSettings();
                else if ("Réglages".equals(action)) showPreferences();
            });
        }

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.25f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.82f), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (w != null) w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.82f), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addReferenceSearchStrip(FrameLayout home) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(10), 0, dp(8), 0);
        strip.setBackgroundColor(Color.TRANSPARENT);

        TextView search = text("⌕", 36, Color.WHITE);
        search.setGravity(Gravity.CENTER);
        search.setContentDescription("Rechercher des applications");
        search.setOnClickListener(v -> showAppsDrawer());
        strip.addView(search, new LinearLayout.LayoutParams(dp(58), dp(58)));

        View lineHolder = new View(this);
        lineHolder.setBackgroundColor(0xCCFFFFFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(0, dp(1), 1f);
        lineLp.leftMargin = dp(2);
        lineLp.rightMargin = dp(8);
        strip.addView(lineHolder, lineLp);

        TextView mic = text("♩", 31, Color.WHITE);
        mic.setGravity(Gravity.CENTER);
        mic.setContentDescription("Recherche vocale");
        mic.setOnClickListener(v -> startVoiceSearch());
        strip.addView(mic, new LinearLayout.LayoutParams(dp(52), dp(58)));

        TextView menu = text("⋮", 32, Color.WHITE);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Menu");
        menu.setOnClickListener(v -> showPersonalizationMenu());
        strip.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(58)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64),
                Gravity.TOP
        );
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        home.addView(strip, lp);
    }

    private void startVoiceSearch() {
        try {
            Intent intent = new Intent("android.speech.action.RECOGNIZE_SPEECH");
            intent.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
            intent.putExtra("android.speech.extra.PROMPT", "Rechercher une application");
            startActivityForResult(intent, 6101);
        } catch (Exception ignored) {
            toast("Recherche vocale indisponible");
        }
    }

    private void addPageIndicator(FrameLayout home) {
        int pages = pageCount();
        if (pages <= 1) return;

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(dp(10), dp(5), dp(10), dp(5));

        for (int i = 0; i < pages; i++) {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(i == currentPage ? Color.WHITE : 0x70FFFFFF);
            dot.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, lp);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        lp.bottomMargin = dp(18);
        home.addView(dots, lp);
    }

    private void addEditModeBar(FrameLayout home) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackground(rounded(0xD9000000, 0x55FFFFFF, 18));

        TextView pageText = text("Page " + (currentPage + 1) + " / " + pageCount(), 15, Color.WHITE);
        bar.addView(pageText, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button minus = smallButton("− Page");
        minus.setOnClickListener(v -> removeCurrentPage());
        bar.addView(minus, new LinearLayout.LayoutParams(dp(84), dp(44)));

        Button plus = smallButton("+ Page");
        plus.setOnClickListener(v -> addHomePage());
        bar.addView(plus, new LinearLayout.LayoutParams(dp(84), dp(44)));

        Button done = smallButton("Terminé");
        done.setOnClickListener(v -> {
            editMode = false;
            renderHome(0);
        });
        bar.addView(done, new LinearLayout.LayoutParams(dp(88), dp(44)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56),
                Gravity.TOP
        );
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        lp.topMargin = dp(10);
        home.addView(bar, lp);
    }

    private View createHomeItemView(HomeItem item) {
        switch (item.type) {
            case HomeItem.TYPE_ALL_APPS:
                return createAllAppsWidget(item);
            case HomeItem.TYPE_APP:
                return createHomeAppShortcut(item);
            case HomeItem.TYPE_FOLDER:
                return createFolderView(item);
            case HomeItem.TYPE_WIDGET:
                return createSystemWidgetView(item);
            default:
                return null;
        }
    }

    private View createAllAppsWidget(HomeItem item) {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setBackgroundColor(Color.TRANSPARENT);
        wrapper.setContentDescription("Toutes les applications");

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(4), dp(4), dp(4), dp(4));

        GridLayout dots = new GridLayout(this);
        dots.setColumnCount(3);
        dots.setRowCount(3);
        for (int i = 0; i < 9; i++) {
            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.WHITE);
            dot.setBackground(circle);
            GridLayout.LayoutParams dlp = new GridLayout.LayoutParams();
            dlp.width = dp(7);
            dlp.height = dp(7);
            dlp.setMargins(dp(3), dp(3), dp(3), dp(3));
            dots.addView(dot, dlp);
        }
        content.addView(dots);

        wrapper.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        wrapper.setOnClickListener(v -> {
            if (!editMode) showAppsDrawer();
        });
        attachDraggable(wrapper, item);
        addEditBadgeIfNeeded(wrapper, item);
        return wrapper;
    }

    private View createHomeAppShortcut(HomeItem item) {
        AppInfo app = findAppByComponentOrPackage(item.componentName, item.packageName);
        if (app == null) return null;

        FrameLayout wrapper = new FrameLayout(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(3), dp(5), dp(3), dp(3));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView label = text(app.label, 12, Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setShadowLayer(3f, 0f, 1f, Color.BLACK);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tlp.topMargin = dp(4);
        box.addView(label, tlp);

        wrapper.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        wrapper.setOnClickListener(v -> {
            if (!editMode) launchApp(app);
        });
        attachDraggable(wrapper, item);
        addEditBadgeIfNeeded(wrapper, item);
        return wrapper;
    }

    private View createFolderView(HomeItem item) {
        FrameLayout wrapper = new FrameLayout(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        GridLayout mini = new GridLayout(this);
        mini.setColumnCount(2);
        mini.setRowCount(2);
        mini.setPadding(dp(7), dp(7), dp(7), dp(7));
        mini.setBackground(rounded(0xCC2C2C2C, 0x55FFFFFF, 16));

        int count = 0;
        for (String pkg : item.folderPackages) {
            AppInfo app = findAppByComponentOrPackage("", pkg);
            if (app == null) continue;
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            GridLayout.LayoutParams ilp = new GridLayout.LayoutParams();
            ilp.width = dp(28);
            ilp.height = dp(28);
            ilp.setMargins(dp(2), dp(2), dp(2), dp(2));
            mini.addView(icon, ilp);
            count++;
            if (count >= 4) break;
        }
        box.addView(mini, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView label = text(item.label.isEmpty() ? "Dossier" : item.label, 12, Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setShadowLayer(3f, 0f, 1f, Color.BLACK);
        box.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
        ));

        wrapper.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        wrapper.setOnClickListener(v -> {
            if (!editMode) showFolder(item);
        });
        attachDraggable(wrapper, item);
        addEditBadgeIfNeeded(wrapper, item);
        return wrapper;
    }

    private View createSystemWidgetView(HomeItem item) {
        FrameLayout wrapper = new FrameLayout(this);
        AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(item.appWidgetId);

        if (info == null) {
            TextView unavailable = text("Widget indisponible", 13, Color.WHITE);
            unavailable.setGravity(Gravity.CENTER);
            unavailable.setBackground(rounded(0x99000000, 0x55FFFFFF, 12));
            wrapper.addView(unavailable, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        } else {
            try {
                AppWidgetHostView hostView = appWidgetHost.createView(this, item.appWidgetId, info);
                hostView.setAppWidget(item.appWidgetId, info);
                wrapper.addView(hostView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            } catch (Exception e) {
                TextView error = text("Erreur widget", 13, Color.WHITE);
                error.setGravity(Gravity.CENTER);
                error.setBackground(rounded(0x99000000, 0x55FFFFFF, 12));
                wrapper.addView(error, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }
        }

        attachDraggable(wrapper, item);
        addEditBadgeIfNeeded(wrapper, item);
        return wrapper;
    }

    private void addEditBadgeIfNeeded(FrameLayout wrapper, HomeItem item) {
        if (!editMode) return;

        TextView dragHandle = text("✥", 18, Color.WHITE);
        dragHandle.setGravity(Gravity.CENTER);
        dragHandle.setBackground(rounded(0xDD111111, accentColor(), 14));
        dragHandle.setOnLongClickListener(v -> {
            ClipData data = ClipData.newPlainText("zeno-home-item", item.id);
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(wrapper);
            boolean started = wrapper.startDragAndDrop(data, shadow, item.id, 0);
            if (started) wrapper.setAlpha(0.25f);
            return started;
        });
        dragHandle.setOnClickListener(v -> toast("Maintiens ✥ puis déplace l'élément"));
        FrameLayout.LayoutParams dragLp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.START);
        dragLp.setMargins(dp(2), dp(2), 0, 0);
        wrapper.addView(dragHandle, dragLp);

        TextView badge = text("⋮", 22, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setBackground(rounded(0xDD111111, accentColor(), 14));
        badge.setOnClickListener(v -> showHomeItemOptions(item));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(2), dp(2), 0);
        wrapper.addView(badge, lp);
    }

    private void attachDraggable(View view, HomeItem item) {
        view.setOnLongClickListener(v -> {
            ClipData data = ClipData.newPlainText("zeno-home-item", item.id);
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
            boolean started = v.startDragAndDrop(data, shadow, item.id, 0);
            if (started) v.setAlpha(0.25f);
            return started;
        });
    }

    private boolean handleHomeDrag(FrameLayout home, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof String;

            case DragEvent.ACTION_DROP: {
                String id = String.valueOf(event.getLocalState());
                HomeItem item = homeStore.find(homeItems, id);
                if (item == null) return false;

                int itemW = dp(item.widthDp);
                int itemH = dp(item.heightDp);
                float maxX = Math.max(1, home.getWidth() - itemW);
                float maxY = Math.max(1, home.getHeight() - itemH);
                float px = clamp(event.getX() - itemW / 2f, 0, maxX);
                float py = clamp(event.getY() - itemH / 2f, 0, maxY);

                item.x = px / maxX;
                item.y = py / maxY;
                item.page = currentPage;

                if (HomeItem.TYPE_APP.equals(item.type)) {
                    tryMergeAppIntoFolder(item, home);
                }

                homeStore.save(homeItems);
                renderHome(0);
                return true;
            }

            case DragEvent.ACTION_DRAG_ENDED:
                if (!event.getResult()) renderHome(0);
                return true;

            default:
                return true;
        }
    }

    private void tryMergeAppIntoFolder(HomeItem dropped, FrameLayout home) {
        float droppedX = dropped.x * Math.max(1, home.getWidth() - dp(dropped.widthDp));
        float droppedY = dropped.y * Math.max(1, home.getHeight() - dp(dropped.heightDp));

        HomeItem target = null;
        float best = Float.MAX_VALUE;

        for (HomeItem candidate : homeItems) {
            if (candidate == dropped || candidate.page != currentPage) continue;
            if (!HomeItem.TYPE_APP.equals(candidate.type) && !HomeItem.TYPE_FOLDER.equals(candidate.type)) continue;

            float cx = candidate.x * Math.max(1, home.getWidth() - dp(candidate.widthDp));
            float cy = candidate.y * Math.max(1, home.getHeight() - dp(candidate.heightDp));
            float dx = droppedX - cx;
            float dy = droppedY - cy;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < best) {
                best = dist;
                target = candidate;
            }
        }

        if (target == null || best > dp(62)) return;

        if (HomeItem.TYPE_APP.equals(target.type)) {
            HomeItem folder = new HomeItem();
            folder.type = HomeItem.TYPE_FOLDER;
            folder.label = "Dossier";
            folder.page = currentPage;
            folder.x = target.x;
            folder.y = target.y;
            folder.widthDp = 92;
            folder.heightDp = 110;
            if (!target.packageName.isEmpty()) folder.folderPackages.add(target.packageName);
            if (!dropped.packageName.isEmpty() && !folder.folderPackages.contains(dropped.packageName)) {
                folder.folderPackages.add(dropped.packageName);
            }
            homeStore.remove(homeItems, target.id);
            homeStore.remove(homeItems, dropped.id);
            homeItems.add(folder);
        } else {
            if (!dropped.packageName.isEmpty() && !target.folderPackages.contains(dropped.packageName)) {
                target.folderPackages.add(dropped.packageName);
            }
            homeStore.remove(homeItems, dropped.id);
        }
    }

    private void positionHomeItem(FrameLayout home, View child, HomeItem item) {
        int width = child.getWidth() > 0 ? child.getWidth() : dp(item.widthDp);
        int height = child.getHeight() > 0 ? child.getHeight() : dp(item.heightDp);
        float maxX = Math.max(0, home.getWidth() - width);
        float maxY = Math.max(0, home.getHeight() - height);
        child.setX(clamp(item.x, 0f, 1f) * maxX);
        child.setY(clamp(item.y, 0f, 1f) * maxY);
    }

    private void switchPage(int delta) {
        int pages = pageCount();
        int next = Math.max(0, Math.min(pages - 1, currentPage + delta));
        if (next == currentPage) return;
        int direction = next > currentPage ? 1 : -1;
        currentPage = next;
        renderHome(direction);
    }

    private int pageCount() {
        return Math.max(1, Math.min(9, settings.getInt("home_pages", 1)));
    }

    private void addHomePage() {
        int pages = pageCount();
        if (pages >= 9) {
            toast("Maximum : 9 pages");
            return;
        }
        pages++;
        settings.edit().putInt("home_pages", pages).apply();
        currentPage = pages - 1;
        renderHome(1);
    }

    private void removeCurrentPage() {
        int pages = pageCount();
        if (pages <= 1) {
            toast("Il faut garder au moins une page");
            return;
        }

        int remove = currentPage;
        int destination = Math.max(0, remove - 1);
        for (HomeItem item : homeItems) {
            if (item.page == remove) item.page = destination;
            else if (item.page > remove) item.page--;
        }
        pages--;
        settings.edit().putInt("home_pages", pages).apply();
        currentPage = Math.min(destination, pages - 1);
        homeStore.save(homeItems);
        renderHome(-1);
    }

    // ECRAN_TIROIR_APPLICATIONS
    private void showAppsDrawer() {
        // ZENO VIVANT : couper totalement le robot AVANT d'ouvrir le tiroir.
        // Aucun callback/animation/toucher du robot ne doit survivre dans ECRAN_TIROIR.
        zenoAnimationRunning = false;
        zenoHandler.removeCallbacksAndMessages(null);
        if (zenoRobotView != null) {
            zenoRobotView.animate().cancel();
            zenoRobotView.setOnTouchListener(null);
            zenoRobotView = null;
        }
        drawerOpen = true;
        editMode = false;
        // IMPORTANT : le noir est applique au conteneur racine AVANT de vider la page.
        // Ainsi, meme la zone d'insets/barre d'etat ne peut jamais laisser voir l'accueil.
        root.setBackgroundColor(Color.BLACK);
        stage.setBackgroundColor(Color.BLACK);
        stage.removeAllViews();

        // Le tiroir couvre 100 % de la zone de l'application : aucun espace
        // ne laisse voir ECRAN_ACCUEIL derrière.
        // ECRAN_TIROIR_APPLICATIONS : noir 100 % opaque. Aucun fond d’écran ni élément de l’accueil ne transparaît.
        final int drawerBg = Color.BLACK;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(8), dp(12), dp(8));
        // Le fond reste plein écran : les réglages déplacent le CONTENU sans
        // créer de trou laissant apparaître ECRAN_ACCUEIL.
        page.setBackgroundColor(drawerBg);

        // Barre courte et légère : Rechercher + micro, puis menu ⋮ à droite.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(4), 0);
        searchBox.setBackground(rounded(0x1FFFFFFF, 0x3DFFFFFF, 24));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Rechercher");
        search.setTextColor(0xFFE8E8E8);
        search.setHintTextColor(0xFFBDBDBD);
        search.setTextSize(15);
        search.setPadding(dp(4), 0, dp(4), 0);
        search.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView mic = text("🎤", 18, Color.WHITE);
        mic.setGravity(Gravity.CENTER);
        mic.setContentDescription("Recherche vocale");
        mic.setBackgroundColor(Color.TRANSPARENT);
        mic.setOnClickListener(v -> startVoiceSearch());
        searchBox.addView(mic, new LinearLayout.LayoutParams(dp(42), dp(44)));

        // 82 % environ de la largeur utile : volontairement plus courte.
        top.addView(searchBox, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView more = text("⋮", 28, Color.WHITE);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Menu du tiroir d'applications");
        more.setBackgroundColor(Color.TRANSPARENT);
        more.setOnClickListener(v -> showDrawerMenu());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(46), dp(44));
        mlp.leftMargin = dp(6);
        top.addView(more, mlp);
        page.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        // Pas de gros vide en bas : seule une petite marge protège la dernière ligne.
        scroll.setPadding(0, dp(2), 0, dp(8));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(drawerBg);

        GridLayout grid = new GridLayout(this);
        int columns = settings.getInt("drawer_columns", 4);
        columns = Math.max(2, Math.min(8, columns));
        grid.setColumnCount(columns);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        grid.setBackgroundColor(drawerBg);
        scroll.addView(grid, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int drawerColumns = columns;
        // PERFORMANCE V1 : la recherche ne reconstruit plus toute la grille à chaque frappe.
        // On attend un très court instant après la dernière touche (debounce).
        final Handler searchHandler = new Handler(Looper.getMainLooper());
        final Runnable[] pendingSearchRender = new Runnable[1];
        Runnable render = () -> renderDrawerGrid(grid, search.getText().toString(), drawerColumns);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pendingSearchRender[0] != null) searchHandler.removeCallbacks(pendingSearchRender[0]);
                pendingSearchRender[0] = render;
                searchHandler.postDelayed(pendingSearchRender[0], 120L);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        render.run();

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ZENO FINAL : dans le tiroir, haut/bas appartient TOUJOURS à la liste.
        // Fermeture = geste dédié depuis le bord gauche vers la droite.
        // Ainsi, même avec beaucoup d’applications, aucun conflit avec le défilement vertical.
        GestureDetector drawerEdgeGesture = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                    @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                                       float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        int edgeWidth = dp(settings.getInt("drawer_close_edge_dp", 32));
                        int distance = dp(settings.getInt("drawer_close_distance_dp", 110));
                        boolean startsOnLeftEdge = e1.getX() <= edgeWidth;
                        if (startsOnLeftEdge && dx > distance && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                            drawerOpen = false;
                            renderHome(0);
                            return true;
                        }
                        return false;
                    }
                });
        page.setOnTouchListener((v, event) -> drawerEdgeGesture.onTouchEvent(event));
        scroll.setOnTouchListener((v, event) -> {
            drawerEdgeGesture.onTouchEvent(event);
            return false;
        });

        stage.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // V3.1 STABILITE : le tiroir doit apparaitre instantanement.
        // Ne jamais lancer animateIn() ici : le mode 1 applique une translationY de 90dp
        // pendant 300 ms, ce qui donne exactement l'impression que le tiroir remonte
        // tout seul et peut entrer en conflit avec les gestes rapides de l'utilisateur.
        page.animate().cancel();
        resetTransform(page);
        page.setAlpha(1f);
        page.setTranslationX(0f);
        page.setTranslationY(0f);
    }

    private void installDrawerTouchAdjustment(final LinearLayout page) {
        // MODE REGLAGE SECURISE : le calque intercepte tous les touchers.
        // Les applications restent visibles mais ne peuvent pas etre ouvertes.
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setBackgroundColor(0x33000000);
        overlay.setOnTouchListener((v, e) -> true);

        // Copie de travail uniquement. Rien n'est sauvegarde avant VERROUILLER.
        final int[] margins = {
                settings.getInt("drawer_adjust_top", 8),
                settings.getInt("drawer_adjust_bottom", 8),
                settings.getInt("drawer_adjust_left", 12),
                settings.getInt("drawer_adjust_right", 12)
        };

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(18), dp(18), dp(18), dp(18));
        controls.setBackground(rounded(0xF2222222, 0xFF4A4A4A, 22));

        TextView title = text("Ajuster l’écran tiroir", 19, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        controls.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        TextView hint = text("Appui = réduire • appui long = agrandir", 12, 0xFFCCCCCC);
        hint.setGravity(Gravity.CENTER);
        controls.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        Button up = adjustmentButton("▲\nHAUT");
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(dp(118), dp(72));
        upLp.gravity = Gravity.CENTER_HORIZONTAL;
        controls.addView(up, upLp);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setGravity(Gravity.CENTER);
        Button left = adjustmentButton("◀\nGAUCHE");
        Button lock = adjustmentButton("🔒\nVERROUILLER");
        Button right = adjustmentButton("▶\nDROITE");
        middle.addView(left, new LinearLayout.LayoutParams(dp(112), dp(82)));
        LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(dp(128), dp(82));
        centerLp.leftMargin = dp(8); centerLp.rightMargin = dp(8);
        middle.addView(lock, centerLp);
        middle.addView(right, new LinearLayout.LayoutParams(dp(112), dp(82)));
        controls.addView(middle);

        Button down = adjustmentButton("BAS\n▼");
        LinearLayout.LayoutParams downLp = new LinearLayout.LayoutParams(dp(118), dp(72));
        downLp.gravity = Gravity.CENTER_HORIZONTAL;
        controls.addView(down, downLp);

        Button reset = adjustmentButton("↶  RÉINITIALISER");
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(dp(220), dp(54));
        resetLp.gravity = Gravity.CENTER_HORIZONTAL;
        resetLp.topMargin = dp(12);
        controls.addView(reset, resetLp);

        // Un appui augmente la marge correspondante (reduit le tiroir).
        // Un appui long la diminue (agrandit le tiroir). Pas de valeur dangereuse.
        bindDrawerAdjustButton(up, page, margins, 0);
        bindDrawerAdjustButton(down, page, margins, 1);
        bindDrawerAdjustButton(left, page, margins, 2);
        bindDrawerAdjustButton(right, page, margins, 3);

        lock.setOnClickListener(v -> {
            settings.edit()
                    .putInt("drawer_adjust_top", margins[0])
                    .putInt("drawer_adjust_bottom", margins[1])
                    .putInt("drawer_adjust_left", margins[2])
                    .putInt("drawer_adjust_right", margins[3])
                    .putBoolean("drawer_adjust_locked", true)
                    .putBoolean("drawer_touch_adjust_mode", false)
                    .apply();
            Toast.makeText(this, "Dimensions enregistrées et verrouillées", Toast.LENGTH_SHORT).show();
            showAppsDrawer();
        });

        reset.setOnClickListener(v -> {
            margins[0] = 8; margins[1] = 8; margins[2] = 12; margins[3] = 12;
            applyDrawerWorkingMargins(page, margins);
        });

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        overlay.addView(controls, cp);

        // PLAN DE SECOURS : toujours fixe a l'ecran et independant du tiroir.
        Button rescue = new Button(this);
        rescue.setText("↶ SECOURS");
        rescue.setAllCaps(false);
        rescue.setTextSize(12);
        rescue.setOnLongClickListener(v -> {
            settings.edit()
                    .putInt("drawer_adjust_top", 8)
                    .putInt("drawer_adjust_bottom", 8)
                    .putInt("drawer_adjust_left", 12)
                    .putInt("drawer_adjust_right", 12)
                    .putBoolean("drawer_adjust_locked", false)
                    .putBoolean("drawer_touch_adjust_mode", false)
                    .apply();
            Toast.makeText(this, "Tiroir remis aux dimensions de secours", Toast.LENGTH_SHORT).show();
            showAppsDrawer();
            return true;
        });
        rescue.setOnClickListener(v -> Toast.makeText(this,
                "Maintiens SECOURS 3 secondes pour réinitialiser", Toast.LENGTH_SHORT).show());
        FrameLayout.LayoutParams rescueLp = new FrameLayout.LayoutParams(
                dp(120), dp(48), Gravity.TOP | Gravity.END);
        rescueLp.topMargin = dp(8); rescueLp.rightMargin = dp(8);
        overlay.addView(rescue, rescueLp);

        stage.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Button adjustmentButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private void bindDrawerAdjustButton(Button button, LinearLayout page,
                                        int[] margins, int index) {
        button.setOnClickListener(v -> {
            margins[index] = Math.min(120, margins[index] + 1);
            applyDrawerWorkingMargins(page, margins);
        });
        button.setOnLongClickListener(v -> {
            margins[index] = Math.max(0, margins[index] - 1);
            applyDrawerWorkingMargins(page, margins);
            return true;
        });
    }

    private void applyDrawerWorkingMargins(LinearLayout page, int[] margins) {
        page.setPadding(dp(margins[2]), dp(margins[0]), dp(margins[3]), dp(margins[1]));
    }

    private void renderDrawerGrid(GridLayout grid, String query, int columns) {
        grid.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());

        int screenWidth = getResources().getDisplayMetrics().widthPixels - dp(24);
        int cellWidth = Math.max(dp(48), screenWidth / columns);
        int iconSize = settings.getInt("drawer_icon_size", 56);
        int textSize = settings.getInt("drawer_text_size", 12);
        int verticalSpace = settings.getInt("drawer_vertical_space", 8);

        Set<String> hidden = settings.getStringSet("hidden_apps", Collections.emptySet());
        Set<String> folderedPackages = new HashSet<>();
        Set<String> drawerFolders = new HashSet<>(settings.getStringSet("drawer_folders", Collections.emptySet()));

        // Dossiers natifs du tiroir Zeno : ils restent dans le tiroir et masquent leurs applis de la grille principale.
        for (String encoded : drawerFolders) {
            String[] parts=encoded.split("::",2);
            if(parts.length!=2) continue;
            String folderName=parts[0];
            ArrayList<String> pkgs=new ArrayList<>();
            if(!parts[1].isEmpty()) Collections.addAll(pkgs,parts[1].split(","));
            folderedPackages.addAll(pkgs);
            if(!needle.isEmpty() && !folderName.toLowerCase(Locale.getDefault()).contains(needle)) continue;
            LinearLayout cell=new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4),dp(verticalSpace),dp(4),dp(verticalSpace)); cell.setBackground(zenoIconFrame());
            TextView folderIcon=text("▦",42,0xFF58C7FF); folderIcon.setGravity(Gravity.CENTER); cell.addView(folderIcon,new LinearLayout.LayoutParams(dp(iconSize),dp(iconSize)));
            TextView label=text(folderName,textSize,0xFFE2E2E2); label.setGravity(Gravity.CENTER); label.setMaxLines(2); cell.addView(label,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(44)));
            cell.setOnClickListener(v->openDrawerFolder(folderName,pkgs));
            cell.setOnLongClickListener(v->{ showDrawerFolderOptions(encoded,folderName); return true; });
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams(); lp.width=cellWidth; lp.height=dp(iconSize+58+(verticalSpace*2)); grid.addView(cell,lp);
        }

        for (AppInfo app : allApps) {
            if (hidden.contains(app.packageName) || folderedPackages.contains(app.packageName)) continue;
            if (!needle.isEmpty() && !app.label.toLowerCase(Locale.getDefault()).contains(needle)) continue;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(verticalSpace), dp(4), dp(verticalSpace));
            cell.setBackground(zenoIconFrame());

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));

            TextView label = text(app.label, textSize, 0xFFE2E2E2);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
            );
            labelLp.topMargin = dp(5);
            cell.addView(label, labelLp);

            cell.setOnClickListener(v -> launchApp(app));
            cell.setOnLongClickListener(v -> {
                showDrawerAppOptions(app);
                return true;
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellWidth;
            lp.height = dp(iconSize + 58 + (verticalSpace * 2));
            grid.addView(cell, lp);
        }
    }

    private void showDrawerMenu() {
        String[] items = {
                "▦   Mode de vue",
                "📁   Créer un dossier",
                "✦   Personnaliser les icônes",
                "◉   Gestion d'applications",
                "⚙   Préférences"
        };

        AlertDialog drawerMenuDialog = new AlertDialog.Builder(this)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showDrawerViewMode();
                    } else if (which == 1) {
                        createFolderFromDrawer();
                    } else if (which == 2) {
                        showIconPersonalization();
                    } else if (which == 3) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                            startActivity(intent);
                        } catch (Exception e) {
                            toast("Gestion d'applications indisponible");
                        }
                    } else {
                        drawerOpen = false;
                        showPreferences();
                    }
                })
                .create();
        drawerMenuDialog.setOnShowListener(d -> {
            if (drawerMenuDialog.getWindow() != null) {
                drawerMenuDialog.getWindow().setDimAmount(0.35f);
                drawerMenuDialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0xFF080B10));
            }
        });
        drawerMenuDialog.show();

        // MENU ⋮ DU TIROIR : forcer une écriture claire sur le fond noir Zeno.
        // AlertDialog peut conserver la couleur de texte sombre du thème Android.
        android.widget.ListView menuList = drawerMenuDialog.getListView();
        if (menuList != null) {
            menuList.setBackgroundColor(0xFF080B10);
            menuList.setDivider(new android.graphics.drawable.ColorDrawable(0xFF162033));
            menuList.setDividerHeight(dp(1));
            menuList.post(() -> {
                for (int i = 0; i < menuList.getChildCount(); i++) {
                    View child = menuList.getChildAt(i);
                    if (child instanceof TextView) {
                        TextView item = (TextView) child;
                        item.setTextColor(0xFFE8EDF5);
                        item.setTextSize(17);
                    }
                }
            });
        }
    }

    private void showDrawerViewMode() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), dp(8));

        final int[] columns = {settings.getInt("drawer_columns", 4)};
        final int[] iconSize = {settings.getInt("drawer_icon_size", 56)};
        final int[] textSize = {settings.getInt("drawer_text_size", 12)};
        final int[] spacing = {settings.getInt("drawer_vertical_space", 8)};

        TextView colLabel = text("Colonnes : " + columns[0], 15, Color.WHITE);
        SeekBar col = new SeekBar(this); col.setMax(6); col.setProgress(Math.max(0, columns[0] - 2));
        col.setOnSeekBarChangeListener(simpleSeek(v -> { columns[0] = v + 2; colLabel.setText("Colonnes : " + columns[0]); }));
        box.addView(colLabel); box.addView(col);

        TextView iconLabel = text("Taille des icônes : " + iconSize[0], 15, Color.WHITE);
        SeekBar icons = new SeekBar(this); icons.setMax(48); icons.setProgress(Math.max(0, iconSize[0] - 32));
        icons.setOnSeekBarChangeListener(simpleSeek(v -> { iconSize[0] = v + 32; iconLabel.setText("Taille des icônes : " + iconSize[0]); }));
        box.addView(iconLabel); box.addView(icons);

        TextView textLabel = text("Taille du texte : " + textSize[0], 15, Color.WHITE);
        SeekBar texts = new SeekBar(this); texts.setMax(12); texts.setProgress(Math.max(0, textSize[0] - 8));
        texts.setOnSeekBarChangeListener(simpleSeek(v -> { textSize[0] = v + 8; textLabel.setText("Taille du texte : " + textSize[0]); }));
        box.addView(textLabel); box.addView(texts);

        TextView spaceLabel = text("Espacement : " + spacing[0], 15, Color.WHITE);
        SeekBar spaces = new SeekBar(this); spaces.setMax(24); spaces.setProgress(spacing[0]);
        spaces.setOnSeekBarChangeListener(simpleSeek(v -> { spacing[0] = v; spaceLabel.setText("Espacement : " + spacing[0]); }));
        box.addView(spaceLabel); box.addView(spaces);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Mode de vue • Zeno")
                .setView(box)
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Réinitialiser", (x, w) -> {
                    settings.edit().putInt("drawer_columns", 4).putInt("drawer_icon_size", 56)
                            .putInt("drawer_text_size", 12).putInt("drawer_vertical_space", 8).apply();
                    showAppsDrawer();
                })
                .setPositiveButton("Appliquer", (x, w) -> {
                    settings.edit().putInt("drawer_columns", columns[0]).putInt("drawer_icon_size", iconSize[0])
                            .putInt("drawer_text_size", textSize[0]).putInt("drawer_vertical_space", spacing[0]).apply();
                    showAppsDrawer();
                }).create();
        d.setOnShowListener(x -> {
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new ColorDrawable(0xFF080B10));
            }
            // FIX : le titre "Mode de vue • Zeno" restait noir avec certains thèmes Android.
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView titleView = d.findViewById(titleId);
            if (titleView != null) {
                titleView.setTextColor(0xFFE8EDF5);
            }
        });
        d.show();
    }

    private void showIconPersonalization() {
        final String[] styles={"Futuriste","Minimal","Glass","Néon","3D"};
        final String[] effects={"Aucun","Lueur","Galaxie","Cristal"};
        LinearLayout box=zenoSettingsBox();
        box.addView(zenoSettingTitle("Style global des icônes"));
        android.widget.Spinner style=new android.widget.Spinner(this);
        style.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,styles));
        style.setSelection(Math.max(0,Math.min(styles.length-1,settings.getInt("icon_style",0))));
        box.addView(style);
        box.addView(zenoSettingTitle("Effet"));
        android.widget.Spinner effect=new android.widget.Spinner(this);
        effect.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,effects));
        effect.setSelection(Math.max(0,Math.min(effects.length-1,settings.getInt("icon_effect",1))));
        box.addView(effect);
        box.addView(zenoSwitch("Cadre arrondi futuriste", "icon_rounded_frame", true));
        new AlertDialog.Builder(this).setTitle("Icônes • Zeno")
                .setView(box).setNegativeButton("Annuler",null)
                .setPositiveButton("Appliquer",(d,w)-> {
                    settings.edit().putInt("icon_style",style.getSelectedItemPosition())
                            .putInt("icon_effect",effect.getSelectedItemPosition()).apply();
                    showAppsDrawer();
                }).show();
    }

    private Drawable zenoIconFrame() {
        GradientDrawable g=new GradientDrawable();
        int style=settings.getInt("icon_style",0);
        int effect=settings.getInt("icon_effect",1);
        int fill = style==1 ? 0x22000000 : style==2 ? 0x44354B66 : 0x552A3A55;
        g.setColor(fill);
        g.setCornerRadius(dp(settings.getBoolean("icon_rounded_frame",true)?18:6));
        int stroke= effect==0 ? 0x335A7A9A : effect==2 ? 0xFF9B63FF : effect==3 ? 0xFF9FE8FF : 0xFF38B9FF;
        g.setStroke(dp(effect==0?1:2),stroke);
        return g;
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(final java.util.function.IntConsumer action) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { action.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private void createFolderFromDrawer() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nom du dossier");
        input.setPadding(dp(16), dp(8), dp(16), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("Créer un dossier")
                .setView(input)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Suivant", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Dossier";
                    chooseAppsForNewFolder(name);
                })
                .show();
    }

    private void chooseAppsForNewFolder(String folderName) {
        String[] labels = new String[allApps.size()];
        boolean[] checked = new boolean[allApps.size()];

        for (int i = 0; i < allApps.size(); i++) {
            labels[i] = allApps.get(i).label;
        }

        new AlertDialog.Builder(this)
                .setTitle("Applications du dossier")
                .setMultiChoiceItems(
                        labels,
                        checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked
                )
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Créer", (dialog, which) -> {
                    ArrayList<String> pkgs=new ArrayList<>();
                    for(int i=0;i<checked.length;i++) if(checked[i]) pkgs.add(allApps.get(i).packageName);
                    if(pkgs.isEmpty()){ toast("Choisis au moins une application"); return; }
                    Set<String> folders=new HashSet<>(settings.getStringSet("drawer_folders",Collections.emptySet()));
                    folders.add(folderName+"::"+android.text.TextUtils.join(",",pkgs));
                    settings.edit().putStringSet("drawer_folders",folders).apply();
                    toast("Dossier créé dans le tiroir");
                    showAppsDrawer();
                })
                .show();
    }

    private void openDrawerFolder(String name, List<String> packages) {
        ArrayList<AppInfo> apps=new ArrayList<>();
        for(String pkg:packages){ AppInfo a=findAppByPackage(pkg); if(a!=null) apps.add(a); }
        String[] labels=new String[apps.size()]; for(int i=0;i<apps.size();i++) labels[i]=apps.get(i).label;
        new AlertDialog.Builder(this).setTitle("📁 "+name).setItems(labels,(d,w)->launchApp(apps.get(w))).setNegativeButton("Fermer",null).show();
    }

    private void showDrawerFolderOptions(String encoded, String name) {
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Supprimer le dossier"},(d,w)->{
            Set<String> folders=new HashSet<>(settings.getStringSet("drawer_folders",Collections.emptySet()));
            folders.remove(encoded); settings.edit().putStringSet("drawer_folders",folders).apply();
            toast("Dossier supprimé • applications conservées"); showAppsDrawer();
        }).show();
    }

    private void showDrawerAppOptions(AppInfo app) {
        String[] items = {"Ajouter à l'accueil", "Informations sur l'application"};
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) addAppToHome(app);
                    else openAppInfo(app.packageName);
                })
                .show();
    }

    private void addAppToHome(AppInfo app) {
        HomeItem item = new HomeItem();
        item.type = HomeItem.TYPE_APP;
        item.label = app.label;
        item.packageName = app.packageName;
        item.componentName = app.componentName.flattenToString();
        item.page = currentPage;
        item.x = 0.16f + (float) Math.random() * 0.58f;
        item.y = 0.20f + (float) Math.random() * 0.54f;
        item.widthDp = 92;
        item.heightDp = 110;
        homeItems.add(item);
        homeStore.save(homeItems);
        toast("Ajouté à l'accueil");
    }

    private void showPersonalizationMenu() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(10));
        panel.setBackground(rounded(panelBackground(), 0x33000000, 20));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        row.addView(personalizationTile("▣", "Fonds d'écran", () -> {
            dialog.dismiss();
            openWallpaperChooser();
        }));
        row.addView(personalizationTile("▦", "Widgets", () -> {
            dialog.dismiss();
            showWidgetGallery();
        }));
        row.addView(personalizationTile("⚙", "Préférences", () -> {
            dialog.dismiss();
            showPreferences();
        }));

        horizontal.addView(row);
        panel.addView(horizontal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(80)
        ));

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.width = WindowManager.LayoutParams.MATCH_PARENT;
            attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attrs.gravity = Gravity.BOTTOM;
            attrs.dimAmount = 0.25f;
            w.setAttributes(attrs);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View personalizationTile(String icon, String label, Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(1), dp(4), dp(1));
        tile.setBackground(rounded(cardBackground(), 0x22000000, 16));

        TextView i = text(icon, 24, accentColor());
        i.setGravity(Gravity.CENTER);
        tile.addView(i, new LinearLayout.LayoutParams(dp(44), dp(32)));

        TextView t = text(label, 11, foregroundColor());
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        tile.addView(t, new LinearLayout.LayoutParams(dp(100), dp(30)));

        tile.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(72));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        tile.setLayoutParams(lp);
        return tile;
    }

    private void openWallpaperChooser() {
        try {
            Intent intent = new Intent(Intent.ACTION_SET_WALLPAPER);
            startActivity(Intent.createChooser(intent, "Choisir un fond d'écran"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Exception ignored) {
                toast("Sélecteur de fond d'écran indisponible");
            }
        }
    }

    private void showWidgetGallery() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(14), dp(14), dp(14), dp(14));
        outer.setBackgroundColor(panelBackground());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Widgets", 22, foregroundColor());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView close = text("✕", 24, foregroundColor());
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        outer.addView(header);

        EditText search = new EditText(this);
        search.setHint("Rechercher un widget");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFFB8C7D9);
        search.setPadding(dp(15), 0, dp(15), 0);
        search.setBackground(rounded(cardBackground(), 0x22000000, 20));
        outer.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(10), 0, dp(30));
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        List<AppWidgetProviderInfo> providers = new ArrayList<>(appWidgetManager.getInstalledProviders());
        providers.sort(Comparator.comparing(p -> String.valueOf(p.loadLabel(packageManager)), String.CASE_INSENSITIVE_ORDER));

        Runnable render = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());

            if (q.isEmpty() || "toutes les applications".contains(q) || "zeno".contains(q)) {
                list.addView(nativeAllAppsWidgetCard(dialog));
            }

            for (AppWidgetProviderInfo info : providers) {
                String name = String.valueOf(info.loadLabel(packageManager));
                String providerApp = info.provider == null ? "" : appLabelForPackage(info.provider.getPackageName());
                String haystack = (name + " " + providerApp).toLowerCase(Locale.getDefault());
                if (!q.isEmpty() && !haystack.contains(q)) continue;
                list.addView(systemWidgetCard(info, dialog));
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        render.run();

        dialog.setContentView(outer);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(panelBackground()));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View nativeAllAppsWidgetCard(Dialog dialog) {
        LinearLayout card = widgetCardBase();

        GridLayout preview = new GridLayout(this);
        preview.setColumnCount(3);
        preview.setRowCount(3);
        for (int i = 0; i < 9; i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(foregroundColor());
            dot.setBackground(d);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(7);
            lp.height = dp(7);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            preview.addView(dot, lp);
        }
        card.addView(preview, new LinearLayout.LayoutParams(dp(82), dp(82)));

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text("Toutes les applications", 16, foregroundColor());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        textBox.addView(name);
        textBox.addView(text("Widget Zeno • 1×1", 12, mutedColor()));
        card.addView(textBox, new LinearLayout.LayoutParams(0, dp(82), 1f));

        Button add = smallButton("Ajouter");
        add.setOnClickListener(v -> {
            addNativeAllAppsWidget();
            dialog.dismiss();
        });
        card.addView(add, new LinearLayout.LayoutParams(dp(90), dp(44)));
        return card;
    }

    private View systemWidgetCard(AppWidgetProviderInfo info, Dialog dialog) {
        LinearLayout card = widgetCardBase();

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Drawable drawable = null;
        try {
            drawable = info.loadPreviewImage(this, getResources().getDisplayMetrics().densityDpi);
        } catch (Throwable ignored) {
        }
        if (drawable == null) {
            try {
                drawable = info.loadIcon(this, getResources().getDisplayMetrics().densityDpi);
            } catch (Throwable ignored) {
            }
        }
        if (drawable != null) preview.setImageDrawable(drawable);
        card.addView(preview, new LinearLayout.LayoutParams(dp(82), dp(82)));

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setGravity(Gravity.CENTER_VERTICAL);
        textBox.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(String.valueOf(info.loadLabel(packageManager)), 15, foregroundColor());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        textBox.addView(name);
        String appName = info.provider == null ? "Widget Android" : appLabelForPackage(info.provider.getPackageName());
        textBox.addView(text(appName, 12, mutedColor()));
        card.addView(textBox, new LinearLayout.LayoutParams(0, dp(82), 1f));

        Button add = smallButton("Ajouter");
        add.setOnClickListener(v -> {
            dialog.dismiss();
            requestSystemWidget(info);
        });
        card.addView(add, new LinearLayout.LayoutParams(dp(90), dp(44)));
        return card;
    }

    private LinearLayout widgetCardBase() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(rounded(cardBackground(), 0x22000000, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(104)
        );
        lp.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(lp);
        return card;
    }

    private void addNativeAllAppsWidget() {
        HomeItem item = new HomeItem();
        item.type = HomeItem.TYPE_ALL_APPS;
        item.label = "Toutes les applications";
        item.page = currentPage;
        item.x = 0.5f;
        item.y = 0.42f;
        item.widthDp = 150;
        item.heightDp = 100;
        homeItems.add(item);
        homeStore.save(homeItems);
        renderHome(0);
    }

    private void requestSystemWidget(AppWidgetProviderInfo info) {
        int id = appWidgetHost.allocateAppWidgetId();
        pendingWidgetId = id;

        boolean allowed = false;
        try {
            allowed = appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider);
        } catch (Exception ignored) {
        }

        if (allowed) {
            configureOrFinishWidget(id);
            return;
        }

        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        try {
            startActivityForResult(bind, REQ_BIND_WIDGET);
        } catch (Exception e) {
            appWidgetHost.deleteAppWidgetId(id);
            pendingWidgetId = -1;
            toast("Impossible d'autoriser ce widget");
        }
    }

    private void configureOrFinishWidget(int id) {
        AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(id);
        if (info == null) {
            appWidgetHost.deleteAppWidgetId(id);
            pendingWidgetId = -1;
            toast("Widget indisponible");
            return;
        }

        if (info.configure != null) {
            Intent configure = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
            configure.setComponent(info.configure);
            configure.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            try {
                startActivityForResult(configure, REQ_CONFIG_WIDGET);
                return;
            } catch (Exception ignored) {
            }
        }

        finishAddingWidget(id);
    }

    private void finishAddingWidget(int id) {
        HomeItem item = new HomeItem();
        item.type = HomeItem.TYPE_WIDGET;
        item.page = currentPage;
        item.appWidgetId = id;
        item.x = 0.20f;
        item.y = 0.22f;
        item.widthDp = 260;
        item.heightDp = 160;
        AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(id);
        if (info != null && info.provider != null) item.componentName = info.provider.flattenToString();
        homeItems.add(item);
        homeStore.save(homeItems);
        pendingWidgetId = -1;
        renderHome(0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ZENO_SPEECH) {
            if(resultCode==RESULT_OK && data!=null){
                ArrayList<String> heard=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if(heard!=null && !heard.isEmpty()){
                    String phrase=heard.get(0); toast("Zeno a entendu : "+phrase);
                    handleZenoVoiceCommand(phrase);
                }
            }
        } else if (requestCode == REQ_BIND_WIDGET) {
            int id = pendingWidgetId;
            if (data != null) id = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            if (resultCode == RESULT_OK && id >= 0) configureOrFinishWidget(id);
            else cancelPendingWidget(id);
        } else if (requestCode == REQ_CONFIG_WIDGET) {
            int id = pendingWidgetId;
            if (data != null) id = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            if (resultCode == RESULT_OK && id >= 0) finishAddingWidget(id);
            else cancelPendingWidget(id);
        }
    }

    private void startZenoVoiceRecognition() {
        try {
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.FRENCH.toLanguageTag());
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Parle à "+settings.getString("zeno_name","Zeno"));
            startActivityForResult(i,REQ_ZENO_SPEECH);
        } catch(Exception e){ toast("Reconnaissance vocale indisponible sur ce téléphone"); }
    }

    private void speakAsZeno(String text) {
        if(zenoTts!=null && settings.getBoolean("zeno_voice_enabled",true))
            zenoTts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"zeno_voice");
    }

    // ZENO FINAL : commandes utiles sans remplacer les applications Android.
    // Zeno lance les actions publiques autorisées par Android et laisse chaque app gérer ses propres permissions.
    private void handleZenoVoiceCommand(String raw) {
        if (raw == null) return;
        String phrase = raw.trim();
        String lower = phrase.toLowerCase(Locale.FRENCH);

        if (lower.startsWith("recherche ") || lower.startsWith("cherche ") || lower.startsWith("rechercher ")) {
            String q = phrase.substring(phrase.indexOf(' ') + 1).trim();
            launchWebSearch(q);
            return;
        }
        if (lower.startsWith("traduis ") || lower.startsWith("traduire ")) {
            String q = phrase.substring(phrase.indexOf(' ') + 1).trim();
            launchTranslator(q);
            return;
        }
        if (lower.startsWith("gps ") || lower.startsWith("itinéraire ") || lower.startsWith("itineraire ") || lower.startsWith("va à ") || lower.startsWith("va a ")) {
            String q = phrase.substring(phrase.indexOf(' ') + 1).trim();
            launchGps(q);
            return;
        }
        if (lower.startsWith("ouvre ") || lower.startsWith("lance ")) {
            String appName = phrase.substring(phrase.indexOf(' ') + 1).trim();
            if (launchInstalledAppByName(appName)) return;
            speakAsZeno("Je ne trouve pas l'application " + appName);
            return;
        }
        speakAsZeno("J'ai entendu " + phrase);
    }

    private void launchWebSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
            i.putExtra(SearchManager.QUERY, query.trim());
            startActivity(i);
        } catch (Exception e) {
            openUrl("https://www.google.com/search?q=" + Uri.encode(query.trim()));
        }
    }

    private void launchTranslator(String text) {
        if (text == null || text.trim().isEmpty()) return;
        openUrl("https://translate.google.com/?sl=auto&tl=fr&text=" + Uri.encode(text.trim()) + "&op=translate");
    }

    private void launchGps(String place) {
        try {
            Uri uri = Uri.parse("geo:0,0?q=" + Uri.encode(place == null ? "" : place.trim()));
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            openUrl("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(place == null ? "" : place.trim()));
        }
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("Aucune application compatible disponible"); }
    }

    private boolean launchInstalledAppByName(String requested) {
        if (requested == null || requested.trim().isEmpty()) return false;
        String needle = requested.trim().toLowerCase(Locale.FRENCH);
        for (AppInfo app : allApps) {
            String label = app.label == null ? "" : app.label.toLowerCase(Locale.FRENCH);
            if (label.equals(needle) || label.contains(needle) || needle.contains(label)) {
                try {
                    Intent launch = packageManager.getLaunchIntentForPackage(app.packageName);
                    if (launch != null) { startActivity(launch); return true; }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void cancelPendingWidget(int id) {
        if (id >= 0) {
            try {
                appWidgetHost.deleteAppWidgetId(id);
            } catch (Exception ignored) {
            }
        }
        pendingWidgetId = -1;
        renderHome(0);
    }

    private void showFolder(HomeItem folder) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView title = text(folder.label.isEmpty() ? "Dossier" : folder.label, 20, Color.BLACK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        outer.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        ));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        for (String pkg : folder.folderPackages) {
            AppInfo app = findAppByComponentOrPackage("", pkg);
            if (app == null) continue;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(6), dp(8), dp(6), dp(8));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView label = text(app.label, 11, Color.DKGRAY);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            cell.addView(label, new LinearLayout.LayoutParams(dp(92), dp(38)));
            cell.setOnClickListener(v -> launchApp(app));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(104);
            lp.height = dp(104);
            grid.addView(cell, lp);
        }
        outer.addView(grid);

        new AlertDialog.Builder(this)
                .setView(outer)
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Renommer", (d, w) -> renameFolder(folder))
                .setPositiveButton("Applications", (d, w) -> editFolderApps(folder))
                .show();
    }

    private void renameFolder(HomeItem folder) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(folder.label);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Nom du dossier")
                .setView(input)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String name = input.getText().toString().trim();
                    folder.label = name.isEmpty() ? "Dossier" : name;
                    homeStore.save(homeItems);
                    renderHome(0);
                })
                .show();
    }

    private void editFolderApps(HomeItem folder) {
        String[] labels = new String[allApps.size()];
        boolean[] checked = new boolean[allApps.size()];
        for (int i = 0; i < allApps.size(); i++) {
            labels[i] = allApps.get(i).label;
            checked[i] = folder.folderPackages.contains(allApps.get(i).packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("Applications du dossier")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer", (dialog, which) -> {
                    folder.folderPackages.clear();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i] && !folder.folderPackages.contains(allApps.get(i).packageName)) {
                            folder.folderPackages.add(allApps.get(i).packageName);
                        }
                    }
                    homeStore.save(homeItems);
                    renderHome(0);
                })
                .show();
    }

    private void showHomeItemOptions(HomeItem item) {
        List<String> options = new ArrayList<>();
        options.add("Déplacer : appui long");

        if (HomeItem.TYPE_WIDGET.equals(item.type)) {
            options.add("Taille petite");
            options.add("Taille moyenne");
            options.add("Taille grande");
        } else if (HomeItem.TYPE_ALL_APPS.equals(item.type)) {
            options.add("Taille petite");
            options.add("Taille moyenne");
        } else if (HomeItem.TYPE_FOLDER.equals(item.type)) {
            options.add("Renommer");
            options.add("Applications du dossier");
        } else if (HomeItem.TYPE_APP.equals(item.type)) {
            options.add("Informations sur l'application");
        }
        options.add("Supprimer de l'accueil");

        String[] arr = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(item.label.isEmpty() ? "Élément" : item.label)
                .setItems(arr, (dialog, which) -> {
                    String selected = arr[which];
                    if (selected.startsWith("Déplacer")) {
                        toast("Maintiens l'élément puis déplace-le");
                    } else if (selected.equals("Taille petite")) {
                        resizeItem(item, false);
                    } else if (selected.equals("Taille moyenne")) {
                        if (HomeItem.TYPE_WIDGET.equals(item.type)) {
                            item.widthDp = 260;
                            item.heightDp = 160;
                        } else {
                            item.widthDp = 150;
                            item.heightDp = 100;
                        }
                        saveAndRender();
                    } else if (selected.equals("Taille grande")) {
                        item.widthDp = 330;
                        item.heightDp = 220;
                        saveAndRender();
                    } else if (selected.equals("Renommer")) {
                        renameFolder(item);
                    } else if (selected.equals("Applications du dossier")) {
                        editFolderApps(item);
                    } else if (selected.equals("Informations sur l'application")) {
                        openAppInfo(item.packageName);
                    } else if (selected.equals("Supprimer de l'accueil")) {
                        deleteHomeItem(item);
                    }
                })
                .show();
    }

    private void resizeItem(HomeItem item, boolean unused) {
        if (HomeItem.TYPE_WIDGET.equals(item.type)) {
            item.widthDp = 180;
            item.heightDp = 120;
        } else {
            item.widthDp = 118;
            item.heightDp = 84;
        }
        saveAndRender();
    }

    private void deleteHomeItem(HomeItem item) {
        if (HomeItem.TYPE_WIDGET.equals(item.type) && item.appWidgetId >= 0) {
            try {
                appWidgetHost.deleteAppWidgetId(item.appWidgetId);
            } catch (Exception ignored) {
            }
        }
        homeStore.remove(homeItems, item.id);
        saveAndRender();
    }

    private void saveAndRender() {
        homeStore.save(homeItems);
        renderHome(0);
    }

    private void showZenoEvolutionHub() {
        final String[] items = {
                "✦ Identité & nom de Zeno",
                "☺ Personnalité, humour & voix",
                "◉ Apparence, thèmes & accessoires",
                "☾ Sommeil, repos & réveil matin",
                "☄ Gestes & mouvements",
                "▦ Applications compatibles",
                "☎ Téléphone, contacts & messages",
                "☁ Chat entre membres Zeno",
                "⌖ GPS & localisation",
                "SOS Mode SOS",
                "▣ Sécurité : PIN / empreinte",
                "♡ Envies fun : manger, boire, jouer",
                "⚙ Réglages complets"
        };
        new AlertDialog.Builder(this)
                .setTitle("ZENO FINAL • Mega Evolution")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showZenoIdentitySettings(); break;
                        case 1: showZenoPersonalitySettings(); break;
                        case 2: showZenoThemeSettings(); break;
                        case 3: showZenoSleepSettings(); break;
                        case 4: showZenoGestureSettings(); break;
                        case 5: showAppsDrawer(); break;
                        case 6: showZenoModuleInfo("Téléphone / Contacts / Messages", "Architecture prête pour les commandes et intégrations Android. Les permissions seront demandées seulement quand une fonction en a besoin."); break;
                        case 7: showZenoModuleInfo("Chat entre membres Zeno", "Module communautaire prévu. Il nécessitera un service réseau et des comptes avant d'être activé."); break;
                        case 8: showZenoQuickTools(); break;
                        case 9: showZenoModuleInfo("Mode SOS", "Module d'urgence prévu avec confirmation, contacts choisis et partage de position optionnel."); break;
                        case 10: showZenoSecuritySettings(); break;
                        case 11: showZenoFunSettings(); break;
                        default: showPreferences(); break;
                    }
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void showZenoQuickTools() {
        final String[] tools = {"⌖ GPS / itinéraire", "⌕ Recherche Internet", "文 Traducteur", "🎙 Commande vocale"};
        new AlertDialog.Builder(this).setTitle("Outils Zeno").setItems(tools, (d, which) -> {
            if (which == 3) { startZenoVoiceRecognition(); return; }
            final EditText input = new EditText(this);
            input.setSingleLine(false);
            input.setHint(which == 0 ? "Lieu ou adresse" : which == 1 ? "Que veux-tu rechercher ?" : "Texte à traduire");
            new AlertDialog.Builder(this).setTitle(tools[which]).setView(input)
                    .setPositiveButton("Lancer", (x, w) -> {
                        String value = input.getText().toString();
                        if (which == 0) launchGps(value);
                        else if (which == 1) launchWebSearch(value);
                        else launchTranslator(value);
                    }).setNegativeButton("Annuler", null).show();
        }).setNegativeButton("Fermer", null).show();
    }

    private void showZenoIdentitySettings() {
        LinearLayout box = zenoSettingsBox();
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Nom de ton compagnon");
        name.setText(settings.getString("zeno_name", "Zeno"));
        box.addView(zenoSettingTitle("Nom de Zeno"));
        box.addView(name);
        box.addView(zenoSwitch("Afficher son nom", "zeno_show_name", true));
        box.addView(zenoSwitch("Cœur lumineux", "zeno_heart_enabled", true));
        box.addView(zenoSwitch("Yeux animés", "zeno_eyes_enabled", true));
        new AlertDialog.Builder(this).setTitle("Identité de Zeno").setView(box)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer", (d,w) -> {
                    String n=name.getText().toString().trim();
                    if(n.isEmpty()) n="Zeno";
                    settings.edit().putString("zeno_name", n).apply();
                    toast("Nom enregistré : " + n);
                    renderHome(0);
                }).show();
    }

    private void showZenoPersonalitySettings() {
        LinearLayout box = zenoSettingsBox();
        box.addView(zenoSwitch("Zeno parle", "zeno_voice_enabled", true));
        box.addView(zenoSwitch("Humour et blagues", "zeno_humor_enabled", true));
        box.addView(zenoSwitch("Réactions quand on l'embête", "zeno_reactions_enabled", true));
        box.addView(zenoSwitch("Mode bavard", "zeno_talkative", false));
        box.addView(zenoSwitch("Personnalité évolutive", "zeno_personality_evolves", true));
        Button listen=new Button(this); listen.setText("🎙 Parler à Zeno"); listen.setOnClickListener(v->startZenoVoiceRecognition()); box.addView(listen);
        Button test=new Button(this); test.setText("🔊 Tester sa voix"); test.setOnClickListener(v->speakAsZeno("Salut ! Je suis "+settings.getString("zeno_name","Zeno")+". On continue notre aventure ?")); box.addView(test);
        showSimpleSettingsDialog("Personnalité • Humour • Voix", box);
    }

    private void showZenoThemeSettings() {
        final String[] themes={"Classique bleu", "Coucher de soleil", "Planète violette", "Glace", "Jungle spatiale", "Océan spatial", "Nuages célestes", "Nuit galactique"};
        int checked=Math.max(0, Math.min(themes.length-1, settings.getInt("zeno_theme",0)));
        new AlertDialog.Builder(this).setTitle("Univers de Zeno")
                .setSingleChoiceItems(themes, checked, (d,w)-> {
                    settings.edit().putInt("zeno_theme",w).apply();
                    d.dismiss();
                    renderHome(0);
                })
                .setNegativeButton("Fermer", null).show();
    }

    private void addZenoThemeBackground(FrameLayout home) {
        String[] names={"zeno_theme_blue","zeno_theme_sunset","zeno_theme_violet","zeno_theme_ice",
                "zeno_theme_jungle","zeno_theme_ocean","zeno_theme_clouds","zeno_theme_galaxy"};
        int theme=Math.max(0,Math.min(names.length-1,settings.getInt("zeno_theme",0)));
        int id=getResources().getIdentifier(names[theme],"drawable",getPackageName());
        if(id==0) return;
        ImageView bg=new ImageView(this);
        bg.setImageResource(id);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0.94f);
        home.addView(bg,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showZenoSleepSettings() {
        LinearLayout box=zenoSettingsBox();
        box.addView(zenoSwitch("Repos automatique", "zeno_sleep_enabled", true));
        box.addView(zenoSwitch("Réveil au toucher", "zeno_wake_touch", true));
        box.addView(zenoSwitch("Réveil matin Zeno", "zeno_alarm_enabled", false));
        box.addView(zenoSwitch("Animation toc-toc", "zeno_alarm_knock", true));
        showSimpleSettingsDialog("Sommeil & Réveil", box);
    }

    private void showZenoGestureSettings() {
        LinearLayout box=zenoSettingsBox();
        box.addView(zenoSwitch("Priorité aux gestes sur Zeno", "zeno_touch_priority", true));
        box.addView(zenoSwitch("Glisser vers le haut : tiroir", "gesture_home_up_drawer", true));
        box.addView(zenoSwitch("Bord gauche → droite : fermer tiroir", "gesture_drawer_edge_close", true));
        box.addView(zenoSwitch("Déplacement libre de Zeno", "zeno_free_move", true));
        showSimpleSettingsDialog("Gestes personnalisables", box);
    }

    private void showZenoSecuritySettings() {
        LinearLayout box=zenoSettingsBox();
        box.addView(zenoSwitch("Verrouillage des réglages Zeno", "zeno_settings_lock", false));
        box.addView(zenoSwitch("Autoriser biométrie si disponible", "zeno_biometric_enabled", false));
        TextView note=text("La sécurité utilise les mécanismes Android. Zeno ne contourne jamais l'écran de verrouillage ni l'empreinte.",14,0xFFB8C7D9);
        note.setPadding(0,dp(12),0,0); box.addView(note);
        showSimpleSettingsDialog("Sécurité", box);
    }

    private void showZenoFunSettings() {
        LinearLayout box=zenoSettingsBox();
        box.addView(zenoSwitch("Nourriture (pour le fun)", "zeno_fun_food", true));
        box.addView(zenoSwitch("Boissons (pour le fun)", "zeno_fun_drink", true));
        box.addView(zenoSwitch("Envie de jouer", "zeno_fun_play", true));
        box.addView(zenoSwitch("Envie de discuter", "zeno_fun_chat", true));
        TextView note=text("Ces envies ne sont jamais vitales : aucune punition et aucune perte de fonction.",14,0xFFB8C7D9);
        note.setPadding(0,dp(12),0,0); box.addView(note);
        showSimpleSettingsDialog("Envies de Zeno", box);
    }

    private LinearLayout zenoSettingsBox() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(22),dp(8),dp(22),dp(8));
        return box;
    }

    private TextView zenoSettingTitle(String title) {
        TextView t=text(title,16,0xFF20252B); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0,dp(8),0,dp(6)); return t;
    }

    private Switch zenoSwitch(String label, String key, boolean def) {
        Switch sw=new Switch(this); sw.setText(label); sw.setTextSize(16); sw.setPadding(0,dp(7),0,dp(7)); sw.setChecked(settings.getBoolean(key,def));
        sw.setOnCheckedChangeListener((b,on)->settings.edit().putBoolean(key,on).apply()); return sw;
    }

    private void showSimpleSettingsDialog(String title, View content) {
        new AlertDialog.Builder(this).setTitle(title).setView(content).setPositiveButton("OK",null).show();
    }

    private void showZenoModuleInfo(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();
    }

    private void showPreferences() {
        preferencesOpen = true;
        hideAppsOpen = false;
        drawerOpen = false;
        editMode = false;
        stage.removeAllViews();

        // Pour le moment on reproduit l'écran de référence : fond blanc,
        // texte noir et accent cyan. Les couleurs seront personnalisées plus tard.
        final int bg = 0xFFF8F8F8;
        final int fg = 0xFF1D1D1D;
        final int cyan = 0xFF29B6D8;
        final int divider = 0xFFE1E1E1;

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(bg);
        page.setPadding(dp(22), dp(8), dp(22), 0);

        TextView title = text("Préférences", 29, cyan);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(82)
        );
        page.addView(title, titleLp);

        // 1) Définir comme accueil par défaut
        LinearLayout defaultRow = preferenceRow(
                "⌂",
                "Définir comme accueil par défaut",
                cyan,
                fg,
                divider
        );

        Switch homeSwitch = new Switch(this);
        homeSwitch.setChecked(isDefaultLauncher());
        homeSwitch.setClickable(false);

        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        switchLp.leftMargin = dp(8);
        defaultRow.addView(homeSwitch, switchLp);

        defaultRow.setOnClickListener(v -> openDefaultHomeSettings());
        page.addView(defaultRow);

        // 2) Écran d'accueil
        LinearLayout homeRow = preferenceRow(
                "⌂",
                "Écran d'accueil",
                cyan,
                fg,
                divider
        );
        homeRow.setOnClickListener(v -> showHomeScreenPreferences());
        page.addView(homeRow);

        // ZENO FINAL • Mega Evolution
        LinearLayout evolutionRow = preferenceRow(
                "✦",
                "Zeno Evolution",
                cyan,
                fg,
                divider
        );
        evolutionRow.setOnClickListener(v -> showZenoEvolutionHub());
        page.addView(evolutionRow);

        // 3) Masquer applis
        LinearLayout hideRow = preferenceRow(
                "▦",
                "Masquer applis",
                cyan,
                fg,
                divider
        );
        hideRow.setOnClickListener(v -> showHideAppsChooser());
        page.addView(hideRow);

        // 4) Commentaires et Aide
        LinearLayout helpRow = preferenceRow(
                "▢",
                "Commentaires et Aide",
                cyan,
                fg,
                divider
        );
        helpRow.setOnClickListener(v -> showHelpAndFeedback());
        page.addView(helpRow);

        // 5) À propos
        LinearLayout aboutRow = preferenceRow(
                "ⓘ",
                "À propos",
                cyan,
                fg,
                divider
        );
        aboutRow.setOnClickListener(v -> showAboutZeno());
        page.addView(aboutRow);

        stage.addView(
                page,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
    }

    private LinearLayout preferenceRow(
            String iconText,
            String labelText,
            int accent,
            int foreground,
            int dividerColor
    ) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xFFF8F8F8);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);

        TextView icon = text(iconText, 31, accent);
        icon.setGravity(Gravity.CENTER);
        row.addView(
                icon,
                new LinearLayout.LayoutParams(
                        dp(72),
                        dp(108)
                )
        );

        TextView label = text(labelText, 20, foreground);
        label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(
                label,
                new LinearLayout.LayoutParams(
                        0,
                        dp(108),
                        1f
                )
        );

        outer.addView(
                row,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(108)
                )
        );

        View divider = new View(this);
        divider.setBackgroundColor(dividerColor);
        outer.addView(
                divider,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                )
        );

        return outer;
    }

    private boolean isDefaultLauncher() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);

            ResolveInfo resolve = packageManager.resolveActivity(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
            );

            return resolve != null
                    && resolve.activityInfo != null
                    && getPackageName().equals(resolve.activityInfo.packageName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openDefaultHomeSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                startActivity(intent);
            } catch (Exception ignored) {
                toast("Réglage de l'accueil par défaut indisponible");
            }
        }
    }

    private void showHomeScreenPreferences() {
        String[] items = {
                "Thèmes",
                "Animations",
                "Grille du tiroir",
                "Ajouter une page",
                "Réinitialiser l'accueil"
        };

        new AlertDialog.Builder(this)
                .setTitle("Écran d'accueil")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showThemeChooser();
                    else if (which == 1) showAnimationChooser();
                    else if (which == 2) showDrawerGridChooser();
                    else if (which == 3) addHomePage();
                    else confirmResetHome();
                })
                .show();
    }

    private void showDrawerAdjustment() {
        boolean locked = settings.getBoolean("drawer_adjust_locked", false);
        String message = locked
                ? "Le tiroir est verrouillé. Déverrouille-le pour utiliser les boutons HAUT / BAS / GAUCHE / DROITE."
                : "Utilise les 4 boutons autour du verrou. Appui = réduire, appui long = agrandir. Les applications dessous sont désactivées pendant le réglage.";

        new AlertDialog.Builder(this)
                .setTitle("Ajuster l’écran tiroir")
                .setMessage(message)
                .setNeutralButton("Réinitialiser", (d, w) -> {
                    settings.edit()
                            .putInt("drawer_adjust_top", 8)
                            .putInt("drawer_adjust_bottom", 8)
                            .putInt("drawer_adjust_left", 12)
                            .putInt("drawer_adjust_right", 12)
                            .putBoolean("drawer_adjust_locked", false)
                            .putBoolean("drawer_touch_adjust_mode", false)
                            .apply();
                    Toast.makeText(this, "Réglage du tiroir réinitialisé", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .setPositiveButton(locked ? "Déverrouiller et ajuster" : "Ajuster avec boutons", (d, w) -> {
                    settings.edit()
                            .putBoolean("drawer_adjust_locked", false)
                            .putBoolean("drawer_touch_adjust_mode", true)
                            .apply();
                    showAppsDrawer();
                })
                .show();
    }

    private void showHideAppsChooser() {
        hideAppsOpen = true;
        preferencesOpen = false;
        drawerOpen = false;
        editMode = false;
        stage.removeAllViews();

        // Fond blanc totalement opaque : aucun fond d'écran visible derrière.
        final int hiddenAppsBg = Color.WHITE;
        final int foreground = 0xFF333333;
        final int cyan = 0xFF25B7E5;

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(hiddenAppsBg);
        page.setPadding(dp(18), 0, dp(18), 0);

        // En-tête : flèche retour + titre.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = text("←", 36, cyan);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showPreferences());
        header.addView(
                back,
                new LinearLayout.LayoutParams(
                        dp(62),
                        dp(78)
                )
        );

        TextView title = text("Masquer applications", 28, cyan);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        dp(78),
                        1f
                )
        );

        page.addView(
                header,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(78)
                )
        );

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(6), 0, dp(56));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);

        scroll.addView(
                grid,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        renderHiddenAppsGrid(grid, foreground, cyan);

        page.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        stage.addView(
                page,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
    }

    private void renderHiddenAppsGrid(
            GridLayout grid,
            int foreground,
            int cyan
    ) {
        grid.removeAllViews();

        Set<String> hidden = new HashSet<>(
                settings.getStringSet(
                        "hidden_apps",
                        Collections.emptySet()
                )
        );

        int usableWidth =
                getResources().getDisplayMetrics().widthPixels - dp(36);
        int cellWidth = Math.max(dp(66), usableWidth / 5);

        for (AppInfo app : allApps) {
            final boolean isHidden =
                    hidden.contains(app.packageName);

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL
            );
            cell.setPadding(
                    dp(2),
                    dp(10),
                    dp(2),
                    dp(8)
            );

            FrameLayout iconBox = new FrameLayout(this);

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);

            FrameLayout.LayoutParams iconLp =
                    new FrameLayout.LayoutParams(
                            dp(58),
                            dp(58),
                            Gravity.CENTER
                    );
            iconBox.addView(icon, iconLp);

            // Badge visibilité : bleu = visible, gris barré = masqué.
            TextView eye = text(
                    isHidden ? "⊘" : "◉",
                    isHidden ? 15 : 14,
                    Color.WHITE
            );
            eye.setGravity(Gravity.CENTER);
            eye.setBackground(
                    rounded(
                            isHidden
                                    ? 0xFF666666
                                    : cyan,
                            Color.TRANSPARENT,
                            50
                    )
            );

            FrameLayout.LayoutParams eyeLp =
                    new FrameLayout.LayoutParams(
                            dp(23),
                            dp(23),
                            Gravity.END | Gravity.BOTTOM
                    );
            eyeLp.rightMargin = dp(1);
            eyeLp.bottomMargin = dp(1);
            iconBox.addView(eye, eyeLp);

            cell.addView(
                    iconBox,
                    new LinearLayout.LayoutParams(
                            dp(66),
                            dp(66)
                    )
            );

            TextView label = text(
                    app.label,
                    12,
                    foreground
            );
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);

            LinearLayout.LayoutParams labelLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(48)
                    );
            labelLp.topMargin = dp(3);
            cell.addView(label, labelLp);

            cell.setOnClickListener(v -> {
                Set<String> updated = new HashSet<>(
                        settings.getStringSet(
                                "hidden_apps",
                                Collections.emptySet()
                        )
                );

                if (updated.contains(app.packageName)) {
                    updated.remove(app.packageName);
                } else {
                    updated.add(app.packageName);
                }

                settings.edit()
                        .putStringSet(
                                "hidden_apps",
                                updated
                        )
                        .apply();

                renderHiddenAppsGrid(
                        grid,
                        foreground,
                        cyan
                );
            });

            GridLayout.LayoutParams lp =
                    new GridLayout.LayoutParams();
            lp.width = cellWidth;
            lp.height = dp(132);

            grid.addView(cell, lp);
        }
    }

    private void showHelpAndFeedback() {
        new AlertDialog.Builder(this)
                .setTitle("Commentaires et Aide")
                .setMessage(
                        "Aide Zeno Launcher\\n\\n"
                        + "• Appui long sur l'accueil : personnalisation\\n"
                        + "• Balayage vers le haut : toutes les applications\\n"
                        + "• Appui long sur une application : options\\n"
                        + "• Menu ⋮ : grille, dossiers et actualisation"
                )
                .setPositiveButton("Fermer", null)
                .show();
    }

    private void showAboutZeno() {
        String version = "1.0";
        try {
            version = packageManager
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (Exception ignored) {
        }

        new AlertDialog.Builder(this)
                .setTitle("À propos")
                .setMessage(
                        "Zeno Launcher\\n"
                        + "Version " + version
                )
                .setPositiveButton("Fermer", null)
                .show();
    }

    private void showThemeChooser() {
        String[] themes = {"Classique", "Sombre", "Clair", "Bleu", "OLED"};
        int current = Math.max(0, Math.min(themes.length - 1, settings.getInt("theme", 0)));
        new AlertDialog.Builder(this)
                .setTitle("Thèmes")
                .setSingleChoiceItems(themes, current, (dialog, which) -> {
                    settings.edit().putInt("theme", which).apply();
                    dialog.dismiss();
                    if (drawerOpen) showAppsDrawer();
                    else renderHome(0);
                    toast("Thème appliqué : " + themes[which]);
                })
                .show();
    }

    private void showAnimationChooser() {
        String[] animations = {"Fondu", "Glissement", "Zoom", "Cube", "Rotation", "Pulse"};
        int current = Math.max(0, Math.min(5, settings.getInt("animation", 1)));
        new AlertDialog.Builder(this)
                .setTitle("Animations")
                .setSingleChoiceItems(animations, current, (dialog, which) -> {
                    settings.edit().putInt("animation", which).apply();
                    dialog.dismiss();
                    previewAnimation();
                    toast("Animation : " + animations[which]);
                })
                .show();
    }

    private void showDrawerGridChooser() {
        String[] choices = {"3 colonnes", "4 colonnes", "5 colonnes", "6 colonnes"};
        int[] values = {3, 4, 5, 6};
        int currentValue = settings.getInt("drawer_columns", 4);
        int current = Math.max(0, Math.min(3, currentValue - 3));
        new AlertDialog.Builder(this)
                .setTitle("Grille du tiroir")
                .setSingleChoiceItems(choices, current, (dialog, which) -> {
                    settings.edit().putInt("drawer_columns", values[which]).apply();
                    dialog.dismiss();
                    toast("Grille : " + choices[which]);
                })
                .show();
    }

    private void confirmResetHome() {
        new AlertDialog.Builder(this)
                .setTitle("Réinitialiser l'accueil ?")
                .setMessage("Les raccourcis, dossiers et widgets ajoutés à l'accueil seront supprimés.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Réinitialiser", (d, w) -> {
                    for (HomeItem item : new ArrayList<>(homeItems)) {
                        if (HomeItem.TYPE_WIDGET.equals(item.type) && item.appWidgetId >= 0) {
                            try { appWidgetHost.deleteAppWidgetId(item.appWidgetId); } catch (Exception ignored) {}
                        }
                    }
                    homeItems.clear();
                    HomeItem allAppsItem = new HomeItem();
                    allAppsItem.type = HomeItem.TYPE_ALL_APPS;
                    allAppsItem.label = "Toutes les applications";
                    allAppsItem.page = 0;
                    allAppsItem.x = 0.5f;
                    allAppsItem.y = 0.46f;
                    allAppsItem.widthDp = 150;
                    allAppsItem.heightDp = 100;
                    homeItems.add(allAppsItem);
                    currentPage = 0;
                    settings.edit().putInt("home_pages", 1).apply();
                    homeStore.save(homeItems);
                    renderHome(0);
                })
                .show();
    }

    private void launchApp(AppInfo app) {
        Intent launch = Intent.makeMainActivity(app.componentName);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        drawerOpen = false;

        animateOut(stage, () -> {
            try {
                startActivity(launch);
            } catch (Exception e) {
                toast("Impossible d'ouvrir " + app.label);
            }
            stage.setAlpha(1f);
            stage.setScaleX(1f);
            stage.setScaleY(1f);
            stage.setTranslationX(0f);
            stage.setRotation(0f);
            stage.setRotationY(0f);
        });
    }

    private void openAppInfo(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            toast("Informations indisponibles");
        }
    }

    private AppInfo findAppByComponentOrPackage(String componentName, String packageName) {
        if (componentName != null && !componentName.isEmpty()) {
            for (AppInfo app : allApps) {
                if (app.componentName.flattenToString().equals(componentName)) return app;
            }
        }
        if (packageName != null && !packageName.isEmpty()) {
            for (AppInfo app : allApps) {
                if (app.packageName.equals(packageName)) return app;
            }
        }
        return null;
    }

    private String appLabelForPackage(String packageName) {
        try {
            return packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private void animateIn(View view) {
        int mode = Math.max(0, Math.min(5, settings.getInt("animation", 1)));
        resetTransform(view);

        switch (mode) {
            case 0:
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(260).start();
                break;
            case 1:
                view.setTranslationY(dp(90));
                view.setAlpha(0.2f);
                view.animate().translationY(0f).alpha(1f).setDuration(300).start();
                break;
            case 2:
                view.setScaleX(0.82f);
                view.setScaleY(0.82f);
                view.setAlpha(0.25f);
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start();
                break;
            case 3:
                view.setCameraDistance(12000f * getResources().getDisplayMetrics().density);
                view.setPivotX(0f);
                view.setRotationY(58f);
                view.setAlpha(0.2f);
                view.animate().rotationY(0f).alpha(1f).setDuration(360).start();
                break;
            case 4:
                view.setRotation(-6f);
                view.setScaleX(0.9f);
                view.setScaleY(0.9f);
                view.setAlpha(0.3f);
                view.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(330).start();
                break;
            case 5:
                view.setScaleX(0.75f);
                view.setScaleY(0.75f);
                view.setAlpha(0.3f);
                view.animate().scaleX(1.06f).scaleY(1.06f).alpha(1f).setDuration(230)
                        .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                        .start();
                break;
        }
    }

    private void animatePageIn(View view, int direction) {
        int mode = Math.max(0, Math.min(5, settings.getInt("animation", 1)));
        resetTransform(view);
        switch (mode) {
            case 0:
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(220).start();
                break;
            case 1:
                view.setTranslationX(direction * dp(110));
                view.setAlpha(0.35f);
                view.animate().translationX(0f).alpha(1f).setDuration(260).start();
                break;
            case 2:
                view.setScaleX(0.88f);
                view.setScaleY(0.88f);
                view.setAlpha(0.3f);
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).start();
                break;
            case 3:
                view.setCameraDistance(12000f * getResources().getDisplayMetrics().density);
                view.setPivotX(direction > 0 ? 0f : view.getWidth());
                view.setRotationY(direction * 62f);
                view.setAlpha(0.25f);
                view.animate().rotationY(0f).alpha(1f).setDuration(330).start();
                break;
            case 4:
                view.setRotation(direction * 5f);
                view.setAlpha(0.3f);
                view.animate().rotation(0f).alpha(1f).setDuration(280).start();
                break;
            case 5:
                view.setScaleX(0.84f);
                view.setScaleY(0.84f);
                view.setAlpha(0.25f);
                view.animate().scaleX(1.04f).scaleY(1.04f).alpha(1f).setDuration(220)
                        .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(110).start())
                        .start();
                break;
        }
    }

    private void previewAnimation() {
        if (stage.getChildCount() == 0) return;
        View v = stage.getChildAt(0);
        animateIn(v);
    }

    private void animateOut(View view, Runnable end) {
        int mode = Math.max(0, Math.min(5, settings.getInt("animation", 1)));
        view.animate().cancel();
        switch (mode) {
            case 0:
                view.animate().alpha(0.15f).setDuration(150).withEndAction(end).start();
                break;
            case 1:
                view.animate().translationY(-dp(60)).alpha(0.2f).setDuration(170).withEndAction(end).start();
                break;
            case 2:
                view.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.18f).setDuration(170).withEndAction(end).start();
                break;
            case 3:
                view.setCameraDistance(12000f * getResources().getDisplayMetrics().density);
                view.setPivotX(view.getWidth());
                view.animate().rotationY(-48f).alpha(0.18f).setDuration(190).withEndAction(end).start();
                break;
            case 4:
                view.animate().rotation(5f).scaleX(0.92f).scaleY(0.92f).alpha(0.2f).setDuration(170).withEndAction(end).start();
                break;
            case 5:
                view.animate().scaleX(1.10f).scaleY(1.10f).alpha(0.18f).setDuration(170).withEndAction(end).start();
                break;
        }
    }

    private void resetTransform(View view) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setRotation(0f);
        view.setRotationY(0f);
        view.animate().setInterpolator(new AccelerateDecelerateInterpolator());
    }

    private int panelBackground() {
        switch (settings.getInt("theme", 0)) {
            case 1: return 0xFF151515;
            case 2: return 0xFFF3F3F3;
            case 3: return 0xFF10253B;
            case 4: return 0xFF000000;
            default: return 0xFFF0F0F0;
        }
    }

    private int cardBackground() {
        switch (settings.getInt("theme", 0)) {
            case 1: return 0xFF242424;
            case 2: return 0xFFFFFFFF;
            case 3: return 0xFF1C3D5C;
            case 4: return 0xFF111111;
            default: return 0xFFFFFFFF;
        }
    }

    private int foregroundColor() {
        int theme = settings.getInt("theme", 0);
        return (theme == 0 || theme == 2) ? 0xFF171717 : Color.WHITE;
    }

    private int mutedColor() {
        int theme = settings.getInt("theme", 0);
        return (theme == 0 || theme == 2) ? 0xFF6D6D6D : 0xFFB5B5B5;
    }

    private int accentColor() {
        switch (settings.getInt("theme", 0)) {
            case 1: return 0xFF64B5F6;
            case 2: return 0xFF1976D2;
            case 3: return 0xFF29B6F6;
            case 4: return 0xFF00E5FF;
            default: return 0xFF2D7DCE;
        }
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(foregroundColor());
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(rounded(cardBackground(), accentColor(), 14));
        return b;
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (Color.alpha(stroke) > 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
