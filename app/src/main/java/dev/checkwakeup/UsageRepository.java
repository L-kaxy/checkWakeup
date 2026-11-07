package dev.checkwakeup;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserManager;

import dev.checkwakeup.core.BusinessDay;
import dev.checkwakeup.core.StatsEngine;
import dev.checkwakeup.core.TimeWindow;
import dev.checkwakeup.core.UsageEvent;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

final class UsageRepository {
    private static UsageRepository instance;
    private final Context context;
    private final EventStore store;
    private List<UsageEvent> cache = new ArrayList<>();
    private long cacheStart = -1;
    private boolean cacheDirty = true;
    private long lastPrune;
    private volatile String error = "";

    static synchronized UsageRepository get(Context context) {
        if (instance == null) instance = new UsageRepository(context.getApplicationContext());
        return instance;
    }

    private UsageRepository(Context context) {
        this.context = context;
        this.store = EventStore.get(context);
    }

    static boolean hasUsagePermission(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    synchronized boolean sync(boolean fullDay) {
        if (!hasUsagePermission(context)) {
            error = "未授予使用记录权限，以下可能是旧数据";
            return false;
        }
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) {
            error = "重启后尚未首次解锁，暂时无法读取系统记录";
            return false;
        }
        long now = System.currentTimeMillis();
        long dayStart = BusinessDay.start(now, ZoneId.systemDefault());
        long lowerBound = BusinessDay.start(dayStart - 1, ZoneId.systemDefault());
        long previous = store.meta("synced_at");
        // Re-query an overlap: platform events may become visible asynchronously.
        long begin = fullDay || previous == 0 || previous > now
                ? lowerBound : Math.max(lowerBound, previous - 60_000);
        try {
            UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
            UsageEvents stream = manager == null ? null : manager.queryEvents(begin, now);
            if (stream == null) {
                error = "系统暂未提供使用记录；不能据此判断未使用";
                return false;
            }
            List<UsageEvent> received = new ArrayList<>();
            UsageEvents.Event event = new UsageEvents.Event();
            while (stream.hasNextEvent()) {
                stream.getNextEvent(event);
                if (supported(event.getEventType())) {
                    received.add(new UsageEvent(event.getTimeStamp(), event.getEventType(),
                            event.getPackageName(), event.getClassName()));
                }
            }
            if (store.save(received, now, begin) > 0) cacheDirty = true;
            if (now - lastPrune > 24 * 60 * 60 * 1000L) {
                store.prune(now - 30L * 24 * 60 * 60 * 1000);
                lastPrune = now;
                cacheDirty = true;
            }
            error = "";
            return true;
        } catch (SecurityException | IllegalStateException e) {
            error = "读取系统记录失败：" + e.getClass().getSimpleName();
            return false;
        }
    }

    private static boolean supported(int type) {
        return type == UsageEvent.RESUME || type == UsageEvent.PAUSE
                || type == UsageEvent.STOP || type == UsageEvent.SCREEN_ON
                || type == UsageEvent.SCREEN_OFF || type == UsageEvent.LOCK
                || type == UsageEvent.UNLOCK || type == UsageEvent.STARTUP
                || type == UsageEvent.SHUTDOWN;
    }

    synchronized StatsEngine.Summary summary() {
        return summaryAt(System.currentTimeMillis(), ZoneId.systemDefault());
    }

    synchronized StatsEngine.Summary refresh(boolean fullDay) {
        boolean available = sync(fullDay);
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        AppSettings settings = new AppSettings(context);
        AppSettings.TrackingConfig config = settings.tracking();
        StatsEngine.Summary result = summaryAt(now, zone, config);
        try {
            long synced = syncedAt();
            UploadManager.get(context).record(this, result, now, zone, config,
                    available && synced > 0 && synced <= now, synced > now ? 0 : synced);
        } catch (RuntimeException e) {
            // A broken/filled upload database must not stop local monitoring.
            android.util.Log.w("CheckWakeup", "Optional report queue unavailable");
        }
        return result;
    }

    private StatsEngine.Summary summaryAt(long now, ZoneId zone) {
        return summaryAt(now, zone, new AppSettings(context).tracking());
    }

    private StatsEngine.Summary summaryAt(long now, ZoneId zone, AppSettings.TrackingConfig config) {
        long begin = BusinessDay.start(BusinessDay.start(now, zone) - 1, zone);
        if (cacheDirty || cacheStart != begin) {
            cache = store.read(begin, now);
            cacheStart = begin;
            cacheDirty = false;
        }
        boolean live = error.isEmpty() && hasUsagePermission(context) && interactiveAndUnlocked();
        // Give recent lifecycle events time to settle before extending an open
        // foreground session. Closed sessions still use exact event timestamps.
        return new StatsEngine(1_500).evaluate(cache, config.target, config.windows,
                now, zone, live);
    }

    synchronized StatsEngine.Summary historical(long reference, long now, ZoneId zone,
                                               String target, List<TimeWindow> windows) {
        long begin = BusinessDay.start(BusinessDay.start(reference, zone) - 1, zone);
        return new StatsEngine(1_500).evaluateDay(store.read(begin, now), target, windows,
                reference, now, zone, interactiveAndUnlocked());
    }

    boolean interactiveAndUnlocked() {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null
                && !keyguard.isKeyguardLocked();
    }

    String error() { return error; }
    long syncedAt() { return store.meta("synced_at"); }
}
