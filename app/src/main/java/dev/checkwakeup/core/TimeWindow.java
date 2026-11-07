package dev.checkwakeup.core;

import java.util.Locale;

public final class TimeWindow {
    public final int startMinute;
    public final int endMinute;

    public TimeWindow(int startMinute, int endMinute) {
        if (startMinute < 0 || startMinute >= 1440 || endMinute < 0 || endMinute >= 1440
                || startMinute == endMinute) {
            throw new IllegalArgumentException("时段必须是两个不同的有效时间");
        }
        int offset = (startMinute - 360 + 1440) % 1440;
        int duration = (endMinute - startMinute + 1440) % 1440;
        if (offset + duration > 1440) {
            throw new IllegalArgumentException("时段不能跨过 06:00 换日，请拆成两个时段");
        }
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    public static String clock(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    public String label() {
        return clock(startMinute) + "–" + clock(endMinute)
                + (endMinute < startMinute ? "（跨夜）" : "");
    }
}
