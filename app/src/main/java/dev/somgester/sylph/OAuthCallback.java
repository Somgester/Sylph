package dev.somgester.sylph;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One short-lived, loopback-only listener. Invalid requests never consume a legitimate login attempt. */
final class OAuthCallback implements AutoCloseable {

    private final HttpServer server;

    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    private final CompletableFuture<Map<String, String>> result = new CompletableFuture<>();

    private final URI uri;

    OAuthCallback(int port, String path, String state, String issuer, boolean codeRequired) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        server.setExecutor(worker);
        server.createContext("/", exchange -> receive(exchange, state, issuer, codeRequired));
        server.start();
    }

    URI uri() {
        return uri;
    }

    Map<String, String> await(Duration timeout) throws IOException, InterruptedException {
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new IOException("Browser sign-in timed out. Try again when ready.");
        } catch (ExecutionException error) {
            throw new IOException("Sign-in was cancelled or denied in the browser.");
        }
    }

    private void receive(HttpExchange exchange, String state, String issuer, boolean codeRequired) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 405, "Only browser callbacks are accepted.");
                return;
            }
            if (!exchange.getRequestURI().getRawPath().equals(uri.getRawPath())
                    || !uri.getRawAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) {
                reply(exchange, 404, "Unknown callback.");
                return;
            }
            Map<String, String> parameters;
            try {
                parameters = query(exchange.getRequestURI().getRawQuery());
            } catch (IllegalArgumentException error) {
                reply(exchange, 400, "Invalid callback parameters.");
                return;
            }
            if (!constant(state, parameters.get("state"))
                    || parameters.containsKey("iss") && !issuer.equals(parameters.get("iss"))) {
                reply(exchange, 400, "Callback does not match the active sign-in attempt.");
            } else if (result.isDone()) {
                reply(exchange, 410, "This sign-in attempt has already completed.");
            } else if (parameters.containsKey("error")) {
                reply(exchange, 200, "Sign-in cancelled. Return to Sylph.");
                result.completeExceptionally(new IOException("Authorization denied."));
            } else if (codeRequired && (!parameters.containsKey("code") || parameters.get("code").isBlank())) {
                reply(exchange, 400, "Missing authorization code.");
            } else {
                // Send the response before unblocking the worker, which closes this server.
                reply(exchange, 200, "Browser step complete. Return to Sylph; it will finish verification.");
                result.complete(parameters);
            }
        }
    }

    static Map<String, String> query(String query) {
        if (query == null || query.length() > 8192) {
            throw new IllegalArgumentException("Missing or oversized query.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length < 2 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate callback parameter.");
            }
        }
        return result;
    }

    private static boolean constant(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    @Override
    public void close() {
        server.stop(0);
        worker.close();
    }
}
