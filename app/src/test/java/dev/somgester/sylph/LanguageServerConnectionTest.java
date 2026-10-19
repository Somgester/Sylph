package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LanguageServerConnectionTest {

    @TempDir
    Path directory;

    @Test
    void initializesBeforeSyncAndClosesDocumentsBeforeShutdownAndExit() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("folder with spaces"));
        try (var fixture = new LanguageServerTestSupport(workspace, LanguageServerTestSupport.FULL, "normal")) {
            var connection = fixture.connection;
            Path file = workspace.resolve("file with spaces.py");
            // Queue work before initialization completes.
            connection.open(file, EditorLanguage.PYTHON, "value = 1").get(5, TimeUnit.SECONDS);
            var initialize = fixture.await("initialize", 1).getAsJsonObject("params");
            assertEquals(workspace.toUri().toString(), initialize.getAsJsonArray("workspaceFolders")
                    .get(0).getAsJsonObject().get("uri").getAsString());
            assertEquals(List.of("utf-16"), initialize.getAsJsonObject("capabilities").getAsJsonObject("general")
                    .getAsJsonArray("positionEncodings").asList().stream().map(Object::toString)
                    .map(value -> value.replace("\"", "")).toList());
            assertEquals(workspace.toString(), fixture.await("test/started", 1).get("directory").getAsString());
            fixture.await("initialized", 1);
            var opened = fixture.await("textDocument/didOpen", 1).getAsJsonObject("params")
                    .getAsJsonObject("textDocument");
            assertEquals(file.toUri().toString(), opened.get("uri").getAsString());
            assertEquals("python", opened.get("languageId").getAsString());
            assertEquals(1, opened.get("version").getAsInt());
            connection.change(file, "value = 2").get(5, TimeUnit.SECONDS);
            connection.change(file, "value = 2").get(5, TimeUnit.SECONDS);
            connection.change(file, "value = 3").get(5, TimeUnit.SECONDS);
            var changed = fixture.await("textDocument/didChange", 2).getAsJsonObject("params");
            assertEquals(3, changed.getAsJsonObject("textDocument").get("version").getAsInt());
            var edit = changed.getAsJsonArray("contentChanges").get(0).getAsJsonObject();
            assertEquals("value = 3", edit.get("text").getAsString());
            assertFalse(edit.has("range"));
            // Saving an older snapshot must not roll back newer in-memory contents.
            connection.saved(file, "value = 2").get(5, TimeUnit.SECONDS);
            assertEquals("value = 2", fixture.await("textDocument/didSave", 1).getAsJsonObject("params")
                    .get("text").getAsString());
            connection.change(file, "value = 3").get(5, TimeUnit.SECONDS);
            connection.close();
            connection.stopped().get(5, TimeUnit.SECONDS);
            fixture.await("textDocument/didClose", 1);
            fixture.await("shutdown", 1);
            fixture.await("exit", 1);
            assertEquals(2, fixture.messages("textDocument/didChange").size());
            assertThrows(ExecutionException.class,
                    () -> connection.change(file, "late edit").get(5, TimeUnit.SECONDS));
            long pid = fixture.pid();
            LanguageServerTestSupport.waitUntil(() -> ProcessHandle.of(pid).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\r\nb", "a\rb", "a\n", "🙂x"})
    void incrementalSyncUsesUtf16RangesAndHandlesLineEndings(String original) throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory,
                "{\"textDocumentSync\":2}", "normal")) {
            Path file = directory.resolve("example.tsx");
            fixture.connection.open(file, EditorLanguage.TYPESCRIPT_TSX, original).get(5, TimeUnit.SECONDS);
            fixture.connection.change(file, "replacement").get(5, TimeUnit.SECONDS);
            var edit = fixture.await("textDocument/didChange", 1).getAsJsonObject("params")
                    .getAsJsonArray("contentChanges").get(0).getAsJsonObject();
            var end = edit.getAsJsonObject("range").getAsJsonObject("end");
            assertEquals(original.contains("\n") || original.contains("\r") ? 1 : 0, end.get("line").getAsInt());
            assertEquals(original.endsWith("\n") ? 0 : original.equals("🙂x") ? 3 : 1,
                    end.get("character").getAsInt());
            assertEquals("typescriptreact", fixture.await("textDocument/didOpen", 1).getAsJsonObject("params")
                    .getAsJsonObject("textDocument").get("languageId").getAsString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"textDocumentSync\":0}",
            "{\"textDocumentSync\":{\"openClose\":false,\"change\":0,\"save\":false}}"})
    void respectsServersThatDoNotRequestDocumentNotifications(String capabilities) throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, capabilities, "normal")) {
            Path file = directory.resolve("file.java");
            fixture.connection.open(file, EditorLanguage.JAVA, "old").get(5, TimeUnit.SECONDS);
            fixture.connection.change(file, "new").get(5, TimeUnit.SECONDS);
            fixture.connection.saved(file, "new").get(5, TimeUnit.SECONDS);
            fixture.connection.closeDocument(file).get(5, TimeUnit.SECONDS);
            fixture.connection.close();
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
            assertTrue(fixture.messages("textDocument/didOpen").isEmpty());
            assertTrue(fixture.messages("textDocument/didChange").isEmpty());
            assertTrue(fixture.messages("textDocument/didSave").isEmpty());
            assertTrue(fixture.messages("textDocument/didClose").isEmpty());
        }
    }

    @Test
    void saveWithoutIncludeTextDoesNotLeakTheBufferIntoNotification() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory,
                "{\"textDocumentSync\":{\"openClose\":true,\"change\":1,\"save\":true}}", "normal")) {
            Path file = directory.resolve("file.c");
            fixture.connection.open(file, EditorLanguage.C, "int value;").get(5, TimeUnit.SECONDS);
            fixture.connection.saved(file, "int value;").get(5, TimeUnit.SECONDS);
            assertFalse(fixture.await("textDocument/didSave", 1).getAsJsonObject("params").has("text"));
        }
    }

    @Test
    void editsClosureAndCallerCancellationSendProtocolCancellation() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "normal")) {
            var connection = fixture.connection;
            Path file = directory.resolve("file.java");
            connection.open(file, EditorLanguage.JAVA, "old").get(5, TimeUnit.SECONDS);
            var params = new HoverParams(new TextDocumentIdentifier(file.toUri().toString()), new Position(0, 0));
            var first = connection.request(file, server -> server.getTextDocumentService().hover(params));
            fixture.await("textDocument/hover", 1);
            connection.change(file, "new").get(5, TimeUnit.SECONDS);
            assertTrue(first.isCancelled());
            fixture.await("$/cancelRequest", 1);
            var second = connection.request(file, server -> server.getTextDocumentService().hover(params));
            fixture.await("textDocument/hover", 2);
            second.cancel(true);
            fixture.await("$/cancelRequest", 2);
            var third = connection.request(file, server -> server.getTextDocumentService().hover(params));
            fixture.await("textDocument/hover", 3);
            connection.closeDocument(file).get(5, TimeUnit.SECONDS);
            assertTrue(third.isCancelled());
            fixture.await("$/cancelRequest", 3);
            assertThrows(ExecutionException.class,
                    () -> connection.request(file, server -> server.shutdown()).get(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hang-initialize", "hang-shutdown"})
    void closeTerminatesUnresponsiveServersWithoutBlockingCaller(String mode) throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, mode)) {
            long pid = fixture.pid();
            if (mode.equals("hang-shutdown")) {
                fixture.connection.ready().get(5, TimeUnit.SECONDS);
            }
            long before = System.nanoTime();
            fixture.connection.close();
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1));
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
            LanguageServerTestSupport.waitUntil(() -> ProcessHandle.of(pid).isEmpty());
        }
    }

    @Test
    void requestQueuedAfterAnEditIsNotCancelledByThatEdit() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "reply-hover")) {
            Path file = directory.resolve("file.java");
            fixture.connection.open(file, EditorLanguage.JAVA, "old").get(5, TimeUnit.SECONDS);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                fixture.connection.request(file, server -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out releasing worker");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    return CompletableFuture.completedFuture(null);
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                fixture.connection.change(file, "new");
                var params = new HoverParams(new TextDocumentIdentifier(file.toUri().toString()), new Position(0, 0));
                var fresh = fixture.connection.request(file, server -> server.getTextDocumentService().hover(params));
                release.countDown();
                assertEquals("type information", fresh.get(5, TimeUnit.SECONDS).getContents().getRight().getValue());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closeCanInterruptAWorkerBlockedWritingToTheServer() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "block-input")) {
            Path file = directory.resolve("large.java");
            fixture.connection.open(file, EditorLanguage.JAVA, "old").get(5, TimeUnit.SECONDS);
            fixture.await("initialized", 1);
            var entered = new CountDownLatch(1);
            var update = new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(file.toUri().toString(), 2),
                    List.of(new TextDocumentContentChangeEvent("x".repeat(2_000_000))));
            var writing = fixture.connection.request(file, server -> {
                entered.countDown();
                server.getTextDocumentService().didChange(update);
                return CompletableFuture.completedFuture(null);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.connection.close();
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
            assertTrue(writing.isDone());
            long pid = fixture.pid();
            LanguageServerTestSupport.waitUntil(() -> ProcessHandle.of(pid).isEmpty());
        }
    }

    @Test
    void returnsServerResultsAndCleansUpWhenAnInitializedServerCrashes() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "reply-hover")) {
            Path file = directory.resolve("file.java");
            fixture.connection.open(file, EditorLanguage.JAVA, "class Example {}").get(5, TimeUnit.SECONDS);
            var params = new HoverParams(new TextDocumentIdentifier(file.toUri().toString()), new Position(0, 6));
            var response = fixture.connection.request(file, server -> server.getTextDocumentService().hover(params))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("type information", response.getContents().getRight().getValue());
            ProcessHandle.of(fixture.pid()).orElseThrow().destroyForcibly();
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,
                    () -> fixture.connection.change(file, "edit").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void missingExecutableAndStartupCrashFailWithoutHanging() throws Exception {
        var missing = new LanguageServerConnection(List.of(directory.resolve("missing").toString()),
                directory, LanguageServerTestSupport.client());
        try {
            assertThrows(ExecutionException.class, () -> missing.ready().get(5, TimeUnit.SECONDS));
            missing.stopped().get(5, TimeUnit.SECONDS);
        } finally {
            missing.close();
        }
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "crash")) {
            var opening = fixture.connection.open(directory.resolve("example.java"), EditorLanguage.JAVA, "text");
            assertThrows(ExecutionException.class, () -> fixture.connection.ready().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> opening.get(5, TimeUnit.SECONDS));
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void refusesAnEncodingThatWouldCorruptEditorPositions() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, "{\"positionEncoding\":\"utf-8\"}", "normal")) {
            assertThrows(ExecutionException.class, () -> fixture.connection.ready().get(5, TimeUnit.SECONDS));
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
        }
    }
}
