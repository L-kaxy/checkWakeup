package dev.checkwakeup;

import dev.checkwakeup.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** Bounded JVM tests; test private keys are fixtures, never production secrets. */
public final class UploadTests {
    private static int passed;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DEVICE = "cdf7f687-acb6-4dd1-bbfc-c37dcc7ed300";
    private static final String TARGET = "example.target";
    private UploadTests() {}
    private interface Task { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        PublicKey pub = ReportCrypto.publicKey(Files.readString(Path.of(args[0])));
        String privatePem = Files.readString(Path.of(args[1])).replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey priv = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privatePem)));
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        byte[] input = "{\"设备\":\"红米\",\"n\":1}".getBytes(StandardCharsets.UTF_8);
        String jwe = ReportCrypto.encrypt(input, pub, TrustedServer.KID);
        test("JWE has five unpadded compact segments", () -> {
            check(jwe.split("\\.", -1).length == 5 && !jwe.contains("="));
        });
        test("JWE header pins algorithms and content type", () -> {
            String header = new String(decode(jwe.split("\\.")[0]), StandardCharsets.UTF_8);
            check(header.equals("{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\","
                    + "\"kid\":\"ingest-2026-01\",\"typ\":\"checkwakeup+jwe\",\"cty\":\"application/json\"}"));
        });
        test("JWE uses RSA3072, 96-bit IV and 128-bit authentication tag", () -> {
            String[] parts = jwe.split("\\.");
            check(decode(parts[1]).length == 384 && decode(parts[2]).length == 12
                    && decode(parts[4]).length == 16);
        });
        test("explicit SHA256/MGF1-SHA256 round trip preserves UTF8", () ->
                check(Arrays.equals(input, decrypt(jwe, priv))));
        test("fresh encryption never reuses the JWE or IV", () -> {
            String next = ReportCrypto.encrypt(input, pub, TrustedServer.KID);
            check(!jwe.equals(next) && !jwe.split("\\.")[2].equals(next.split("\\.")[2]));
        });
        test("ciphertext tampering is rejected", () -> {
            String[] parts = jwe.split("\\.");
            byte[] cipher = decode(parts[3]);
            cipher[0] ^= 1;
            parts[3] = encode(cipher);
            rejects(() -> decrypt(String.join(".", parts), priv));
        });
        test("protected header is authenticated as AAD", () -> {
            String[] parts = jwe.split("\\.");
            String header = new String(decode(parts[0]), StandardCharsets.UTF_8).replace("2026", "2027");
            parts[0] = encode(header.getBytes(StandardCharsets.UTF_8));
            rejects(() -> decrypt(String.join(".", parts), priv));
        });
        test("bad kid cannot inject protected header", () ->
                rejects(() -> ReportCrypto.encrypt(input, pub, "\"evil")));
        test("plaintext cap is enforced", () ->
                rejects(() -> ReportCrypto.encrypt(new byte[2 * 1024 * 1024 + 1], pub, TrustedServer.KID)));
        test("2048-bit keys are rejected", () -> {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----";
            rejects(() -> ReportCrypto.publicKey(pem));
        });
        PublicKey injected = ReportCrypto.publicKey(TrustedServer.PUBLIC_KEY);
        test("generated test configuration injects the expected fixture, not a deployment key", () ->
                check(Arrays.equals(pub.getEncoded(), injected.getEncoded())));
        System.out.println("Test fixture SPKI SHA-256: " + ReportCrypto.fingerprint(injected));

        test("base URL normalizes to the full ingest endpoint", () -> check(
                UploadPolicy.endpoint(" https://example.test/ ")
                        .equals(TrustedServer.URL)));
        test("HTTPS endpoint is preserved", () -> check(UploadPolicy.endpoint(
                "https://example.com/ingest/v1/reports").equals("https://example.com/ingest/v1/reports")));
        String[] invalid = {"ftp://example.com", "http://a:b@example.com", "http://example.com/?secret=x",
                "http://example.com/#x", "http://example.com:0/", "http://example.com:70000/",
                "http://example.com/login", "file:///tmp/x", "http:///no-host"};
        for (String value : invalid) test("reject unsafe/mistyped URL: " + value,
                () -> rejects(() -> UploadPolicy.endpoint(value)));
        test("device name is trimmed, not used as device identity", () ->
                check(UploadPolicy.deviceName(" 红米 ").equals("红米")));
        test("blank name is rejected", () -> rejects(() -> UploadPolicy.deviceName(" ")));
        test("Unicode whitespace-only name is rejected before sending", () ->
                rejects(() -> UploadPolicy.deviceName("\u3000\u00A0")));
        test("control characters in names are rejected", () ->
                rejects(() -> UploadPolicy.deviceName("phone\nname")));
        test("unpaired surrogate in name is rejected", () ->
                rejects(() -> UploadPolicy.deviceName("x\uD800")));
        test("64 Unicode code points accepted, 65 rejected", () -> {
            check(UploadPolicy.deviceName("📱".repeat(64)).codePointCount(0, 128) == 64);
            rejects(() -> UploadPolicy.deviceName("📱".repeat(65)));
        });
        test("retryable HTTP errors are limited", () -> check(UploadPolicy.retryable(429)
                && UploadPolicy.retryable(503) && UploadPolicy.retryable(408)
                && !UploadPolicy.retryable(400) && !UploadPolicy.retryable(422)
                && !UploadPolicy.retryable(409) && !UploadPolicy.retryable(302)));
        test("retry backoff is bounded and has jitter", () -> check(
                UploadPolicy.retryDelay(1, null, 0, 0) == 5_000
                && UploadPolicy.retryDelay(1, null, 0, 1) == 6_000
                && UploadPolicy.retryDelay(30, null, 0, 0) == 300_000));
        test("server Retry-After seconds is a lower bound", () ->
                check(UploadPolicy.retryDelay(1, "120", 0, 0) == 120_000));
        test("Retry-After RFC1123 date is supported", () -> check(UploadPolicy.retryDelay(
                1, "Fri, 06 Nov 2026 02:00:00 GMT", 1793930280000L, 0) == 120_000));
        test("bad/negative/overflow Retry-After is bounded", () -> check(
                UploadPolicy.retryDelay(1, "-10", 0, 0) == 5_000
                && UploadPolicy.retryDelay(1, "bad", 0, 0) == 5_000
                && UploadPolicy.retryDelay(1, "9999999999999999999999", 0, 0) == 5_000
                && UploadPolicy.retryDelay(1, "99999999", 0, 0) == 86_400_000));
        test("JSON UTC timestamps always include three milliseconds", () -> check(
                ReportJson.timestamp(0).equals("1970-01-01T00:00:00.000Z")
                && ReportJson.timestamp(123).endsWith(".123Z")));
        test("JSON strings escape quotes, slashes, controls and retain Unicode", () ->
                check(ReportJson.quote("红米\"\\\n\t\u0001📱")
                        .equals("\"红米\\\"\\\\\\n\\t\\u0001📱\"")));
        test("display labels are bounded without splitting Unicode code points", () ->
                check(ReportJson.displayField("📱".repeat(129), 128, "fallback")
                        .codePointCount(0, 256) == 128));
        test("untrusted installed-app labels cannot break backend text validation", () -> check(
                ReportJson.displayField(" \n测试\rApp\t ", 128, "fallback").equals("测试 App")
                        && ReportJson.displayField("\n\t", 128, "fallback").equals("fallback")));

        long reference = at("2026-11-06T10:00:00");
        long closedAt = at("2026-11-07T06:01:00");
        List<TimeWindow> windows = Arrays.asList(new TimeWindow(540, 660),
                new TimeWindow(900, 1020), new TimeWindow(1200, 1320));
        List<UsageEvent> events = Arrays.asList(
                new UsageEvent(at("2026-11-06T09:01:00"), UsageEvent.UNLOCK, "", ""),
                new UsageEvent(at("2026-11-06T09:02:00"), UsageEvent.RESUME, TARGET, "A"),
                new UsageEvent(at("2026-11-06T09:02:10"), UsageEvent.PAUSE, TARGET, "A"));
        StatsEngine.Summary historic = new StatsEngine().evaluateDay(
                events, TARGET, windows, reference, closedAt, ZONE, false);
        test("06:00 finalization retains yesterday, not today's empty summary", () -> check(
                historic.dayStart == at("2026-11-06T06:00:00")
                && historic.unlocks.size() == 1 && historic.doneCount() == 1
                && historic.windows.get(1).state == StatsEngine.State.MISSED));
        test("historical evaluation uses its saved target, not the new target", () -> check(
                new StatsEngine().evaluateDay(events, "different.app", windows,
                        reference, closedAt, ZONE, false).doneCount() == 0));
        test("closed-day live tail is clipped at 06:00", () -> {
            StatsEngine.Summary tail = new StatsEngine().evaluateDay(Collections.singletonList(
                    new UsageEvent(at("2026-11-07T05:59:50"), UsageEvent.RESUME, TARGET, "A")),
                    TARGET, Collections.singletonList(new TimeWindow(300, 360)),
                    reference, closedAt, ZONE, true);
            check(tail.windows.get(0).bestContinuousMs == 10_000
                    && tail.windows.get(0).qualifiedAt == at("2026-11-07T06:00:00"));
        });
        test("historical DST day is 25 local-clock hours", () -> {
            ZoneId zone = ZoneId.of("America/New_York");
            long ref = LocalDateTime.parse("2026-10-31T10:00:00").atZone(zone).toInstant().toEpochMilli();
            long calc = LocalDateTime.parse("2026-11-01T07:00:00").atZone(zone).toInstant().toEpochMilli();
            StatsEngine.Summary day = new StatsEngine().evaluateDay(Collections.emptyList(), "",
                    windows, ref, calc, zone, false);
            check(day.dayEnd - day.dayStart == 25 * 3_600_000L);
        });
        String historicDay = ReportJson.day(historic, reference, closedAt, ZONE, 10,
                TARGET, "测试 App", true, closedAt - 1);
        test("serialized DONE is first qualification, not most recent", () -> check(
                historicDay.contains("\"qualified_at\":\"2026-11-06T01:02:10.000Z\"")
                        && historicDay.contains("\"business_date\":\"2026-11-06\"")));
        test("missing permission is marked unavailable, not successful zero", () -> check(
                ReportJson.day(historic, reference, closedAt, ZONE, 11,
                        TARGET, "测试 App", false, 0).contains("\"status\":\"unavailable\"")));
        test("failed sync after previous success is marked stale", () -> check(
                ReportJson.day(historic, reference, closedAt, ZONE, 11,
                        TARGET, "测试 App", false, closedAt - 1000).contains("\"status\":\"stale\"")));
        for (String kind : Arrays.asList("created", "updated", "unchanged")) {
            test("ACK accepts exact revision: " + kind, () -> check(ReportAck.matches("id",
                    new long[]{10}, 1, "id", new ReportAck.Result[]{new ReportAck.Result(0, 10, kind)})));
        }
        test("ACK accepts a strictly newer server snapshot as ignored_stale", () -> check(
                ReportAck.matches("id", new long[]{10}, 1, "id",
                        new ReportAck.Result[]{new ReportAck.Result(0, 11, "ignored_stale")})));
        test("ACK rejects a different report ID", () -> check(!ReportAck.matches("id",
                new long[]{10}, 1, "other", new ReportAck.Result[]{new ReportAck.Result(0, 10, "created")})));
        test("ACK rejects partial results", () -> check(!ReportAck.matches("id",
                new long[]{10, 11}, 1, "id", new ReportAck.Result[]{new ReportAck.Result(0, 10, "created")})));
        test("ACK rejects a wrong result index", () -> check(!ReportAck.matches("id",
                new long[]{10}, 1, "id", new ReportAck.Result[]{new ReportAck.Result(1, 10, "created")})));
        test("ACK rejects an older stored revision", () -> check(!ReportAck.matches("id",
                new long[]{10}, 1, "id", new ReportAck.Result[]{new ReportAck.Result(0, 9, "updated")})));
        test("ACK rejects an unknown result kind", () -> check(!ReportAck.matches("id",
                new long[]{10}, 1, "id", new ReportAck.Result[]{new ReportAck.Result(0, 10, "ok")})));
        test("ACK rejects schema mismatch", () -> check(!ReportAck.matches("id",
                new long[]{10}, 2, "id", new ReportAck.Result[]{new ReportAck.Result(0, 10, "created")})));

        // Full snapshots for the real Python backend validator/ingest integration.
        write(output, "historical", report(20, closedAt, Collections.singletonList(historicDay)), pub);
        List<String> days = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            long now = System.currentTimeMillis();
            long dayRef = java.time.Instant.ofEpochMilli(now).atZone(ZONE).minusDays(i)
                    .toInstant().toEpochMilli();
            StatsEngine.Summary s = new StatsEngine().evaluateDay(
                    Collections.emptyList(), "", windows, dayRef, now, ZONE, false);
            days.add(ReportJson.day(s, dayRef, now, ZONE, 100 + i, "", "", true, now));
        }
        write(output, "multi-day", report(200, System.currentTimeMillis(), days), pub);
        // Generated config is exercised only against the isolated test key.
        Files.writeString(output.resolve("fixture-invalid.jwe"),
                ReportCrypto.encrypt("not-json".getBytes(StandardCharsets.UTF_8),
                        injected, TrustedServer.KID));
        System.out.println("PASS: " + passed + " upload JVM tests; no Android runtime used.");
    }

    private static String report(long sequence, long now, List<String> days) {
        return ReportJson.report("ecb254d5-54f0-4e2a-bbb9-732af20f213e", sequence, DEVICE,
                "本地联调红米 📱", now, true, "16", "HyperOS", days);
    }
    private static void write(Path out, String name, String body, PublicKey pub) throws Exception {
        Files.writeString(out.resolve(name + ".json"), body);
        Files.writeString(out.resolve(name + ".jwe"),
                ReportCrypto.encrypt(body.getBytes(StandardCharsets.UTF_8), pub, TrustedServer.KID));
    }
    private static byte[] decrypt(String jwe, PrivateKey key) throws Exception {
        String[] parts = jwe.split("\\.");
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] cek = rsa.doFinal(decode(parts[1]));
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"),
                new GCMParameterSpec(128, decode(parts[2])));
        aes.updateAAD(parts[0].getBytes(StandardCharsets.US_ASCII));
        byte[] cipher = decode(parts[3]), tag = decode(parts[4]);
        byte[] combined = Arrays.copyOf(cipher, cipher.length + tag.length);
        System.arraycopy(tag, 0, combined, cipher.length, tag.length);
        return aes.doFinal(combined);
    }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static long at(String value) {
        return LocalDateTime.parse(value).atZone(ZONE).toInstant().toEpochMilli();
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void rejects(Task task) throws Exception {
        try { task.run(); }
        catch (IllegalArgumentException | java.security.GeneralSecurityException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private static void test(String name, Task task) throws Exception {
        try { task.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError e) { throw new AssertionError(name, e); }
    }
}
