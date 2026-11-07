package dev.checkwakeup;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import dev.checkwakeup.core.StatsEngine;
import dev.checkwakeup.core.TimeWindow;
import dev.checkwakeup.core.UploadPolicy;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppSettings settings;
    private UsageRepository repository;
    private boolean resumed;
    private boolean updatingLiveSwitch;
    private boolean updatingUploadSwitch;
    private boolean savingUpload;
    private String windowSignature = "";
    private final List<TextView> resultViews = new ArrayList<>();

    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshAsync(false); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        settings = new AppSettings(this);
        repository = UsageRepository.get(this);
        View root = findViewById(R.id.root);
        int base = dp(20);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom);
            } else {
                view.setPadding(base + insets.getSystemWindowInsetLeft(),
                        base + insets.getSystemWindowInsetTop(),
                        base + insets.getSystemWindowInsetRight(),
                        base + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
        findViewById(R.id.usage_permission).setOnClickListener(v -> openUsageSettings());
        findViewById(R.id.notification_permission).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            } else {
                openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        });
        findViewById(R.id.battery_settings).setOnClickListener(v ->
                openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.toggle_monitor).setOnClickListener(v -> toggleMonitoring());
        findViewById(R.id.select_app).setOnClickListener(v -> loadApps());
        findViewById(R.id.add_window).setOnClickListener(v -> {
            if (settings.windows().size() >= 12) {
                toast("最多配置 12 个时段");
                return;
            }
            editWindow(-1, new TimeWindow(540, 660));
        });
        findViewById(R.id.show_unlocks).setOnClickListener(v -> showUnlocks());
        Switch liveSwitch = findViewById(R.id.live_updates);
        liveSwitch.setChecked(settings.liveUpdates());
        liveSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingLiveSwitch) return;
            settings.setLiveUpdates(checked);
            changed();
        });
        findViewById(R.id.live_updates_settings).setOnClickListener(v -> {
            try {
                startActivity(new Intent(LiveUpdates.PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (ActivityNotFoundException | SecurityException e) {
                toast("系统没有提供专用入口，已转到普通通知设置");
                openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        });
        ((EditText) findViewById(R.id.upload_url)).setText(settings.uploadUrl());
        ((EditText) findViewById(R.id.upload_name)).setText(settings.uploadName());
        Switch uploadSwitch = findViewById(R.id.upload_enabled);
        uploadSwitch.setChecked(settings.uploadEnabled());
        uploadSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingUploadSwitch || savingUpload) return;
            if (checked) requestUploadSave(true);
            else saveUpload(false, settings.uploadUrl(), settings.uploadName());
        });
        findViewById(R.id.upload_save).setOnClickListener(v ->
                requestUploadSave(settings.uploadEnabled()));
        findViewById(R.id.upload_retry).setOnClickListener(v -> {
            if (!settings.uploadEnabled()) { toast("请先启用加密上报"); return; }
            io.execute(() -> {
                UploadManager.get(this).retry();
                main.post(() -> { if (!isDestroyed()) changed(); });
            });
        });
        findViewById(R.id.upload_details).setOnClickListener(v -> io.execute(() -> {
            String details = UploadManager.get(this).details();
            main.post(() -> {
                if (!resumed || isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("设备与加密信息")
                        .setMessage(details).setPositiveButton("关闭", null).show();
            });
        }));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (settings.enabled() && UsageRepository.hasUsagePermission(this)) startMonitoring();
        main.removeCallbacks(refresh);
        refreshAsync(true);
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void refreshAsync(boolean fullDay) {
        if (!resumed || io.isShutdown()) return;
        io.execute(() -> {
            try {
                // The foreground service owns refresh/reporting when it is running.
                // Rendering the dashboard must not create a second upload stream.
                StatsEngine.Summary summary = MonitorService.isRunning()
                        ? repository.summary() : repository.refresh(fullDay);
                String error = repository.error();
                long syncedAt = repository.syncedAt();
                String uploadStatus;
                try { uploadStatus = UploadManager.get(this).status(); }
                catch (RuntimeException e) { uploadStatus = "无法读取上报队列，请检查存储空间"; }
                final String reportStatus = uploadStatus;
                main.post(() -> {
                    if (!resumed || isDestroyed()) return;
                    render(summary, error, syncedAt);
                    text(R.id.upload_status).setText(reportStatus);
                    main.removeCallbacks(refresh);
                    main.postDelayed(refresh, 2_000);
                });
            } catch (RuntimeException e) {
                android.util.Log.e("CheckWakeup", "Dashboard update failed", e);
                main.post(() -> {
                    if (!resumed || isDestroyed()) return;
                    text(R.id.status).setText("本地统计读取失败，请重新打开应用");
                    main.removeCallbacks(refresh);
                    main.postDelayed(refresh, 5_000);
                });
            }
        });
    }

    private void render(StatsEngine.Summary summary, String error, long syncedAt) {
        text(R.id.day_label).setText(DisplayText.dateTime(summary.dayStart) + " — "
                + DisplayText.dateTime(summary.dayEnd) + " · 每日 06:00 换日");
        text(R.id.unlock_count).setText(summary.unlocks.size() + " 次解锁");
        text(R.id.last_unlock).setText(summary.unlocks.isEmpty() ? "当前记录中还没有解锁事件"
                : "最近一次  " + DisplayText.time(summary.unlocks.get(summary.unlocks.size() - 1)));
        boolean usage = UsageRepository.hasUsagePermission(this);
        String status = !usage ? "需要使用记录权限，才能统计解锁与 App 前台时长"
                : !error.isEmpty() ? error
                : !settings.enabled() ? "监控已暂停 · 打开本页时仍可回补系统记录"
                : MonitorService.isRunning() ? "监控中 · "
                        + (settings.uploadEnabled() ? "加密上报已启用" : "数据仅保存在本机")
                : "监控未运行 · 请点击开始监控";
        text(R.id.status).setText(status);
        ((Button) findViewById(R.id.toggle_monitor)).setText(
                settings.enabled() && MonitorService.isRunning() ? "暂停监控" : "开始监控");
        ((Button) findViewById(R.id.select_app)).setText(
                settings.targetPackage().isEmpty() ? "选择一个已安装 App" : settings.targetLabel() + "  · 更换");
        text(R.id.package_label).setText(settings.targetPackage());
        String signature = settings.targetPackage();
        for (StatsEngine.WindowResult result : summary.windows) signature += "|" + result.window.label();
        if (!signature.equals(windowSignature) || resultViews.size() != summary.windows.size()) {
            windowSignature = signature;
            rebuildWindows(summary);
        }
        for (int i = 0; i < summary.windows.size(); i++) {
            resultViews.get(i).setText(DisplayText.result(summary.windows.get(i),
                    !settings.targetPackage().isEmpty()));
        }
        boolean notifications = getSystemService(android.app.NotificationManager.class)
                .areNotificationsEnabled();
        text(R.id.diagnostics).setText("最近同步：" + (syncedAt == 0 ? "尚未同步" : DisplayText.time(syncedAt))
                + "\n通知：" + (notifications ? "已允许（锁屏显示还受系统设置控制）" : "未允许")
                + "\n系统历史保留时间有限，回补不保证完整。多开 / 工作资料 / 分屏需真机验证。");
        updatingLiveSwitch = true;
        ((Switch) findViewById(R.id.live_updates)).setChecked(settings.liveUpdates());
        updatingLiveSwitch = false;
        text(R.id.live_updates_diagnostics).setText(LiveUpdates.diagnostics(this));
        if (!savingUpload) resetUploadSwitch();
    }

    private void resetUploadSwitch() {
        updatingUploadSwitch = true;
        ((Switch) findViewById(R.id.upload_enabled)).setChecked(settings.uploadEnabled());
        updatingUploadSwitch = false;
    }

    private void requestUploadSave(boolean enabled) {
        if (savingUpload) return;
        resetUploadSwitch();
        String url, name;
        try {
            url = UploadPolicy.endpoint(text(R.id.upload_url).getText().toString());
            name = UploadPolicy.deviceName(text(R.id.upload_name).getText().toString());
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return;
        }
        if (enabled && (!settings.uploadEnabled() || !url.equals(settings.uploadUrl()))) {
            new AlertDialog.Builder(this).setTitle("启用加密上报？")
                    .setMessage("将向以下地址发送当前统计日的解锁时间明细、目标 App、"
                            + "时段状态、设备名称及随机设备码；之后数据变化时上报，"
                            + "无变化每 15 分钟心跳，不影响本地统计精度。"
                            + "所有业务参数均在加密正文内。\n\n" + url
                            + "\n\n关闭会清除本机待传队列，但不会删除后台已收到的数据。"
                            + "更换地址会清空旧地址的待传队列。")
                    .setNegativeButton("取消", (d, w) -> resetUploadSwitch())
                    .setOnCancelListener(d -> resetUploadSwitch())
                    .setPositiveButton("同意并启用", (d, w) -> saveUpload(true, url, name)).show();
        } else saveUpload(enabled, url, name);
    }

    private void saveUpload(boolean enabled, String url, String name) {
        savingUpload = true;
        findViewById(R.id.upload_enabled).setEnabled(false);
        findViewById(R.id.upload_save).setEnabled(false);
        findViewById(R.id.upload_url).setEnabled(false);
        findViewById(R.id.upload_name).setEnabled(false);
        io.execute(() -> {
            String failure = "";
            try { UploadManager.get(this).configure(enabled, url, name); }
            catch (RuntimeException e) {
                failure = e instanceof IllegalArgumentException ? e.getMessage() : "无法保存上报设置";
            }
            String message = failure;
            main.post(() -> {
                if (isDestroyed()) return;
                savingUpload = false;
                findViewById(R.id.upload_enabled).setEnabled(true);
                findViewById(R.id.upload_save).setEnabled(true);
                findViewById(R.id.upload_url).setEnabled(true);
                findViewById(R.id.upload_name).setEnabled(true);
                resetUploadSwitch();
                if (!message.isEmpty()) toast(message);
                else {
                    text(R.id.upload_url).setText(settings.uploadUrl());
                    text(R.id.upload_name).setText(settings.uploadName());
                    toast(enabled ? "已保存 · 变化时上报，无变化每 15 分钟心跳"
                            : "上报已关闭，本地监控不受影响");
                }
                changed();
            });
        });
    }

    private void rebuildWindows(StatsEngine.Summary summary) {
        LinearLayout container = findViewById(R.id.windows);
        container.removeAllViews();
        resultViews.clear();
        for (int i = 0; i < summary.windows.size(); i++) {
            int index = i;
            TimeWindow window = summary.windows.get(i).window;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            margin.bottomMargin = dp(8);
            container.addView(row, margin);
            TextView heading = new TextView(this);
            heading.setText(window.label());
            heading.setTextSize(18);
            heading.setTextColor(0xFF183E36);
            row.addView(heading);
            TextView result = new TextView(this);
            result.setTextSize(14);
            result.setTextColor(0xFF23675B);
            result.setPadding(0, dp(6), 0, dp(6));
            row.addView(result);
            resultViews.add(result);
            LinearLayout buttons = new LinearLayout(this);
            Button edit = new Button(this);
            edit.setText("编辑");
            edit.setOnClickListener(v -> editWindow(index, window));
            Button delete = new Button(this);
            delete.setText("删除");
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("删除 " + window.label() + "？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        List<TimeWindow> windows = settings.windows();
                        if (index < windows.size()) {
                            windows.remove(index);
                            settings.setWindows(windows);
                            changed();
                        }
                    }).show());
            buttons.addView(edit);
            buttons.addView(delete);
            row.addView(buttons);
        }
    }

    private void editWindow(int index, TimeWindow original) {
        new TimePickerDialog(this, (startPicker, startHour, startMinute) -> {
            int start = startHour * 60 + startMinute;
            new TimePickerDialog(this, (endPicker, endHour, endMinute) -> {
                try {
                    TimeWindow window = new TimeWindow(start, endHour * 60 + endMinute);
                    List<TimeWindow> windows = settings.windows();
                    for (int i = 0; i < windows.size(); i++) {
                        TimeWindow existing = windows.get(i);
                        if (i != index && existing.startMinute == window.startMinute
                                && existing.endMinute == window.endMinute) {
                            toast("该时段已存在");
                            return;
                        }
                    }
                    if (index < 0) windows.add(window);
                    else if (index < windows.size()) windows.set(index, window);
                    settings.setWindows(windows);
                    changed();
                } catch (IllegalArgumentException e) {
                    toast(e.getMessage());
                }
            }, original.endMinute / 60, original.endMinute % 60, true).show();
        }, original.startMinute / 60, original.startMinute % 60, true).show();
    }

    private void toggleMonitoring() {
        if (settings.enabled() && MonitorService.isRunning()) {
            settings.setEnabled(false);
            stopService(new Intent(this, MonitorService.class));
            changed();
        } else {
            settings.setEnabled(true);
            if (!UsageRepository.hasUsagePermission(this)) {
                openUsageSettings();
                return;
            }
            startMonitoring();
            changed();
        }
    }

    private void startMonitoring() {
        try {
            startForegroundService(new Intent(this, MonitorService.class)
                    .setAction(MonitorService.ACTION_REFRESH));
        } catch (IllegalStateException | SecurityException e) {
            toast("系统未允许后台监控，请检查应用与电池设置");
        }
    }

    private void changed() {
        if (settings.enabled() && UsageRepository.hasUsagePermission(this)) startMonitoring();
        main.removeCallbacks(refresh);
        refreshAsync(true);
    }

    private void openUsageSettings() {
        openSettings(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                toast("请手动打开系统设置");
            }
        }
    }

    private void showUnlocks() {
        io.execute(() -> {
            StatsEngine.Summary summary = repository.summary();
            String[] labels = new String[summary.unlocks.size()];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = (labels.length - i) + "  ·  "
                        + DisplayText.time(summary.unlocks.get(labels.length - 1 - i));
            }
            main.post(() -> {
                if (!resumed || isDestroyed()) return;
                AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                        .setTitle("今日解锁记录 · " + labels.length + " 次")
                        .setPositiveButton("关闭", null);
                if (labels.length == 0) dialog.setMessage("当前记录中没有解锁事件。请确认使用记录权限已开启。");
                else dialog.setItems(labels, null);
                dialog.show();
            });
        });
    }

    private static final class AppChoice {
        final String packageName;
        final String label;
        AppChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
        @Override public String toString() { return label + "\n" + packageName; }
    }

    private void loadApps() {
        toast("正在读取可启动的已安装应用…");
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
            List<AppChoice> apps = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : infos) {
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && seen.add(pkg)) {
                    apps.add(new AppChoice(pkg, info.loadLabel(pm).toString()));
                }
            }
            Collator collator = Collator.getInstance(Locale.CHINA);
            apps.sort((a, b) -> collator.compare(a.label, b.label));
            main.post(() -> {
                if (!resumed || isDestroyed()) return;
                showAppPicker(apps);
            });
        });
    }

    private void showAppPicker(List<AppChoice> apps) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索名称或包名");
        search.setContentDescription("搜索已安装应用");
        content.addView(search);
        ListView list = new ListView(this);
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340)));
        List<AppChoice> filtered = new ArrayList<>(apps);
        ArrayAdapter<AppChoice> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, filtered);
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择目标 App")
                .setView(content).setNegativeButton("取消", null)
                .setNeutralButton("取消目标", (d, which) -> {
                    settings.setTarget("", "未选择");
                    changed();
                }).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppChoice app = adapter.getItem(position);
            if (app != null) settings.setTarget(app.packageName, app.label);
            dialog.dismiss();
            changed();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase(Locale.ROOT);
                adapter.clear();
                for (AppChoice app : apps) {
                    if (app.label.toLowerCase(Locale.ROOT).contains(query)
                            || app.packageName.toLowerCase(Locale.ROOT).contains(query)) adapter.add(app);
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        dialog.show();
    }

    private TextView text(int id) { return findViewById(id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
