package dev.checkwakeup;

import com.sun.net.httpserver.HttpServer;
import dev.checkwakeup.core.ReportTransport;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback-only, finite tests of the app's actual HttpURLConnection transport. */
public final class TransportTests {
    private TransportTests() {}
    public static void main(String[] args) throws Exception {
        String wire = Files.readString(Path.of(args[0]));
        AtomicInteger accepts = new AtomicInteger();
        AtomicReference<String> seen = new AtomicReference<>("");
        AtomicReference<String> type = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/accept", exchange -> {
            accepts.incrementAndGet();
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII));
            type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] ack = "{\"schema_version\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ack.length);
            exchange.getResponseBody().write(ack);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/accept");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/rate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "120");
            byte[] error = "{\"error\":{\"code\":\"RATE_LIMITED\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });
        server.createContext("/huge", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 70_000);
            exchange.getResponseBody().write(new byte[70_000]);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            ReportTransport.Response ok = ReportTransport.post(base + "/accept", wire, connection -> {
                check(connection.getConnectTimeout() == 8000 && connection.getReadTimeout() == 10000);
                check(!connection.getInstanceFollowRedirects());
                return true;
            });
            check(ok.status == 200 && ok.body.equals("{\"schema_version\":1}"));
            System.out.println("PASS real transport uses fixed timeouts and parses successful response");
            check(seen.get().equals(wire) && seen.get().split("\\.").length == 5
                    && !seen.get().contains("device_code") && "application/jose".equals(type.get()));
            System.out.println("PASS HTTP wire carries JWE only with the correct content type");
            ReportTransport.Response redirected = ReportTransport.post(base + "/redirect", wire, c -> true);
            check(redirected.status == 302 && accepts.get() == 1);
            System.out.println("PASS redirects never forward encrypted device data");
            ReportTransport.Response rate = ReportTransport.post(base + "/rate", wire, c -> true);
            check(rate.status == 429 && "120".equals(rate.retryAfter) && rate.body.contains("RATE_LIMITED"));
            System.out.println("PASS rate-limit error and Retry-After reach the queue policy");
            try {
                ReportTransport.post(base + "/huge", wire, c -> true);
                throw new AssertionError("Oversized response accepted");
            } catch (java.io.IOException expected) {
                System.out.println("PASS response body is capped at 64 KiB");
            }
            try {
                ReportTransport.post(base + "/accept", wire, c -> false);
                throw new AssertionError("Cancelled config sent");
            } catch (java.io.IOException expected) {
                check(accepts.get() == 1);
                System.out.println("PASS disabled/stale configuration declines connection before any POST");
            }
            System.out.println("PASS: 6 loopback transport checks; no production traffic.");
        } finally { server.stop(0); }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
