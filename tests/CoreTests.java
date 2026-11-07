package dev.checkwakeup.core;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Tiny test runner; intentionally no JUnit or Android dependencies. */
public final class CoreTests {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TARGET = "example.target";
    private static final List<TimeWindow> WINDOWS = Arrays.asList(
            new TimeWindow(540, 660), new TimeWindow(900, 1020), new TimeWindow(1200, 1320));
    private static int passed;

    private CoreTests() {}

    public static void main(String[] args) {
        test("empty data never qualifies", () -> check(
                summary("2026-11-06T10:00:00").doneCount() == 0));
        test("exactly 10 seconds qualifies", () -> check(first(run(
                "2026-11-06T09:00:10", resume("09:00:00"))).state == StatsEngine.State.DONE));
        test("9.999 seconds does not qualify", () -> check(first(run(
                "2026-11-06T09:00:09.999", resume("09:00:00"))).state == StatsEngine.State.PENDING));
        test("qualification timestamp is start plus 10 seconds", () -> check(first(run(
                "2026-11-06T10:00:00", resume("09:02:00"), pause("09:02:20"))).qualifiedAt
                == at("2026-11-06T09:02:10")));
        test("6 and 5 seconds separated by background are not accumulated", () -> check(first(run(
                "2026-11-06T09:01:00", resume("09:00:00"), pause("09:00:06"),
                other("09:00:06.010"), resume("09:00:10"), pause("09:00:15")))
                .state == StatsEngine.State.PENDING));
        test("same-app page changes do not reset a session", () -> check(first(run(
                "2026-11-06T09:01:00", resume("09:00:00"), pause("09:00:06"),
                event("09:00:06.100", UsageEvent.RESUME, TARGET, "B"),
                event("09:00:11.100", UsageEvent.PAUSE, TARGET, "B")))
                .state == StatsEngine.State.DONE));
        test("long gap inside same package is not bridged", () -> check(first(run(
                "2026-11-06T09:01:00", resume("09:00:00"), pause("09:00:06"),
                resume("09:00:07"), pause("09:00:12"))).state == StatsEngine.State.PENDING));
        test("another package breaks even a short transition gap", () -> check(first(run(
                "2026-11-06T09:01:00", resume("09:00:00"), pause("09:00:06"),
                other("09:00:06.010"), resume("09:00:06.100"), pause("09:00:11")))
                .state == StatsEngine.State.PENDING));
        test("repeated resumes within app do not count as new launch", () -> check(first(run(
                "2026-11-06T09:00:11", resume("09:00:00"), resume("09:00:06")))
                .state == StatsEngine.State.DONE));
        test("old page STOP after new page RESUME is ignored", () -> check(first(run(
                "2026-11-06T09:00:11", resume("09:00:00"),
                event("09:00:06", UsageEvent.RESUME, TARGET, "B"),
                event("09:00:06.100", UsageEvent.STOP, TARGET, "A")))
                .state == StatsEngine.State.DONE));
        test("old instance STOP cannot close resumed replacement of same class", () -> check(first(run(
                "2026-11-06T09:00:11", resume("09:00:00"), pause("09:00:06"),
                resume("09:00:06.100"), event("09:00:06.200", UsageEvent.STOP, TARGET, "A")))
                .state == StatsEngine.State.DONE));
        test("pending pause never counts as foreground", () -> check(first(run(
                "2026-11-06T09:00:10.200", resume("09:00:00"), pause("09:00:09.900")))
                .state == StatsEngine.State.PENDING));
        test("screen off ends foreground", () -> check(first(run(
                "2026-11-06T09:01:00", resume("09:00:00"),
                system("09:00:06", UsageEvent.SCREEN_OFF))).state == StatsEngine.State.PENDING));
        test("keyguard ends foreground", () -> check(first(run(
                "2026-11-06T09:01:00", resume("09:00:00"),
                system("09:00:06", UsageEvent.LOCK))).state == StatsEngine.State.PENDING));
        test("shutdown ends foreground", () -> check(first(run(
                "2026-11-06T09:01:00", resume("09:00:00"),
                system("09:00:06", UsageEvent.SHUTDOWN))).state == StatsEngine.State.PENDING));
        test("background-only package does not qualify", () -> check(first(run(
                "2026-11-06T09:01:00", other("09:00:00"), pause("09:00:20")))
                .state == StatsEngine.State.PENDING));
        test("session crossing window start counts only overlap", () -> check(first(run(
                "2026-11-06T09:00:09", resume("08:59:00"))).state == StatsEngine.State.PENDING));
        test("session already open at window start qualifies at 09:00:10", () -> check(first(run(
                "2026-11-06T09:00:10", resume("08:59:00"))).qualifiedAt
                == at("2026-11-06T09:00:10")));
        test("session crossing window end is clipped", () -> check(first(run(
                "2026-11-06T11:00:30", resume("10:59:55"))).state == StatsEngine.State.MISSED));
        test("10-second interval ending exactly at boundary qualifies", () -> check(first(run(
                "2026-11-06T11:00:00", resume("10:59:50"))).state == StatsEngine.State.DONE));
        test("event at window end does not count", () -> check(first(run(
                "2026-11-06T11:01:00", resume("11:00:00"))).state == StatsEngine.State.MISSED));
        test("three windows are independent", () -> {
            StatsEngine.Summary s = run("2026-11-06T21:00:00",
                    resume("09:01:00"), pause("09:01:10"),
                    resume("15:01:00"), pause("15:01:09"),
                    resume("20:01:00"), pause("20:01:10"));
            check(s.doneCount() == 2 && s.windows.get(1).state == StatsEngine.State.MISSED);
        });
        test("upcoming state", () -> check(summary("2026-11-06T08:00:00")
                .windows.get(0).state == StatsEngine.State.UPCOMING));
        test("earliest successful session is retained", () -> check(first(run(
                "2026-11-06T10:00:00", resume("09:01:00"), pause("09:01:10"),
                resume("09:02:00"), pause("09:02:30"))).qualifiedAt
                == at("2026-11-06T09:01:10")));
        test("before 06:00 belongs to previous day", () -> check(BusinessDay.start(
                at("2026-11-07T05:59:59"), ZONE) == at("2026-11-06T06:00:00")));
        test("06:00 starts the next day", () -> check(BusinessDay.start(
                at("2026-11-07T06:00:00"), ZONE) == at("2026-11-07T06:00:00")));
        test("previous-day completion resets at 06:00", () -> check(run(
                "2026-11-07T06:00:00", resume("09:00:00"), pause("09:00:10")).doneCount() == 0));
        test("unlock boundary is inclusive at 06:00", () -> {
            StatsEngine.Summary s = run("2026-11-06T07:00:00",
                    system("05:59:59", UsageEvent.UNLOCK), system("05:59:59.500", UsageEvent.LOCK),
                    system("06:00:00", UsageEvent.UNLOCK));
            check(s.unlocks.size() == 1 && s.unlocks.get(0) == at("2026-11-06T06:00:00"));
        });
        test("screen-on alone is not an unlock", () -> check(run("2026-11-06T10:00:00",
                system("09:00:00", UsageEvent.SCREEN_ON)).unlocks.isEmpty()));
        test("duplicate unlock events are deduplicated", () -> check(run("2026-11-06T10:00:00",
                system("09:00:00", UsageEvent.UNLOCK), system("09:00:00", UsageEvent.UNLOCK))
                .unlocks.size() == 1));
        test("two actual unlocks are counted", () -> check(run("2026-11-06T10:00:00",
                system("09:00:00", UsageEvent.UNLOCK), system("09:01:00", UsageEvent.LOCK),
                system("09:02:00", UsageEvent.UNLOCK)).unlocks.size() == 2));
        test("screen off recovers unlock count if OEM omits LOCK event", () -> check(run(
                "2026-11-06T10:00:00", system("09:00:00", UsageEvent.UNLOCK),
                system("09:01:00", UsageEvent.SCREEN_OFF),
                system("09:02:00", UsageEvent.UNLOCK)).unlocks.size() == 2));
        test("resume before keyguard dismissal starts counting only after unlock", () -> {
            StatsEngine.Summary s = run("2026-11-06T09:00:20",
                    system("08:59:00", UsageEvent.LOCK), resume("09:00:00"),
                    system("09:00:05", UsageEvent.UNLOCK));
            check(first(s).qualifiedAt == at("2026-11-06T09:00:15"));
        });
        test("no counting while target is behind keyguard", () -> check(first(run(
                "2026-11-06T09:00:20", system("08:59:00", UsageEvent.LOCK),
                resume("09:00:00"))).state == StatsEngine.State.PENDING));
        test("live lock gate prevents optimistic tail completion", () -> check(first(
                new StatsEngine().evaluate(List.of(resume("09:00:00")), TARGET, WINDOWS,
                        at("2026-11-06T09:01:00"), ZONE, false)).state == StatsEngine.State.PENDING));
        test("closed historical session still qualifies when phone is locked", () -> check(first(
                new StatsEngine().evaluate(List.of(resume("09:00:00"), pause("09:00:10")),
                        TARGET, WINDOWS, at("2026-11-06T09:01:00"), ZONE, false))
                .state == StatsEngine.State.DONE));
        test("live settling delay defers rather than changes duration", () -> {
            StatsEngine engine = new StatsEngine(1500);
            check(first(engine.evaluate(List.of(resume("09:00:00")), TARGET, WINDOWS,
                    at("2026-11-06T09:00:11"), ZONE, true)).state == StatsEngine.State.PENDING);
            check(first(engine.evaluate(List.of(resume("09:00:00")), TARGET, WINDOWS,
                    at("2026-11-06T09:00:11.500"), ZONE, true)).state == StatsEngine.State.DONE);
        });
        test("future events are ignored", () -> check(first(run("2026-11-06T09:00:05",
                resume("09:00:00"), pause("09:00:20"))).state == StatsEngine.State.PENDING));
        test("unsorted events are replayed chronologically", () -> check(first(run(
                "2026-11-06T10:00:00", pause("09:00:10"), resume("09:00:00")))
                .state == StatsEngine.State.DONE));
        test("empty target never qualifies", () -> check(new StatsEngine().evaluate(
                List.of(resume("09:00:00")), "", WINDOWS, at("2026-11-06T10:00:00"),
                ZONE, true).doneCount() == 0));
        test("no configured windows is valid", () -> check(new StatsEngine().evaluate(
                Collections.emptyList(), TARGET, Collections.emptyList(),
                at("2026-11-06T10:00:00"), ZONE, true).windows.isEmpty()));
        test("overnight window is anchored to correct business day", () -> {
            TimeWindow window = new TimeWindow(23 * 60, 60);
            long[] bounds = BusinessDay.bounds(at("2026-11-07T00:30:00"), ZONE, window);
            check(bounds[0] == at("2026-11-06T23:00:00")
                    && bounds[1] == at("2026-11-07T01:00:00"));
        });
        test("early-morning window uses next calendar date", () -> {
            long[] bounds = BusinessDay.bounds(at("2026-11-06T10:00:00"), ZONE,
                    new TimeWindow(60, 120));
            check(bounds[0] == at("2026-11-07T01:00:00"));
        });
        test("DST business day uses local boundaries, not fixed 24 hours", () -> {
            ZoneId ny = ZoneId.of("America/New_York");
            long now = LocalDateTime.parse("2026-11-01T02:30:00").atZone(ny).toInstant().toEpochMilli();
            check(BusinessDay.end(now, ny) - BusinessDay.start(now, ny) == 25 * 60 * 60 * 1000L);
        });
        test("equal endpoints rejected", () -> invalid(540, 540));
        test("invalid clock rejected", () -> invalid(-1, 120));
        test("cross-06:00 windows rejected instead of silently truncated", () -> invalid(300, 420));
        test("overnight cross-06:00 rejected", () -> invalid(1380, 420));
        test("window can end exactly at reset", () -> check(new TimeWindow(300, 360).endMinute == 360));
        test("compact notification with no unlocks", () -> check(NotificationText.title(
                summary("2026-11-06T10:00:00"), ZONE).equals("解锁 0 次 · 最近 暂无")));
        test("compact notification includes most recent unlock time", () -> {
            StatsEngine.Summary s = run("2026-11-06T10:00:00",
                    system("09:10:00", UsageEvent.UNLOCK), system("09:10:01", UsageEvent.LOCK),
                    system("09:12:34", UsageEvent.UNLOCK));
            check(NotificationText.title(s, ZONE).equals("解锁 2 次 · 最近 09:12:34"));
        });
        test("compact notification preserves each window state", () -> {
            StatsEngine.Summary s = run("2026-11-06T16:00:00",
                    resume("09:01:00"), pause("09:01:10"));
            check(NotificationText.windows(s, true).equals("09–11✓ | 15–17… | 20–22○"));
        });
        test("compact missed state is not done", () -> check(NotificationText.windows(
                summary("2026-11-06T12:00:00"), true).startsWith("09–11×")));
        test("compact notification with no target does not claim completion", () -> check(
                NotificationText.windows(summary("2026-11-06T10:00:00"), false)
                        .equals("未选择目标 App")));
        test("compact notification with no windows", () -> {
            StatsEngine.Summary s = new StatsEngine().evaluate(Collections.emptyList(), TARGET,
                    Collections.emptyList(), at("2026-11-06T10:00:00"), ZONE, true);
            check(NotificationText.windows(s, true).equals("未配置时段"));
        });
        test("compact notification preserves non-whole-hour endpoints", () -> {
            StatsEngine.Summary s = new StatsEngine().evaluate(Collections.emptyList(), TARGET,
                    Arrays.asList(new TimeWindow(9 * 60 + 5, 11 * 60 + 30)),
                    at("2026-11-06T10:00:00"), ZONE, true);
            check(NotificationText.windows(s, true).equals("09:05–11:30…"));
        });
        System.out.println("\nPASS: " + passed + " bounded core logic tests; no device/emulator used.");
    }

