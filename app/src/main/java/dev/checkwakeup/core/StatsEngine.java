package dev.checkwakeup.core;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic replay, with no Android dependencies and no wall-clock reads. */
public final class StatsEngine {
    public static final long REQUIRED_MS = 10_000;
    // Android pauses A before resuming B within the same app. Only a same-package
    // transition within this grace period may bridge that lifecycle gap.
    public static final long PAGE_TRANSITION_MS = 500;
    private final long liveDelayMs;

    public StatsEngine() { this(0); }

    public StatsEngine(long liveDelayMs) {
        if (liveDelayMs < 0) throw new IllegalArgumentException("Negative live delay");
        this.liveDelayMs = liveDelayMs;
    }

    public static final class Session {
        public final long start;
        public final long end;

        Session(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    public enum State { UPCOMING, PENDING, DONE, MISSED }

    public static final class WindowResult {
        public final TimeWindow window;
        public final State state;
        public final long qualifiedAt;
        public final long bestContinuousMs;

        WindowResult(TimeWindow window, State state, long at, long best) {
            this.window = window;
            this.state = state;
            this.qualifiedAt = at;
            this.bestContinuousMs = best;
        }
    }

    public static final class Summary {
        public final long dayStart;
        public final long dayEnd;
        public final List<Long> unlocks;
        public final List<WindowResult> windows;

        Summary(long start, long end, List<Long> unlocks, List<WindowResult> windows) {
            this.dayStart = start;
            this.dayEnd = end;
            this.unlocks = unlocks;
            this.windows = windows;
        }

        public int doneCount() {
            int count = 0;
            for (WindowResult result : windows) if (result.state == State.DONE) count++;
            return count;
        }
    }

    public Summary evaluate(List<UsageEvent> input, String target, List<TimeWindow> windows,
                            long now, ZoneId zone, boolean canExtendLiveSession) {
        return evaluateDay(input, target, windows, now, now, zone, canExtendLiveSession);
    }

    /** Reconcile a just-closed day without applying today's target/config to its history. */
    public Summary evaluateDay(List<UsageEvent> input, String target, List<TimeWindow> windows,
                               long dayReference, long now, ZoneId zone,
                               boolean canExtendLiveSession) {
        List<UsageEvent> events = new ArrayList<>(input);
        // TimSort is stable: preserve platform order for events with equal timestamps.
        events.sort(Comparator.comparingLong(event -> event.time));
        List<Session> sessions = sessions(events, target, now, canExtendLiveSession);
        long dayStart = BusinessDay.start(dayReference, zone);
        long dayEnd = BusinessDay.end(dayReference, zone);
        List<Long> unlocks = new ArrayList<>();
        boolean locked = true;
        long previousUnlock = Long.MIN_VALUE;
        for (UsageEvent event : events) {
            if (event.time > now) break;
            if (event.type == UsageEvent.LOCK || event.type == UsageEvent.SCREEN_OFF
                    || event.type == UsageEvent.SHUTDOWN
                    || event.type == UsageEvent.STARTUP) locked = true;
            if (event.type == UsageEvent.UNLOCK) {
                if (locked && event.time >= dayStart && event.time < dayEnd
                        && event.time != previousUnlock) {
                    unlocks.add(event.time);
                    previousUnlock = event.time;
                }
                locked = false;
            }
        }
        List<WindowResult> results = new ArrayList<>();
        for (TimeWindow window : windows) {
            long[] bounds = BusinessDay.bounds(dayReference, zone, window);
            long qualified = -1;
            long best = 0;
            for (Session session : sessions) {
                long start = Math.max(session.start, bounds[0]);
                long end = Math.min(Math.min(session.end, bounds[1]), now);
                long duration = Math.max(0, end - start);
                best = Math.max(best, duration);
                if (duration >= REQUIRED_MS) {
                    long at = start + REQUIRED_MS;
                    if (qualified < 0 || at < qualified) qualified = at;
                }
            }
            State state = qualified >= 0 ? State.DONE
                    : now < bounds[0] ? State.UPCOMING
                    : now >= bounds[1] ? State.MISSED : State.PENDING;
            results.add(new WindowResult(window, state, qualified, best));
        }
        return new Summary(dayStart, dayEnd, unlocks, results);
    }

    private List<Session> sessions(List<UsageEvent> events, String target, long now,
                                   boolean live) {
        List<Session> result = new ArrayList<>();
        if (target == null || target.isEmpty()) return result;
        long start = -1;
        long pause = -1;
        String activeActivity = "";
        boolean screen = true;
        boolean locked = false;
        boolean foregroundTarget = false;
        for (UsageEvent event : events) {
            if (event.time > now) break;
            if (pause >= 0 && event.time - pause > PAGE_TRANSITION_MS) {
                add(result, start, pause);
                start = -1;
                pause = -1;
                foregroundTarget = false;
            }
            if (event.type == UsageEvent.SCREEN_OFF || event.type == UsageEvent.LOCK
                    || event.type == UsageEvent.SHUTDOWN || event.type == UsageEvent.STARTUP) {
                add(result, start, pause >= 0 ? pause : event.time);
                start = -1;
                pause = -1;
                if (event.type == UsageEvent.SCREEN_OFF) screen = false;
                if (event.type != UsageEvent.SCREEN_OFF) locked = true;
                if (event.type == UsageEvent.SHUTDOWN || event.type == UsageEvent.STARTUP) {
                    foregroundTarget = false;
                    activeActivity = "";
                }
            } else if (event.type == UsageEvent.SCREEN_ON) {
                screen = true;
                if (foregroundTarget && !locked && start < 0) start = event.time;
            } else if (event.type == UsageEvent.UNLOCK) {
                locked = false;
                if (foregroundTarget && screen && start < 0) start = event.time;
            } else if (event.type == UsageEvent.RESUME) {
                if (target.equals(event.packageName)) {
                    foregroundTarget = true;
                    if (screen && !locked && start < 0) start = event.time;
                    pause = -1;
                    activeActivity = event.activity;
                } else {
                    add(result, start, pause >= 0 ? pause : event.time);
                    start = -1;
                    pause = -1;
                    activeActivity = "";
                    foregroundTarget = false;
                }
            // STOP can belong to an old instance of the same Activity class
            // after its replacement has resumed. Public UsageEvents does not
            // expose instance IDs, so use PAUSE/other RESUME/lock as boundaries.
            } else if (event.type == UsageEvent.PAUSE
                    && target.equals(event.packageName)
                    && (activeActivity.isEmpty() || activeActivity.equals(event.activity))) {
                foregroundTarget = false;
                if (start >= 0 && pause < 0) pause = event.time;
            }
        }
        // A pending pause is never optimistically counted, even inside the grace.
        if (start >= 0 && pause >= 0) add(result, start, pause);
        else if (start >= 0 && live) add(result, start, now - liveDelayMs);
        // When a live gate says locked/off but its usage event has not arrived, do
        // not guess an end timestamp or claim a completed 10-second session.
        return result;
    }

    private static void add(List<Session> result, long start, long end) {
        if (start >= 0 && end > start) result.add(new Session(start, end));
    }
}
