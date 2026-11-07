package dev.checkwakeup.core;

import android.app.usage.UsageEvents;

/** The SDK constants inline at javac time; execution needs only a JVM. */
public final class AndroidApiContract {
    private AndroidApiContract() {}

    public static void main(String[] args) {
        check(UsageEvent.RESUME == UsageEvents.Event.ACTIVITY_RESUMED);
        check(UsageEvent.PAUSE == UsageEvents.Event.ACTIVITY_PAUSED);
        check(UsageEvent.STOP == UsageEvents.Event.ACTIVITY_STOPPED);
        check(UsageEvent.SCREEN_ON == UsageEvents.Event.SCREEN_INTERACTIVE);
        check(UsageEvent.SCREEN_OFF == UsageEvents.Event.SCREEN_NON_INTERACTIVE);
        check(UsageEvent.LOCK == UsageEvents.Event.KEYGUARD_SHOWN);
        check(UsageEvent.UNLOCK == UsageEvents.Event.KEYGUARD_HIDDEN);
        check(UsageEvent.SHUTDOWN == UsageEvents.Event.DEVICE_SHUTDOWN);
        check(UsageEvent.STARTUP == UsageEvents.Event.DEVICE_STARTUP);
        System.out.println("PASS: 9 event constants match Android SDK 35.");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Android event constant mismatch");
    }
}
