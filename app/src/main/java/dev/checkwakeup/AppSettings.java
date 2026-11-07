package dev.checkwakeup;

import android.content.Context;
import android.content.SharedPreferences;

import dev.checkwakeup.core.TimeWindow;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AppSettings {
    static final class TrackingConfig {
        final String target, label;
        final List<TimeWindow> windows;
        TrackingConfig(String target, String label, List<TimeWindow> windows) {
            this.target = target;
            this.label = label;
            this.windows = windows;
        }
    }
    private final SharedPreferences prefs;

    AppSettings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    String targetPackage() { return prefs.getString("target_package", ""); }
    String targetLabel() { return prefs.getString("target_label", "未选择"); }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    void setEnabled(boolean enabled) { prefs.edit().putBoolean("enabled", enabled).apply(); }

    boolean uploadEnabled() { return prefs.getBoolean("upload_enabled", false); }
    String uploadUrl() { return prefs.getString("upload_url", TrustedServer.URL); }
    String uploadName() { return prefs.getString("upload_name", android.os.Build.MODEL); }
    boolean saveUpload(boolean enabled, String url, String name) {
        return prefs.edit().putBoolean("upload_enabled", enabled)
                .putString("upload_url", url).putString("upload_name", name).commit();
    }

    boolean liveUpdates() { return prefs.getBoolean("live_updates", false); }
    long notificationGeneration() { return prefs.getLong("notification_generation", 0); }
    void setLiveUpdates(boolean enabled) {
        prefs.edit().putBoolean("live_updates", enabled)
                .putLong("notification_generation", notificationGeneration() + 1).apply();
    }

    void setTarget(String packageName, String label) {
        prefs.edit().putString("target_package", packageName).putString("target_label", label).apply();
    }

    List<TimeWindow> windows() {
        return decodeWindows(prefs.getString("windows", "[[540,660],[900,1020],[1200,1320]]"));
    }

    TrackingConfig tracking() {
        // A settings change on the UI thread cannot mix a new target with the
        // previous target's evaluated result in an upload.
        Map<String, ?> values = prefs.getAll();
        return new TrackingConfig(values.get("target_package") instanceof String
                ? (String) values.get("target_package") : "",
                values.get("target_label") instanceof String ? (String) values.get("target_label") : "未选择",
                decodeWindows(values.get("windows") instanceof String
                        ? (String) values.get("windows") : "[[540,660],[900,1020],[1200,1320]]"));
    }

    private List<TimeWindow> decodeWindows(String json) {
        List<TimeWindow> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONArray pair = array.getJSONArray(i);
                result.add(new TimeWindow(pair.getInt(0), pair.getInt(1)));
            }
        } catch (JSONException | IllegalArgumentException e) {
            // A corrupt configuration must not silently claim any completed checks.
        }
        return result;
    }

    void setWindows(List<TimeWindow> windows) {
        JSONArray array = new JSONArray();
        for (TimeWindow window : windows) {
            array.put(new JSONArray().put(window.startMinute).put(window.endMinute));
        }
        prefs.edit().putString("windows", array.toString()).apply();
    }
}
