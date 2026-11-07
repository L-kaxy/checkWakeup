package dev.checkwakeup;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.InvocationTargetException;

/**
 * Optional public-API compatibility probe, not a Xiaomi private API adapter.
 * The documented extras key works without downloading a newer compile SDK.
 * Absence of a method is reported, never interpreted as successful promotion.
 */
final class LiveUpdates {
    static final String REQUEST = "android.requestPromotedOngoing";
    static final String PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS";

    private LiveUpdates() {}

    static void request(Notification.Builder builder) {
        Bundle extras = new Bundle();
        extras.putBoolean(REQUEST, true);
        builder.addExtras(extras).setColorized(false);
    }

    static String diagnostics(Context context) {
        AppSettings settings = new AppSettings(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String full;
        try {
            full = String.valueOf(Build.VERSION.class.getField("SDK_INT_FULL").get(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            full = "不可读取";
        }
        StringBuilder text = new StringBuilder("Android ").append(Build.VERSION.RELEASE)
                .append(" · API ").append(Build.VERSION.SDK_INT).append(" · 完整 API ").append(full)
                .append("\n系统构建：").append(Build.DISPLAY)
                .append("\n通知模式：").append(settings.liveUpdates() ? "Live Updates 实验" : "原版普通通知")
                .append("\n请求接口：").append(hasRequestMethod()
                        ? "公开 Builder 方法存在" : "公开 Builder 方法缺失（实验仅发送标准 extras）")
                .append("\n系统允许提升：").append(booleanCall(
                        NotificationManager.class, manager, "canPostPromotedNotifications"));
        try {
            Notification active = null;
            for (StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() == StatusNotification.ID && item.getTag() == null) {
                    active = item.getNotification();
                    break;
                }
            }
            if (active == null) {
                text.append("\n实际通知：未找到活动通知，请先开始监控并允许通知");
            } else {
                boolean requested = active.extras != null && active.extras.getBoolean(REQUEST, false);
                text.append("\n活动通知请求提升：").append(requested ? "是" : "否")
                        .append("\n通知格式符合条件：").append(booleanCall(
                                Notification.class, active, "hasPromotableCharacteristics"));
                try {
                    int flag = Notification.class.getField("FLAG_PROMOTED_ONGOING").getInt(null);
                    text.append("\n系统实际提升标志：").append((active.flags & flag) != 0
                            ? "已提升（锁屏外观仍请实测）" : "未提升");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    text.append("\n系统实际提升标志：无法读取，不能确认已提升");
                }
            }
        } catch (RuntimeException e) {
            text.append("\n实际通知：读取失败（").append(e.getClass().getSimpleName()).append('）');
        }
        return text.append("\n仅靠 Android 16 / HyperOS 3 版本号不能确认大卡片支持。").toString();
    }

    private static boolean hasRequestMethod() {
        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static String booleanCall(Class<?> owner, Object target, String name) {
        try {
            Object result = owner.getMethod(name).invoke(target);
            return Boolean.TRUE.equals(result) ? "是" : "否";
        } catch (NoSuchMethodException e) {
            return "公开接口缺失，无法判断";
        } catch (InvocationTargetException e) {
            return "调用失败（" + e.getTargetException().getClass().getSimpleName() + "）";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "读取失败（" + e.getClass().getSimpleName() + "）";
        }
    }
}
