package dev.checkwakeup.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

/** Dependency-free JSON writer shared by Android and the protocol tests. */
public final class ReportJson {
    private static final DateTimeFormatter UTC =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    public static final String APP_VERSION = GeneratedAppVersion.NAME;
    private ReportJson() {}

    public static String timestamp(long millis) { return UTC.format(Instant.ofEpochMilli(millis)); }

    public static String displayField(String text, int max, String fallback) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < text.length() && count < max; count++) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            out.appendCodePoint(Character.isISOControl(cp) || (cp >= 0xD800 && cp <= 0xDFFF)
                    ? ' ' : cp);
        }
        String value = out.toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    public static String quote(String text) {
        if (text == null) return "null";
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32 || (Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && i + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(i + 1))))) {
                        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        result.append(c);
                        if (Character.isHighSurrogate(c)) result.append(text.charAt(++i));
                    }
            }
        }
        return result.append('"').toString();
    }

    public static String day(StatsEngine.Summary summary, long reference, long calculatedAt,
                             ZoneId zone, long revision, String target, String label,
                             boolean available, long syncedAt) {
        String quality = available ? "available" : syncedAt == 0 ? "unavailable" : "stale";
        StringBuilder out = new StringBuilder("{\"business_date\":")
                .append(quote(BusinessDay.date(reference, zone).toString()))
                .append(",\"timezone\":").append(quote(zone.getId()))
                .append(",\"day_start_at\":").append(quote(timestamp(summary.dayStart)))
                .append(",\"day_end_at\":").append(quote(timestamp(summary.dayEnd)))
                .append(",\"revision\":").append(revision)
                .append(",\"calculated_at\":").append(quote(timestamp(calculatedAt)))
                .append(",\"data_quality\":{\"status\":").append(quote(quality))
                .append(",\"last_successful_sync_at\":")
                .append(syncedAt == 0 ? "null" : quote(timestamp(syncedAt)))
                .append(",\"error_code\":").append(available ? "null" : "\"USAGE_UNAVAILABLE\"")
                .append("},\"unlock_count\":").append(summary.unlocks.size())
                .append(",\"unlock_times\":[");
        for (int i = 0; i < summary.unlocks.size(); i++) {
            if (i != 0) out.append(',');
            out.append(quote(timestamp(summary.unlocks.get(i))));
        }
        out.append("],\"target_app\":");
        out.append(target.isEmpty() ? "null" : "{\"package_name\":" + quote(target)
                + ",\"label\":" + quote(displayField(label, 128, "未命名 App")) + "}");
        out.append(",\"windows\":[");
        for (int i = 0; i < summary.windows.size(); i++) {
            if (i != 0) out.append(',');
            StatsEngine.WindowResult result = summary.windows.get(i);
            TimeWindow window = result.window;
            long[] bounds = BusinessDay.bounds(reference, zone, window);
            // Include the index to allow intentionally duplicated intervals.
            out.append("{\"window_id\":").append(quote("w_" + i + "_"
                            + window.startMinute + "_" + window.endMinute))
                    .append(",\"start_minute\":").append(window.startMinute)
                    .append(",\"end_minute\":").append(window.endMinute)
                    .append(",\"start_at\":").append(quote(timestamp(bounds[0])))
                    .append(",\"end_at\":").append(quote(timestamp(bounds[1])))
                    .append(",\"state\":").append(quote(result.state.name()))
                    .append(",\"qualified_at\":").append(result.qualifiedAt < 0 ? "null"
                            : quote(timestamp(result.qualifiedAt)))
                    .append(",\"best_continuous_ms\":").append(result.bestContinuousMs).append('}');
        }
        return out.append("]}").toString();
    }

    public static String report(String id, long sequence, String device, String name,
                                long now, boolean monitoring, String androidVersion,
                                String osVersion, List<String> days) {
        return "{\"schema_version\":1,\"report_id\":" + quote(id)
                + ",\"sequence\":" + sequence + ",\"device_code\":" + quote(device)
                + ",\"generated_at\":" + quote(timestamp(now))
                + ",\"device_name\":" + quote(name)
                + ",\"client\":{\"package_name\":\"dev.checkwakeup\",\"app_version\":"
                + quote(APP_VERSION) + ",\"android_version\":" + quote(displayField(androidVersion, 128, ""))
                + ",\"os_version\":" + quote(displayField(osVersion, 128, "")) + "},\"monitoring_enabled\":"
                + monitoring + ",\"days\":[" + String.join(",", days) + "]}";
    }
}
