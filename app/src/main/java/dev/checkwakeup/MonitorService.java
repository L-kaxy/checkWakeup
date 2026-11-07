package dev.checkwakeup;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import dev.checkwakeup.core.BusinessDay;
import dev.checkwakeup.core.StatsEngine;

import java.time.ZoneId;

public final class MonitorService extends Service {
    static final String ACTION_REFRESH = "dev.checkwakeup.REFRESH";
    private HandlerThread thread;
    private Handler worker;
    private UsageRepository repository;
    private volatile boolean destroyed;
    private boolean fullSync = true;
    private long lastFullSync;
    private String lastNotification = "";
    private StatsEngine.Summary lastSummary;
    private static volatile boolean running;

    static boolean isRunning() { return running; }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (worker == null || destroyed) return;
            worker.post(() -> {
                fullSync = true;
                worker.removeCallbacks(tick);
                worker.postDelayed(tick, 350);
            });
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try {
                long now = System.currentTimeMillis();
                boolean rescan = fullSync || now - lastFullSync >= 15 * 60_000;
                StatsEngine.Summary summary = repository.refresh(rescan);
                if (repository.error().isEmpty() && rescan) lastFullSync = now;
                fullSync = false;
                lastSummary = summary;
                // Only repost when the visible contents change (no per-second
                // notification churn while a session's progress is ticking).
                String key = notificationKey(summary, repository.error());
                if (!key.equals(lastNotification)) {
                    getSystemService(NotificationManager.class).notify(StatusNotification.ID,
                            StatusNotification.create(MonitorService.this, summary, repository.error()));
                    lastNotification = key;
                }
            } catch (RuntimeException e) {
                // Keep the foreground notification truthful if storage/OS access fails.
                android.util.Log.e("CheckWakeup", "Monitor update failed", e);
                if (lastSummary != null) {
                    getSystemService(NotificationManager.class).notify(StatusNotification.ID,
                            StatusNotification.create(MonitorService.this, lastSummary,
                                    "统计更新失败，请打开应用检查"));
                }
            } finally {
                if (!destroyed) {
                    long now = System.currentTimeMillis();
                    long untilReset = BusinessDay.end(now, ZoneId.systemDefault()) - now + 100;
                    long delay = repository.interactiveAndUnlocked() ? 2_000 : 60_000;
                    worker.postDelayed(this, Math.max(250, Math.min(delay, untilReset)));
                }
            }
        }
    };

    private String notificationKey(StatsEngine.Summary summary, String error) {
        AppSettings settings = new AppSettings(this);
        StringBuilder key = new StringBuilder(error).append(summary.dayStart)
                .append(':').append(summary.unlocks).append(settings.targetPackage())
                .append(settings.targetLabel()).append(settings.liveUpdates())
                .append(settings.notificationGeneration());
        for (StatsEngine.WindowResult result : summary.windows) {
            key.append(result.window.label()).append(result.state).append(result.qualifiedAt);
        }
        return key.toString();
    }

    @Override public void onCreate() {
        super.onCreate();
        repository = UsageRepository.get(this);
        lastSummary = repository.summary();
        Notification initial = StatusNotification.create(this, lastSummary, repository.error());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(StatusNotification.ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(StatusNotification.ID, initial);
        }
        running = true;
        thread = new HandlerThread("usage-monitor");
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new AppSettings(this).enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        worker.post(() -> {
            fullSync = true;
            worker.removeCallbacks(tick);
            worker.post(tick);
        });
        return START_STICKY;
    }

    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        unregisterReceiver(receiver);
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
