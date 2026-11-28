package dev.somgester.sylph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** A scoped loopback bridge for Kiro's local session-start hook. */
final class KiroSessionCapture implements AutoCloseable {

    record Attachment(String sessionId, Map<String, String> environment) { }

    @FunctionalInterface
    interface Attacher {
        CompletableFuture<Attachment> attach(String terminalId, Path directory);
    }

    @FunctionalInterface
    interface Receiver {
        CompletableFuture<Boolean> capture(String herdrId, String kiroId, Path directory, String pane);
    }

    private final HttpServer server;

    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    private final String token = UUID.randomUUID() + "-" + UUID.randomUUID();

    private final Path connectionFile;

    KiroSessionCapture(Receiver receiver) throws IOException {
        this(null, receiver);
    }

    KiroSessionCapture(Path connectionFile, Receiver receiver) throws IOException {
        this(connectionFile, receiver, null);
    }

    KiroSessionCapture(Path connectionFile, Receiver receiver, Attacher attacher) throws IOException {
        this.connectionFile = connectionFile == null ? null : connectionFile.toAbsolutePath();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(worker);
        server.createContext("/session", exchange -> receive(exchange, receiver));
        if (attacher != null) {
            server.createContext("/attach", exchange -> receiveAttach(exchange, attacher));
        }
        server.start();
        try {
            if (this.connectionFile != null) {
                writeConnection();
            }
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void receiveAttach(HttpExchange exchange, Attacher attacher) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().toString().equals("/attach")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readNBytes(16385);
                if (body.length > 16384) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                var request = JsonParser.parseString(StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(body)).toString()).getAsJsonObject();
                String instance = UUID.fromString(string(request, "terminalId")).toString();
                Path directory = Path.of(string(request, "directory"));
                if (!directory.isAbsolute()) {
                    throw new IllegalArgumentException("Expected an absolute directory");
                }
                var pending = attacher.attach(instance, directory.normalize());
                Attachment attachment;
                try {
                    attachment = pending.get(4, TimeUnit.SECONDS);
                } finally {
                    pending.cancel(false);
                }
                if (attachment == null) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                byte[] response = new com.google.gson.Gson().toJson(attachment).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (IllegalArgumentException | IllegalStateException | com.google.gson.JsonParseException
                    | java.nio.charset.CharacterCodingException malformed) {
                exchange.sendResponseHeaders(400, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(503, -1);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                exchange.sendResponseHeaders(503, -1);
            }
        }
    }

    Map<String, String> environment() {
        return connectionFile == null ? endpoint()
                : Map.of("SYLPH_KIRO_CAPTURE_FILE", connectionFile.toString());
    }

    private Map<String, String> endpoint() {
        return Map.of("SYLPH_KIRO_CAPTURE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/session",
                "SYLPH_KIRO_CAPTURE_TOKEN", token);
    }

    private void writeConnection() throws IOException {
        if (Files.isSymbolicLink(connectionFile)) {
            throw new IOException("Session capture endpoint must be a regular file.");
        }
        Files.createDirectories(connectionFile.getParent());
        Path temporary = Files.createTempFile(connectionFile.getParent(), ".kiro-capture-", ".tmp");
        try {
            if (Files.getFileAttributeView(temporary, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            JsonObject data = new JsonObject();
            endpoint().forEach(data::addProperty);
            Files.writeString(temporary, data.toString());
            Files.move(temporary, connectionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void receive(HttpExchange exchange, Receiver receiver) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().toString().equals("/session")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(
                    authorization.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(16385);
            if (body.length > 16384) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            try {
                JsonObject payload = JsonParser.parseString(StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(body)).toString()).getAsJsonObject();
                String herdr = string(payload, "herdrSessionId");
                String kiro = string(payload, "kiroSessionId");
                Path directory = Path.of(string(payload, "directory"));
                new HerdrSessionStore.Tab(herdr, kiro, directory, true, HerdrCliTheme.DEFAULT);
                String pane = payload.has("paneId") ? string(payload, "paneId") : null;
                if (pane != null) {
                    HerdrSessionStore.validatePane(pane);
                }
                boolean accepted = receiver.capture(herdr, kiro, directory, pane).get(4, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(accepted ? 204 : 410, -1);
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException
                    | com.google.gson.JsonParseException | java.nio.charset.CharacterCodingException malformed) {
                exchange.sendResponseHeaders(400, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(503, -1);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                exchange.sendResponseHeaders(503, -1);
            }
        }
    }

    private static String string(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected session metadata");
        }
        return value.getAsString();
    }

    @Override
    public void close() {
        server.stop(0);
        worker.shutdownNow();
    }
}
