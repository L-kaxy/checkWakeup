package dev.checkwakeup;

import android.content.Context;
import android.os.Build;
import dev.checkwakeup.core.BusinessDay;
import dev.checkwakeup.core.ReportCrypto;
import dev.checkwakeup.core.ReportJson;
import dev.checkwakeup.core.ReportAck;
import dev.checkwakeup.core.ReportTransport;
import dev.checkwakeup.core.StatsEngine;
import dev.checkwakeup.core.TimeWindow;
import dev.checkwakeup.core.UploadPolicy;
import dev.checkwakeup.core.UploadTrigger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Optional upload pipeline. Local statistics never wait for network I/O. */
final class UploadManager {
    private static UploadManager instance;
    private final AppSettings settings;
    private final UploadStore store;
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "encrypted-report");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> scheduled;
    private HttpURLConnection active;
    private boolean busy;
    private boolean configurationBound;
    private long generation;
    private String localError = "";
    private PublicKey publicKey;
    private String lastSignal;
    private long lastQueuedAt;
    private long nextHistoricalCheck;
    private boolean resumePending = true;
    private boolean forceSnapshot;

    static synchronized UploadManager get(Context context) {
        if (instance == null) instance = new UploadManager(context.getApplicationContext());
        return instance;
    }

    private UploadManager(Context context) {
        settings = new AppSettings(context);
        store = new UploadStore(context);
        bindConfiguration();
        configurationBound = true;
        loadTrigger();
    }

    private void loadTrigger() {
        lastSignal = store.text("last_signal", "");
        lastQueuedAt = store.number("last_queued_at");
        nextHistoricalCheck = 0;
        resumePending = true;
    }

    private void bindConfiguration() {
        // Crash-safe destination binding: preferences may have committed just
        // before a crash, while the previous server's outbox still exists.
        android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            if (!settings.uploadEnabled()
                    || !settings.uploadUrl().equals(store.text("bound_url", ""))
                    || !store.text("bound_enabled", "false").equals("true")) {
                store.clearPending();
            } else if (!settings.uploadName().equals(store.text("bound_name", ""))) {
                db.delete("outbox", null, null);
                store.resetFailure();
            }
            store.putMeta("bound_url", settings.uploadUrl());
            store.putMeta("bound_name", settings.uploadName());
            store.putMeta("bound_enabled", settings.uploadEnabled());
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void configure(boolean enabled, String url, String name) {
        url = UploadPolicy.endpoint(url);
        name = UploadPolicy.deviceName(name);
        if (enabled) {
            try { publicKey = ReportCrypto.publicKey(TrustedServer.PUBLIC_KEY); }
            catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalArgumentException("构建配置中的后台公钥无效，不能启用上传", e);
            }
        }
        boolean changed = enabled != settings.uploadEnabled()
                || !url.equals(settings.uploadUrl()) || !name.equals(settings.uploadName());
        if (!changed && configurationBound) return;
        configurationBound = false;
        generation++;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
        if (active != null) active.disconnect();
        if (!settings.saveUpload(enabled, url, name)) {
            localError = "上报设置保存失败，上传已停止";
            throw new IllegalStateException("无法保存上报设置");
        }
        // Consent/config changes never send an old outbox to a new destination.
        bindConfiguration();
        configurationBound = true;
        loadTrigger();
        localError = "";
    }

    /** Called only by the serialized repository refresh, not the UI render loop. */
    synchronized void record(UsageRepository source, StatsEngine.Summary summary, long now,
                             ZoneId zone, AppSettings.TrackingConfig config,
                             boolean available, long syncedAt) {
        if (!settings.uploadEnabled() || !configurationBound) return;
        try {
            if (resumePending) {
                resumePending = false;
                kick(); // Resume an unchanged persisted outbox after process restart.
            }
            String signal = UploadTrigger.signal(summary, zone, config.target, config.label,
                    available, syncedAt, settings.enabled(), settings.uploadName(),
                    Build.VERSION.RELEASE, Build.DISPLAY);
            boolean due = forceSnapshot || UploadTrigger.due(lastSignal, signal, now, lastQueuedAt);
            if (!due && now < nextHistoricalCheck) return;
            boolean queued = due;
            long historicalCheck = summary.dayEnd;
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                // Finish a just-closed day using its OWN target/windows/timezone.
                // More distant days are left as last actually observed, not fabricated.
                if (available) {
                    for (UploadStore.Day old : store.unfinished(now)) {
                        ZoneId oldZone = ZoneId.of(old.zone);
                        long oldestSyncedDay = BusinessDay.start(
                                BusinessDay.start(now, oldZone) - 1, oldZone);
                        if (old.end <= oldestSyncedDay) continue;
                        List<TimeWindow> windows = decodeWindows(old.windows);
                        StatsEngine.Summary finalSummary = source.historical(
                                old.reference, now, oldZone, old.target, windows);
                        String oldSignal = UploadTrigger.daySignal(finalSummary, oldZone,
                                old.target, old.label, true, syncedAt);
                        boolean finalize = now - old.end >= 120_000;
                        if (!finalize) historicalCheck = Math.min(historicalCheck, now + 2_000);
                        if (!finalize && oldSignal.equals(old.signal)) continue;
                        long revision = store.nextSequence();
                        old.payload = ReportJson.day(finalSummary, old.reference, now, oldZone,
                                revision, old.target, old.label, true, syncedAt);
                        // Give delayed platform events two minutes to settle.
                        old.finalized = finalize;
                        old.signal = oldSignal;
                        store.put(old, revision);
                        queued = true;
                    }
                }
                if (queued) {
                    long revision = store.nextSequence();
                    UploadStore.Day current = new UploadStore.Day();
                    current.key = BusinessDay.date(now, zone) + "|" + zone.getId();
                    current.reference = summary.dayStart;
                    current.end = summary.dayEnd;
                    current.zone = zone.getId();
                    current.target = config.target;
                    current.label = config.label;
                    current.signal = UploadTrigger.daySignal(summary, zone, config.target,
                            config.label, available, syncedAt);
                    JSONArray windows = new JSONArray();
                    for (StatsEngine.WindowResult result : summary.windows) {
                        windows.put(new JSONArray().put(result.window.startMinute)
                                .put(result.window.endMinute));
                    }
                    current.windows = windows.toString();
                    current.payload = ReportJson.day(summary, now, now, zone, revision,
                            current.target, current.label, available, syncedAt);
                    store.put(current, revision);
                    store.boundQueue();
                    store.putMeta("last_signal", signal);
                    store.putMeta("last_queued_at", now);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            nextHistoricalCheck = historicalCheck;
            if (queued) {
                lastSignal = signal;
                lastQueuedAt = now;
                forceSnapshot = false;
            }
            localError = "";
            if (queued) kick();
        } catch (RuntimeException | JSONException e) {
            nextHistoricalCheck = 0;
            resumePending = true;
            localError = "待传数据保存失败（" + e.getClass().getSimpleName() + "）";
        }
    }

    private List<TimeWindow> decodeWindows(String json) throws JSONException {
        List<TimeWindow> windows = new ArrayList<>();
        JSONArray pairs = new JSONArray(json);
        for (int i = 0; i < pairs.length(); i++) {
            JSONArray pair = pairs.getJSONArray(i);
            windows.add(new TimeWindow(pair.getInt(0), pair.getInt(1)));
        }
        return windows;
    }

    private synchronized void kick() {
        if (!settings.uploadEnabled() || !configurationBound || busy
                || scheduled != null || store.number("blocked") != 0) return;
        if (store.pending() == 0 && store.outbox() == null) return;
        long delay = Math.max(0, store.number("retry_at") - System.currentTimeMillis());
        scheduled = network.schedule(this::send, delay, TimeUnit.MILLISECONDS);
    }

    synchronized void retry() {
        if (!settings.uploadEnabled()) return;
        if (store.number("blocked") != 0) {
            store.getWritableDatabase().delete("outbox", null, null);
            store.resetFailure(); // Use latest coalesced snapshots, never mutate an existing report.
        }
        localError = "";
        forceSnapshot = true;
        kick(); // A server Retry-After remains in force.
    }

    synchronized String status() {
        if (!settings.uploadEnabled()) return "未启用 · 仅在本机保存统计";
        if (!configurationBound) return "上报配置未能安全保存，上传已停止；请重新保存设置";
        try {
            String message = "待传 " + store.pending() + " 天 · "
                    + (busy ? "正在加密上传" : store.number("blocked") != 0 ? "需检查后重试"
                    : store.number("retry_at") > System.currentTimeMillis()
                    ? "下次重试 " + DisplayText.time(store.number("retry_at"))
                    : "变化时上报 · 无变化每 15 分钟心跳");
            long success = store.number("last_success");
            message += "\n最近成功：" + (success == 0 ? "尚未成功" : DisplayText.dateTime(success));
            String error = localError.isEmpty() ? store.text("error", "") : localError;
            if (!error.isEmpty()) message += "\n" + error;
            if (store.number("expired_days") > 0) message += "\n超过队列容量而过期的快照："
                    + store.number("expired_days") + " 天";
            return message;
        } catch (RuntimeException e) { return "无法读取上报队列，请检查存储空间"; }
    }

    synchronized String details() {
        try {
            return "设备码（重装后变化）：\n" + store.text("device_code", "")
                    + "\n\nkid：" + TrustedServer.KID + "\nSHA-256 公钥指纹：\n"
                    + ReportCrypto.fingerprint(ReportCrypto.publicKey(TrustedServer.PUBLIC_KEY))
                    + "\n\n只在启用后发送；每台手机使用同一地址，以设备码区分，名称仅用于展示。"
                    + "\n仅保留最近 31 个统计日/时区快照；离线时合并同一天的新数据。"
                    + "\n解锁、达标、时段状态、配置及数据质量变化时上报；无变化每 15 分钟心跳。"
                    + "\n本地统计频率不变；系统休眠可能延后心跳，不为心跳主动唤醒手机。"
                    + "\nHTTP 下请求正文为 JWE 密文，但地址、流量及响应不受加密认证保护。"
                    + "\n更换后台密钥需更新构建配置并发布新 APK；不会通过 HTTP 自动信任新公钥。";
        } catch (java.security.GeneralSecurityException e) { return "公钥配置错误"; }
    }

    private void send() {
        long token;
        String body, endpoint;
        synchronized (this) {
            scheduled = null;
            if (!settings.uploadEnabled() || !configurationBound || busy || store.number("blocked") != 0) return;
            busy = true;
            token = generation;
            body = null;
            endpoint = settings.uploadUrl();
        }
        try {
            synchronized (this) {
                if (token != generation || !settings.uploadEnabled()) return;
                body = store.outbox();
                if (body == null) {
                    List<String> days = store.pendingDays();
                    if (days.isEmpty()) return;
                    body = ReportJson.report(UUID.randomUUID().toString(), store.nextSequence(),
                            store.text("device_code", ""), settings.uploadName(),
                            System.currentTimeMillis(), settings.enabled(),
                            Build.VERSION.RELEASE, Build.DISPLAY, days);
                    store.outbox(body); // Persist BEFORE network: process death => same report on retry.
                }
                if (publicKey == null) publicKey = ReportCrypto.publicKey(TrustedServer.PUBLIC_KEY);
            }
            String encrypted = ReportCrypto.encrypt(body.getBytes(StandardCharsets.UTF_8),
                    publicKey, TrustedServer.KID);
            ReportTransport.Response response = ReportTransport.post(endpoint, encrypted, connection -> {
                synchronized (UploadManager.this) {
                    if (token != generation || !settings.uploadEnabled()) return false;
                    active = connection;
                    return true;
                }
            });
            synchronized (this) {
                if (token != generation || !settings.uploadEnabled()) return;
                if (response.status == 200) {
                    JSONObject report = new JSONObject(body);
                    validateAck(report, new JSONObject(response.body));
                    store.acknowledge(report, System.currentTimeMillis());
                } else {
                    failure("HTTP " + response.status + errorCode(response.body),
                            UploadPolicy.retryable(response.status), response.retryAfter);
                }
            }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            synchronized (this) {
                if (token == generation) failure("加密或数据配置错误", false, null);
            }
        } catch (JSONException e) {
            synchronized (this) {
                if (token == generation) failure("服务器回执无效，未确认成功", true, null);
            }
        } catch (IOException e) {
            synchronized (this) {
                if (token == generation) failure("网络连接失败，已保留待传数据", true, null);
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                if (token == generation) localError = "上报队列异常，请重新打开应用";
            }
        } finally {
            synchronized (this) {
                active = null;
                busy = false;
                // Do not spin on unexpected database/runtime errors.
                if (localError.isEmpty()) kick();
            }
        }
    }

    private void failure(String error, boolean retryable, String retryAfter) {
        int attempt = (int) Math.min(30, store.number("attempt") + 1);
        store.putMeta("attempt", attempt);
        store.putMeta("error", error);
        store.putMeta("blocked", retryable ? 0 : 1);
        store.putMeta("retry_at", retryable ? System.currentTimeMillis() + UploadPolicy.retryDelay(
                attempt, retryAfter, System.currentTimeMillis(), Math.random()) : 0);
    }

    private String errorCode(String response) {
        try {
            String code = new JSONObject(response).getJSONObject("error").getString("code");
            return code.matches("[A-Z_]{1,64}") ? " · " + code : "";
        } catch (JSONException e) { return ""; }
    }

    private void validateAck(JSONObject report, JSONObject ack) throws JSONException {
        try { Instant.parse(ack.getString("received_at")); }
        catch (java.time.DateTimeException e) { throw new JSONException("Invalid ACK time"); }
        JSONArray expected = report.getJSONArray("days");
        JSONArray results = ack.getJSONArray("results");
        long[] sent = new long[expected.length()];
        for (int i = 0; i < sent.length; i++) sent[i] = expected.getJSONObject(i).getLong("revision");
        ReportAck.Result[] parsed = new ReportAck.Result[results.length()];
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);
            parsed[i] = new ReportAck.Result(integer(result, "index"),
                    integer(result, "stored_revision"), result.getString("result"));
        }
        if (!ReportAck.matches(report.getString("report_id"), sent,
                integer(ack, "schema_version"), ack.getString("report_id"), parsed)) {
            throw new JSONException("Mismatched or partial ACK");
        }
    }

    private long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new JSONException("Non-integer ACK");
        }
        return ((Number) value).longValue();
    }
}