    private static StatsEngine.Summary summary(String now) { return run(now); }
    private static StatsEngine.WindowResult first(StatsEngine.Summary summary) { return summary.windows.get(0); }
    private static StatsEngine.Summary run(String now, UsageEvent... events) {
        return new StatsEngine().evaluate(new ArrayList<>(Arrays.asList(events)),
                TARGET, WINDOWS, at(now), ZONE, true);
    }
    private static long at(String time) {
        return LocalDateTime.parse(time).atZone(ZONE).toInstant().toEpochMilli();
    }
    private static UsageEvent event(String clock, int type, String pkg, String activity) {
        return new UsageEvent(at("2026-11-06T" + clock), type, pkg, activity);
    }
    private static UsageEvent resume(String clock) { return event(clock, UsageEvent.RESUME, TARGET, "A"); }
    private static UsageEvent pause(String clock) { return event(clock, UsageEvent.PAUSE, TARGET, "A"); }
    private static UsageEvent other(String clock) { return event(clock, UsageEvent.RESUME, "other", "Other"); }
    private static UsageEvent system(String clock, int type) { return event(clock, type, "", ""); }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("condition false");
    }
    private static void invalid(int start, int end) {
        try {
            new TimeWindow(start, end);
            throw new AssertionError("Expected invalid window");
        } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().isEmpty());
        }
    }
    private static void test(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (AssertionError e) {
            throw new AssertionError("FAIL " + name, e);
        }
    }
}
