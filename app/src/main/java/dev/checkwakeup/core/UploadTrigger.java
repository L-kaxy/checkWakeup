package dev.checkwakeup.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;

/** Meaningful changes, not a ticking calculation/sync/progress timestamp, trigger uploads. */
public final class UploadTrigger {
    public static final long HEARTBEAT_MS = 15 * 60_000L;
    private UploadTrigger() {}

    public static boolean due(String previous, String current, long now, long lastQueuedAt) {
        return !current.equals(previous) || lastQueuedAt <= 0 || now < lastQueuedAt
                || now - lastQueuedAt >= HEARTBEAT_MS;
    }

    public static String signal(StatsEngine.Summary summary, ZoneId zone, String target,
                                String label, boolean available, long syncedAt,
                                boolean monitoring, String name, String android, String os) {
        return digest(daySignal(summary, zone, target, label, available, syncedAt)
                + "|" + monitoring + "|" + ReportJson.quote(name)
                + "|" + ReportJson.APP_VERSION + "|" + ReportJson.quote(android)
                + "|" + ReportJson.quote(os));
    }

    public static String daySignal(StatsEngine.Summary summary, ZoneId zone, String target,
                                   String label, boolean available, long syncedAt) {
        StringBuilder text = new StringBuilder().append(summary.dayStart).append('|')
                .append(summary.dayEnd).append('|').append(ReportJson.quote(zone.getId()))
                .append('|').append(ReportJson.quote(target)).append('|')
                .append(ReportJson.quote(ReportJson.displayField(label, 128, "未命名 App")))
                .append('|').append(available ? "available" : syncedAt == 0 ? "unavailable" : "stale")
                .append('|').append(summary.unlocks);
        for (StatsEngine.WindowResult result : summary.windows) {
            text.append('|').append(result.window.startMinute).append(':')
                    .append(result.window.endMinute).append(':').append(result.state)
                    .append(':').append(result.qualifiedAt);
        }
        // bestContinuousMs is a diagnostic, not another per-second upload trigger.
        // Its exact current value is still included in change/heartbeat snapshots.
        return digest(text.toString());
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : hash) {
                out.append(Character.forDigit((value & 255) >>> 4, 16))
                        .append(Character.forDigit(value & 15, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
