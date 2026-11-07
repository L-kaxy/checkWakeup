package dev.checkwakeup.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public final class BusinessDay {
    private BusinessDay() {}

    public static LocalDate date(long now, ZoneId zone) {
        var local = Instant.ofEpochMilli(now).atZone(zone);
        return local.toLocalTime().isBefore(LocalTime.of(6, 0))
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    public static long start(long now, ZoneId zone) {
        return boundary(date(now, zone), zone);
    }

    public static long end(long now, ZoneId zone) {
        return boundary(date(now, zone).plusDays(1), zone);
    }

    private static long boundary(LocalDate date, ZoneId zone) {
        return date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /**
     * A window starts on its named clock time within this business day.
     * Overnight windows are clipped at the next 06:00 reset.
     * E.g. 23:00–01:00 is entirely inside one business day.
     */
    public static long[] bounds(long dayReference, ZoneId zone, TimeWindow window) {
        LocalDate day = date(dayReference, zone);
        LocalDate startDate = window.startMinute < 360 ? day.plusDays(1) : day;
        LocalDate endDate = startDate;
        if (window.endMinute <= window.startMinute) endDate = endDate.plusDays(1);
        long start = startDate.atTime(window.startMinute / 60, window.startMinute % 60)
                .atZone(zone).toInstant().toEpochMilli();
        long end = endDate.atTime(window.endMinute / 60, window.endMinute % 60)
                .atZone(zone).toInstant().toEpochMilli();
        return new long[]{Math.max(start, start(dayReference, zone)),
                Math.min(end, end(dayReference, zone))};
    }
}
