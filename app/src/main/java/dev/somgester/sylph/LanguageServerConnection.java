package dev.somgester.sylph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.GeneralClientCapabilities;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/** One explicitly configured stdio server. All outgoing document operations are serialized off the UI thread. */
final class LanguageServerConnection implements AutoCloseable {

    record Diagnostics(String uri, String text, int version, List<Diagnostic> items) {
        Diagnostics {
            items = List.copyOf(items);
        }
    }

    private final List<Consumer<Diagnostics>> diagnosticListeners = new CopyOnWriteArrayList<>();

    private final Map<String, Integer> versions = new LinkedHashMap<>();

    private record Document(String language, String text, int version) { }

    private record Request(String uri, CompletableFuture<?> result) { }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> daemon(action, "sylph-lsp"));

    private final ExecutorService transport = Executors.newCachedThreadPool(action -> daemon(action, "sylph-lsp-io"));

    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1,
            action -> daemon(action, "sylph-lsp-cleanup"));

    private final AtomicBoolean closed = new AtomicBoolean();

    private final CompletableFuture<ServerCapabilities> ready = new CompletableFuture<>();

    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    private final Map<String, Document> documents = new LinkedHashMap<>();

    private final Set<Request> requests = ConcurrentHashMap.newKeySet();

    private volatile CompletableFuture<InitializeResult> initializing;

    private volatile Throwable failure;

    private volatile Process process;

    private Future<Void> listening;

    private LanguageServer server;

    private TextDocumentSyncOptions sync = new TextDocumentSyncOptions();

    LanguageServerConnection(List<String> command, Path workspace, LanguageClient client) {
        Objects.requireNonNull(client);
        List<String> arguments = List.copyOf(command);
        if (arguments.isEmpty() || arguments.getFirst().isBlank()) {
            throw new IllegalArgumentException("A language-server executable is required");
        }
        Path directory = workspace.toAbsolutePath().normalize();
        worker.execute(() -> initialize(arguments, directory, client));
    }

    CompletableFuture<ServerCapabilities> ready() {
        return ready.copy();
    }

    CompletableFuture<Void> stopped() {
        return stopped.copy();
    }

    @SuppressWarnings("deprecation") // rootUri remains necessary for servers without workspace-folder support.
    private void initialize(List<String> command, Path directory, LanguageClient client) {
        try {
            if (closed.get()) {
                return;
            }
            // Arguments are passed directly; never interpret a configured command through a shell.
            process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Launcher<LanguageServer> launcher = new Launcher.Builder<LanguageServer>()
                    .setLocalService(new LanguageServerClient(client, this::publishDiagnostics))
                    .setRemoteInterface(LanguageServer.class)
                    .setInput(process.getInputStream()).setOutput(process.getOutputStream())
                    .setExecutorService(transport).create();
            server = launcher.getRemoteProxy();
            listening = launcher.startListening();
            Future<Void> stream = listening;
            transport.execute(() -> {
                try {
                    stream.get();
                    fail(new IOException("Language server disconnected"));
                } catch (Exception error) {
                    fail(error);
                }
            });
            process.onExit().thenRun(() -> fail(new IOException("Language server exited")));
            var capabilities = new ClientCapabilities();
            var general = new GeneralClientCapabilities();
            general.setPositionEncodings(List.of(PositionEncodingKind.UTF16));
            capabilities.setGeneral(general);
            var documentCapabilities = new TextDocumentClientCapabilities();
            var completion = new CompletionCapabilities();
            var completionItem = new CompletionItemCapabilities();
            completionItem.setSnippetSupport(false);
            completionItem.setInsertReplaceSupport(true);
            completion.setCompletionItem(completionItem);
            completion.setContextSupport(true);
            documentCapabilities.setCompletion(completion);
            var hover = new HoverCapabilities();
            hover.setContentFormat(List.of(MarkupKind.PLAINTEXT));
            documentCapabilities.setHover(hover);
            var definition = new DefinitionCapabilities();
            definition.setLinkSupport(true);
            documentCapabilities.setDefinition(definition);
            var diagnostics = new PublishDiagnosticsCapabilities();
            diagnostics.setVersionSupport(true);
            documentCapabilities.setPublishDiagnostics(diagnostics);
            capabilities.setTextDocument(documentCapabilities);
            var params = new InitializeParams();
            params.setProcessId(Math.toIntExact(ProcessHandle.current().pid()));
            params.setClientInfo(new ClientInfo("Sylph"));
            params.setCapabilities(capabilities);
            params.setRootUri(directory.toUri().toString());
            params.setWorkspaceFolders(List.of(new WorkspaceFolder(directory.toUri().toString(),
                    directory.getFileName() == null ? directory.toString() : directory.getFileName().toString())));
            initializing = server.initialize(params);
            if (closed.get()) {
                initializing.cancel(true);
            }
            var result = initializing.get(10, TimeUnit.SECONDS);
            var announced = result.getCapabilities();
            if (announced.getPositionEncoding() != null
                    && !PositionEncodingKind.UTF16.equals(announced.getPositionEncoding())) {
                throw new IOException("Language server requires an unsupported position encoding");
            }
            if (announced.getTextDocumentSync() != null) {
                var value = announced.getTextDocumentSync();
                if (value.isRight()) {
                    sync = value.getRight();
                } else {
                    sync.setOpenClose(value.getLeft() != TextDocumentSyncKind.None);
                    sync.setChange(value.getLeft());
                }
            }
            server.initialized(new InitializedParams());
            ready.complete(announced);
        } catch (Exception error) {
            fail(error);
        } finally {
            initializing = null;
        }
    }

    Runnable onDiagnostics(Consumer<Diagnostics> listener) {
        diagnosticListeners.add(listener);
        return () -> diagnosticListeners.remove(listener);
    }

    private void publishDiagnostics(PublishDiagnosticsParams params) {
        if (params == null || params.getUri() == null || params.getDiagnostics() == null) {
            return;
        }
        enqueue(() -> {
            Document document = documents.get(params.getUri());
            if (document != null && (params.getVersion() == null || params.getVersion() == document.version())) {
                var update = new Diagnostics(params.getUri(), document.text(), document.version(),
                        params.getDiagnostics());
                diagnosticListeners.forEach(listener -> listener.accept(update));
            }
        });
    }

    CompletableFuture<Void> open(Path path, EditorLanguage language, String text) {
        String uri = uri(path);
        return enqueue(() -> {
            if (documents.containsKey(uri)) {
                throw new IllegalStateException("Document is already open: " + uri);
            }
            int version = Math.incrementExact(versions.getOrDefault(uri, 0));
            versions.put(uri, version);
            documents.put(uri, new Document(language.languageServerId(), text, version));
            if (Boolean.TRUE.equals(sync.getOpenClose())) {
                server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                        new TextDocumentItem(uri, language.languageServerId(), version, text)));
            }
        });
    }

    CompletableFuture<Void> change(Path path, String text) {
        String uri = uri(path);
        return enqueue(() -> change(uri, text));
    }

    private void change(String uri, String text) {
        Document previous = document(uri);
        if (previous.text().equals(text)) {
            return;
        }
        cancelRequests(uri);
        var next = new Document(previous.language(), text, Math.incrementExact(previous.version()));
        documents.put(uri, next);
        versions.put(uri, next.version());
        TextDocumentSyncKind kind = sync.getChange();
        if (kind == TextDocumentSyncKind.Full || kind == TextDocumentSyncKind.Incremental) {
            var change = new TextDocumentContentChangeEvent(text);
            if (kind == TextDocumentSyncKind.Incremental) {
                // A whole-document range is a valid incremental edit, including UTF-16 astral characters.
                change.setRange(new Range(new Position(0, 0), end(previous.text())));
            }
            server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, next.version()), List.of(change)));
        }
    }

    CompletableFuture<Void> saved(Path path, String text) {
        String uri = uri(path);
        return enqueue(() -> {
            document(uri);
            var save = sync.getSave();
            if (save != null && (save.isRight() || Boolean.TRUE.equals(save.getLeft()))) {
                var params = new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri));
                if (save.isRight() && Boolean.TRUE.equals(save.getRight().getIncludeText())) {
                    params.setText(text);
                }
                server.getTextDocumentService().didSave(params);
            }
        });
    }

    CompletableFuture<Void> closeDocument(Path path) {
        String uri = uri(path);
        return enqueue(() -> closeDocument(uri));
    }

    private void closeDocument(String uri) {
        cancelRequests(uri);
        if (documents.remove(uri) != null && Boolean.TRUE.equals(sync.getOpenClose())) {
            server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
        }
    }

    /** Cancellation propagates to LSP4J; edits and document closure cancel outstanding results too. */
    <T> CompletableFuture<T> request(Path path, Function<LanguageServer, CompletableFuture<T>> action) {
        String uri = uri(path);
        var result = new CompletableFuture<T>();
        var request = new Request(uri, result);
        result.whenComplete((value, error) -> requests.remove(request));
        enqueue(() -> {
            document(uri);
            synchronized (worker) {
                if (closed.get()) {
                    throw new IllegalStateException("Language server is closed", failure);
                }
                if (result.isDone()) {
                    return;
                }
                // Earlier queued edits cancel only requests that were actually sent before those edits.
                requests.add(request);
            }
            var remote = action.apply(server);
            result.whenComplete((value, error) -> {
                if (result.isCancelled() || error instanceof TimeoutException) {
                    cancelRemote(remote);
                }
            });
            remote.whenComplete((value, error) -> {
                if (error == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(error);
                }
            });
        }).exceptionally(error -> {
            result.completeExceptionally(error);
            return null;
        });
        return result;
    }

    private Document document(String uri) {
        Document value = documents.get(uri);
        if (value == null) {
            throw new IllegalStateException("Document is not open: " + uri);
        }
        return value;
    }

    private CompletableFuture<Void> enqueue(Runnable action) {
        var result = new CompletableFuture<Void>();
        // Serialize the closed check with close() so no task races executor shutdown.
        synchronized (worker) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Language server is closed", failure));
            }
            worker.execute(() -> {
                try {
                    if (closed.get()) {
                        throw new IllegalStateException("Language server is closed", failure);
                    }
                    if (failure != null) {
                        throw new IllegalStateException("Language server is unavailable", failure);
                    }
                    action.run();
                    result.complete(null);
                } catch (Exception error) {
                    result.completeExceptionally(error);
                }
            });
        }
        return result;
    }

    private void cancelRemote(CompletableFuture<?> remote) {
        try {
            // LSP4J cancellation writes a protocol message, so it must never run on the JavaFX thread.
            transport.execute(() -> remote.cancel(true));
        } catch (RejectedExecutionException ignored) {
            // The transport is already disposed; no remote process remains to notify.
        }
    }

    private void cancelRequests(String uri) {
        for (var request : requests) {
            if (uri == null || request.uri().equals(uri)) {
                request.result().cancel(true);
            }
        }
    }

    @Override
    public void close() {
        synchronized (worker) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ready.completeExceptionally(new IllegalStateException("Language server is closed", failure));
            var startup = initializing;
            if (startup != null) {
                cancelRemote(startup);
            }
            cancelRequests(null);
            // A blocked pipe write can prevent the worker reaching its shutdown task. Kill independently.
            watchdog.schedule(this::terminateProcess, 2, TimeUnit.SECONDS);
            worker.execute(() -> {
                try {
                    if (server != null && ready.isDone() && !ready.isCompletedExceptionally() && failure == null) {
                        for (String uri : List.copyOf(documents.keySet())) {
                            closeDocument(uri);
                        }
                        server.shutdown().get(1, TimeUnit.SECONDS);
                        server.exit();
                        process.waitFor(1, TimeUnit.SECONDS);
                    }
                } catch (Exception error) {
                    // Shutdown is best-effort; disposal below forcibly terminates an unresponsive server.
                } finally {
                    dispose();
                    stopped.complete(null);
                }
            });
            worker.shutdown();
        }
    }

    private void fail(Throwable error) {
        synchronized (worker) {
            if (!closed.get()) {
                failure = error;
                ready.completeExceptionally(error);
                close();
            }
        }
    }

    private void dispose() {
        cancelRequests(null);
        if (initializing != null) {
            initializing.cancel(true);
        }
        if (process != null) {
            terminateProcess();
            try {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            } catch (IOException ignored) {
                // Process termination can already have closed its streams.
            }
        }
        if (listening != null) {
            listening.cancel(true);
        }
        diagnosticListeners.clear();
        transport.shutdownNow();
        watchdog.shutdownNow();
    }

    private void terminateProcess() {
        Process running = process;
        if (running != null) {
            running.descendants().forEach(ProcessHandle::destroyForcibly);
            running.destroyForcibly();
        }
    }

    private static Position end(String text) {
        int line = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                line++;
                start = index + 1;
            }
        }
        return new Position(line, text.length() - start);
    }

    private static String uri(Path path) {
        return path.toAbsolutePath().normalize().toUri().toString();
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }
}
