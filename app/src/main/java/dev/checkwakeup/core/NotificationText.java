package dev.checkwakeup.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Compact fallback text for the experiment, independent of Android UI classes. */
public final class NotificationText {
    private NotificationText() {}

    public static String title(StatsEngine.Summary summary, ZoneId zone) {
        String last = summary.unlocks.isEmpty() ? "暂无"
                : DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(
                        Instant.ofEpochMilli(summary.unlocks.get(summary.unlocks.size() - 1)));
        return "解锁 " + summary.unlocks.size() + " 次 · 最近 " + last;
    }

    public static String windows(StatsEngine.Summary summary, boolean hasTarget) {
        if (!hasTarget) return "未选择目标 App";
        if (summary.windows.isEmpty()) return "未配置时段";
        StringBuilder text = new StringBuilder();
        for (StatsEngine.WindowResult result : summary.windows) {
            if (text.length() > 0) text.append(" | ");
            text.append(clock(result.window.startMinute)).append('–')
                    .append(clock(result.window.endMinute));
            text.append(switch (result.state) {
                case DONE -> "✓";
                case UPCOMING -> "○";
                case PENDING -> "…";
                case MISSED -> "×";
            });
        }
        return text.toString();
    }

    private static String clock(int minutes) {
        return minutes % 60 == 0 ? String.format(java.util.Locale.ROOT, "%02d", minutes / 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
