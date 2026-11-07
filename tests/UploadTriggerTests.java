package dev.checkwakeup.core;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Clock-injected upload decisions, independent of network or Android runtime. */
public final class UploadTriggerTests {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DAY = 1_793_916_000_000L;
    private static int passed;
    private UploadTriggerTests() {}

    public static void main(String[] args) {
        StatsEngine.Summary base = summary(Collections.emptyList(), StatsEngine.State.PENDING, -1, 0);
        String key = signal(base, true, 100);
        test("first refresh queues a snapshot immediately",
                UploadTrigger.due("", key, 1_000, 0));
        test("unchanged refresh does not queue", !UploadTrigger.due(key, key, 3_000, 1_000));
        test("heartbeat does not fire a millisecond early",
                !UploadTrigger.due(key, key, 900_999, 1_000));
        test("heartbeat fires at exactly fifteen minutes",
                UploadTrigger.due(key, key, 901_000, 1_000));
        test("late execution sends one overdue heartbeat",
                UploadTrigger.due(key, key, 10_000_000, 1_000));
        test("a change queues immediately without waiting for heartbeat",
                UploadTrigger.due(key, "new", 1_001, 1_000));
        test("a meaningful change resets the heartbeat deadline",
                !UploadTrigger.due("new", "new", 901_000, 900_000));
        test("persisted key and timestamp suppress duplicate on process restart",
                !UploadTrigger.due(new String(key), signal(base, true, 300), 5_000, 1_000));
        test("clock rollback does not stall reporting indefinitely",
                UploadTrigger.due(key, key, 500, 1_000));

        StatsEngine.Summary oneUnlock = summary(Arrays.asList(DAY + 1_000),
                StatsEngine.State.PENDING, -1, 0);
        test("new unlock immediately changes the signal", !key.equals(signal(oneUnlock, true, 200)));
        StatsEngine.Summary changedTime = summary(Arrays.asList(DAY + 1_001),
                StatsEngine.State.PENDING, -1, 0);
        test("timestamp correction with unchanged count still triggers",
                !signal(oneUnlock, true, 200).equals(signal(changedTime, true, 200)));
        StatsEngine.Summary done = summary(Collections.emptyList(), StatsEngine.State.DONE, DAY + 10_000, 10_000);
        test("ten-second qualification triggers immediately", !key.equals(signal(done, true, 200)));
        test("first qualification timestamp correction triggers",
                !signal(done, true, 200).equals(signal(summary(Collections.emptyList(),
                        StatsEngine.State.DONE, DAY + 11_000, 11_000), true, 200)));
        test("ongoing best-duration ticks do not produce network requests",
                key.equals(signal(summary(Collections.emptyList(), StatsEngine.State.PENDING, -1, 9_999),
                        true, 200)));
        test("duration after DONE does not cause per-second reuploads",
                signal(done, true, 200).equals(signal(summary(Collections.emptyList(),
                        StatsEngine.State.DONE, DAY + 10_000, 60_000), true, 200)));
        test("UPCOMING to PENDING triggers", !key.equals(signal(
                summary(Collections.emptyList(), StatsEngine.State.UPCOMING, -1, 0), true, 200)));
        test("PENDING to MISSED triggers", !key.equals(signal(
                summary(Collections.emptyList(), StatsEngine.State.MISSED, -1, 9_000), true, 200)));
        test("business-day rollover triggers", !key.equals(signal(new StatsEngine.Summary(
                DAY + 86_400_000, DAY + 172_800_000, Collections.emptyList(), base.windows), true, 200)));
        test("timezone change triggers", !key.equals(UploadTrigger.signal(base, ZoneId.of("UTC"),
                "example.app", "测试", true, 200, true, "红米", "16", "HyperOS")));
        test("target app change triggers", !key.equals(UploadTrigger.signal(base, ZONE,
                "other.app", "测试", true, 200, true, "红米", "16", "HyperOS")));
        test("target label change triggers", !key.equals(UploadTrigger.signal(base, ZONE,
                "example.app", "新名称", true, 200, true, "红米", "16", "HyperOS")));
        StatsEngine.Summary changedWindow = new StatsEngine.Summary(DAY, DAY + 86_400_000,
                Collections.emptyList(), Collections.singletonList(new StatsEngine.WindowResult(
                new TimeWindow(541, 660), StatsEngine.State.PENDING, -1, 0)));
        test("time-window configuration change triggers", !key.equals(signal(changedWindow, true, 200)));
        test("monitor pause/resume triggers", !key.equals(UploadTrigger.signal(base, ZONE,
                "example.app", "测试", true, 200, false, "红米", "16", "HyperOS")));
        test("device display-name change triggers", !key.equals(UploadTrigger.signal(base, ZONE,
                "example.app", "测试", true, 200, true, "另一台", "16", "HyperOS")));
        test("OS metadata change triggers", !key.equals(UploadTrigger.signal(base, ZONE,
                "example.app", "测试", true, 200, true, "红米", "16", "HyperOS-new")));
        test("successful sync timestamp advancing alone does not trigger",
                key.equals(signal(base, true, 9_999)));
        test("permission/query failure triggers stale quality", !key.equals(signal(base, false, 200)));
        test("stale versus never-available quality is distinguished",
                !signal(base, false, 200).equals(signal(base, false, 0)));
        test("permission/query recovery triggers", !signal(base, false, 200).equals(key));

        int uploads = 0;
        String previous = "";
        long queuedAt = 0;
        for (long now = 1_000; now < 3_601_000; now += 2_000) {
            if (UploadTrigger.due(previous, key, now, queuedAt)) {
                uploads++;
                previous = key;
                queuedAt = now;
            }
        }
        test("1800 unchanged two-second refreshes produce only initial plus 3 heartbeats", uploads == 4);

        String heartbeatPayload = ReportJson.day(summary(Collections.emptyList(),
                StatsEngine.State.PENDING, -1, 9_000), DAY, DAY + 4 * 3_600_000, ZONE,
                100, "example.app", "测试", true, DAY + 4 * 3_600_000);
        test("heartbeat still contains exact diagnostic duration, not rounded/truncated",
                heartbeatPayload.contains("\"best_continuous_ms\":9000"));
        test("heartbeat carries fresh successful sync timestamp", heartbeatPayload.contains(
                "\"last_successful_sync_at\":" + ReportJson.quote(ReportJson.timestamp(DAY + 4 * 3_600_000))));
        long start = java.time.LocalDateTime.parse("2026-11-07T09:00:00")
                .atZone(ZONE).toInstant().toEpochMilli();
        List<UsageEvent> foreground = Collections.singletonList(
                new UsageEvent(start, UsageEvent.RESUME, "example.app", "A"));
        String previousLive = "";
        long lastLive = 0, completionUploadAt = -1;
        int liveUploads = 0;
        StatsEngine.Summary live = null;
        for (long offset = 0; offset <= 14_000; offset += 2_000) {
            long now = start + offset;
            live = new StatsEngine(1_500).evaluate(foreground, "example.app",
                    Collections.singletonList(new TimeWindow(540, 660)), now, ZONE, true);
            String current = signal(live, true, now);
            if (UploadTrigger.due(previousLive, current, now, lastLive)) {
                liveUploads++;
                previousLive = current;
                lastLive = now;
                if (live.doneCount() == 1) completionUploadAt = now;
            }
        }
        test("real engine reports DONE on its original two-second poll, not fifteen minutes later",
                liveUploads == 2 && completionUploadAt == start + 12_000);
        test("upload filtering preserves exact original first ten-second qualification time",
                live != null && live.windows.get(0).qualifiedAt == start + 10_000);
        System.out.println("PASS: " + passed + " change/heartbeat tests; local statistics unchanged.");
    }

    private static String signal(StatsEngine.Summary summary, boolean available, long sync) {
        return UploadTrigger.signal(summary, ZONE, "example.app", "测试", available, sync,
                true, "红米", "16", "HyperOS");
    }
    private static StatsEngine.Summary summary(List<Long> unlocks, StatsEngine.State state,
                                               long qualified, long best) {
        return new StatsEngine.Summary(DAY, DAY + 86_400_000, unlocks, Collections.singletonList(
                new StatsEngine.WindowResult(new TimeWindow(540, 660), state, qualified, best)));
    }
    private static void test(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }
}
