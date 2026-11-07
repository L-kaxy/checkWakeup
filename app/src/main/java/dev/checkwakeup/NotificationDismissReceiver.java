package dev.checkwakeup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Respect dismissal: subsequent data updates must not resurrect Live Updates. */
public final class NotificationDismissReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.checkwakeup.DISMISS_LIVE_UPDATES";
    static final String GENERATION = "generation";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        AppSettings settings = new AppSettings(context);
        if (settings.liveUpdates() && intent.getLongExtra(GENERATION, -1)
                == settings.notificationGeneration()) {
            settings.setLiveUpdates(false);
            // Monitoring keeps running. Its next changed data update may post the
            // normal service disclosure, but never re-request promotion.
        }
    }
}
