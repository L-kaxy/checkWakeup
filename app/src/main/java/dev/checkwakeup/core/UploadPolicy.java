package dev.checkwakeup.core;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Small, JVM-testable boundaries for user configuration and retry behavior. */
public final class UploadPolicy {
    private UploadPolicy() {}

    public static String endpoint(String input) {
        String value = input.trim();
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || (uri.getPort() < -1)) {
                throw new IllegalArgumentException("请输入不含账号、参数或片段的 HTTP(S) 地址");
            }
            if (uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || "/".equals(uri.getRawPath())) {
                return value.replaceAll("/+$", "") + "/ingest/v1/reports";
            }
            if (!uri.getRawPath().equals("/ingest/v1/reports")) {
                throw new IllegalArgumentException("地址路径应为 /ingest/v1/reports");
            }
            return value;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("上报地址格式不正确", e);
        }
    }

    public static String deviceName(String input) {
        int start = 0, end = input.length();
        while (start < end && space(input.codePointAt(start))) {
            start += Character.charCount(input.codePointAt(start));
        }
        while (end > start && space(input.codePointBefore(end))) {
            end -= Character.charCount(input.codePointBefore(end));
        }
        String value = input.substring(start, end);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 64) {
            throw new IllegalArgumentException("设备名称需要 1–64 个字符");
        }
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if (Character.isISOControl(cp) || (cp >= 0xD800 && cp <= 0xDFFF)) {
                throw new IllegalArgumentException("设备名称不能包含控制字符");
            }
            i += Character.charCount(cp);
        }
        return value;
    }

    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    public static long retryDelay(int attempt, String retryAfter, long now, double jitter) {
        long backoff = Math.min(300_000L, 5_000L << Math.min(6, Math.max(0, attempt - 1)));
        backoff = (long) (backoff * (1 + Math.max(0, Math.min(1, jitter)) * .2));
        long server = 0;
        if (retryAfter != null) {
            try {
                server = Math.multiplyExact(Long.parseLong(retryAfter.trim()), 1_000L);
            } catch (ArithmeticException | NumberFormatException e) {
                try {
                    server = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - now;
                } catch (java.time.DateTimeException ignored) {
                    server = 0;
                }
            }
        }
        // Don't let an untrusted HTTP response overflow a persisted timestamp.
        return Math.max(backoff, Math.min(86_400_000L, Math.max(0, server)));
    }
}
