package dev.checkwakeup;

import dev.checkwakeup.core.StatsEngine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class DisplayText {
    private DisplayText() {}

    static String time(long timestamp) {
        return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
    }

    static String dateTime(long timestamp) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
    }

    static String result(StatsEngine.WindowResult result, boolean hasTarget) {
        if (!hasTarget) return "未选择 App";
        return switch (result.state) {
            case DONE -> "✓ 已完成 · " + time(result.qualifiedAt);
            case UPCOMING -> "待开始";
            case PENDING -> "待完成 · 最长连续 " + Math.min(10, result.bestContinuousMs / 1000) + "/10 秒";
            case MISSED -> "未达标（已记录数据中）";
        };
    }
}
