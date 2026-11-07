package dev.checkwakeup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import dev.checkwakeup.core.NotificationText;
import dev.checkwakeup.core.StatsEngine;

import java.time.ZoneId;

final class StatusNotification {
    static final int ID = 100;
    private static final String CHANNEL = "daily_status";

    private StatusNotification() {}

    static Notification create(Context context, StatsEngine.Summary summary, String error) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "每日统计",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("解锁次数与目标 App 时段检查；可在锁屏显示");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AppSettings settings = new AppSettings(context);
        String title = "今日解锁 " + summary.unlocks.size() + " 次";
        if (!error.isEmpty()) title = "统计待更新 · 请检查使用权限";
        String shortText = settings.targetPackage().isEmpty() ? "未选择目标 App"
                : settings.targetLabel() + " · 完成 " + summary.doneCount() + "/" + summary.windows.size();
        StringBuilder expanded = new StringBuilder(shortText);
        if (!summary.unlocks.isEmpty()) {
            expanded.append("\n最近解锁 ").append(DisplayText.time(
                    summary.unlocks.get(summary.unlocks.size() - 1)));
        }
        for (StatsEngine.WindowResult result : summary.windows) {
            expanded.append("\n").append(result.window.label()).append("  ")
                    .append(result.state == StatsEngine.State.PENDING
                            && !settings.targetPackage().isEmpty() ? "待完成"
                            : DisplayText.result(result, !settings.targetPackage().isEmpty()));
        }
        expanded.append("\n统计日 06:00–次日 06:00");
        if (!error.isEmpty()) expanded.append("\n").append(error);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_unlock)
                .setContentTitle(settings.liveUpdates() && error.isEmpty()
                        ? NotificationText.title(summary, ZoneId.systemDefault()) : title)
                .setContentText(settings.liveUpdates()
                        ? NotificationText.windows(summary, !settings.targetPackage().isEmpty()) : shortText)
                .setStyle(new Notification.BigTextStyle().bigText(expanded.toString()))
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setColor(0xFF23675B);
        if (settings.liveUpdates()) {
            Intent dismissed = new Intent(context, NotificationDismissReceiver.class)
                    .setAction(NotificationDismissReceiver.ACTION)
                    .setData(Uri.parse("checkwakeup://notification/" + settings.notificationGeneration()))
                    .putExtra(NotificationDismissReceiver.GENERATION, settings.notificationGeneration());
            builder.setDeleteIntent(PendingIntent.getBroadcast(context, 0, dismissed,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            LiveUpdates.request(builder);
        }
        return builder.build();
    }
}
