package dev.checkwakeup.core;

/** Platform-neutral event model. Values intentionally match Android UsageEvents. */
public final class UsageEvent {
    public static final int RESUME = 1;
    public static final int PAUSE = 2;
    public static final int SCREEN_ON = 15;
    public static final int SCREEN_OFF = 16;
    public static final int LOCK = 17;
    public static final int UNLOCK = 18;
    public static final int SHUTDOWN = 26;
    public static final int STARTUP = 27;
    public static final int STOP = 23;

    public final long time;
    public final int type;
    public final String packageName;
    public final String activity;

    public UsageEvent(long time, int type, String packageName, String activity) {
        this.time = time;
        this.type = type;
        this.packageName = packageName == null ? "" : packageName;
        this.activity = activity == null ? "" : activity;
    }
}
