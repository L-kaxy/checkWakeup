package dev.checkwakeup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class RestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!new AppSettings(context).enabled() || !UsageRepository.hasUsagePermission(context)) return;
        try {
            context.startForegroundService(new Intent(context, MonitorService.class));
        } catch (IllegalStateException | SecurityException e) {
            // OEM or platform policies may reject background startup.
            // Do not circumvent them. The next manual open can resume monitoring.
            android.util.Log.w("CheckWakeup", "Open the app to resume monitoring", e);
        }
    }
}
