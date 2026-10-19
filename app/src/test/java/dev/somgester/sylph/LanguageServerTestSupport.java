package dev.somgester.sylph;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

final class LanguageServerTestSupport implements AutoCloseable {

    static final String FULL = "{\"textDocumentSync\":{\"openClose\":true,\"change\":1,"
            + "\"save\":{\"includeText\":true}}}";

    final LanguageServerConnection connection;

    final Path log;

    LanguageServerTestSupport(Path directory, String capabilities, String mode) throws Exception {
        log = directory.resolve("server-" + java.util.UUID.randomUUID() + ".jsonl");
        String classpath = Path.of(FakeLanguageServer.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        connection = new LanguageServerConnection(List.of(java, "-cp", classpath, FakeLanguageServer.class.getName(),
                log.toString(), capabilities, mode), directory, new QuietClient());
    }

    List<JsonObject> messages(String method) {
        try {
            if (!Files.exists(log)) {
                return List.of();
            }
            return Files.readAllLines(log).stream().filter(line -> line.endsWith("}"))
                    .map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(value -> method.equals(value.get("method").getAsString())).toList();
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    JsonObject await(String method, int count) throws Exception {
        waitUntil(() -> messages(method).size() >= count);
        return messages(method).get(count - 1);
    }

    long pid() throws Exception {
        return await("test/started", 1).get("pid").getAsLong();
    }

    static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for language server");
            }
            Thread.sleep(10);
        }
    }

    @Override
    public void close() {
        connection.close();
        try {
            connection.stopped().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    static LanguageClient client() {
        return new QuietClient();
    }

    private static final class QuietClient implements LanguageClient {

        @Override
        public void telemetryEvent(Object object) { }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) { }

        @Override
        public void showMessage(MessageParams params) { }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) { }
    }
}
