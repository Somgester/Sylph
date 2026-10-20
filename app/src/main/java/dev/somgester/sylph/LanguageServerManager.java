package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

/** Owns server processes, grouped by language profile and workspace, for the lifetime of the application. */
final class LanguageServerManager implements AutoCloseable {

    interface Factory {
        LanguageServerConnection start(List<String> command, Path directory);
    }

    private record Key(LanguageServerSettings.Server server, Path directory) { }

    private static final class Entry {
        private final LanguageServerConnection connection;

        private int users;

        private boolean ready;

        private String failure;

        Entry(LanguageServerConnection connection) {
            this.connection = connection;
        }
    }

    private final class DocumentBinding {
        private final EditorDocument document;

        private final InvalidationListener changed = observable -> refresh(this);

        private final Runnable unsubscribeClose;

        private Entry entry;

        private Key key;

        DocumentBinding(EditorDocument document) {
            this.document = document;
            document.session.pathProperty().addListener(changed);
            document.session.editingBlockedProperty().addListener(changed);
            unsubscribeClose = document.onClose(() -> unwatch(document));
        }

        void close() {
            document.session.pathProperty().removeListener(changed);
            document.session.editingBlockedProperty().removeListener(changed);
            unsubscribeClose.run();
            release(this);
        }
    }

    private final LanguageServerSettings settings;

    private final Factory factory;

    private final Map<EditorDocument, DocumentBinding> documents = new IdentityHashMap<>();

    private final Map<Key, Entry> entries = new LinkedHashMap<>();

    private final EnumMap<LanguageServerSettings.Server, ReadOnlyStringWrapper> states
            = new EnumMap<>(LanguageServerSettings.Server.class);

    private final EnumMap<LanguageServerSettings.Server, InvalidationListener> listeners
            = new EnumMap<>(LanguageServerSettings.Server.class);

    private Path workspace;

    private boolean closed;

    LanguageServerManager(LanguageServerSettings settings) {
        this(settings, (command, directory) -> new LanguageServerConnection(command, directory, new Client()));
    }

    LanguageServerManager(LanguageServerSettings settings, Factory factory) {
        this.settings = settings;
        this.factory = factory;
        for (var server : LanguageServerSettings.Server.values()) {
            states.put(server, new ReadOnlyStringWrapper());
            InvalidationListener listener = observable -> restart(server);
            listeners.put(server, listener);
            settings.configuration(server).addListener(listener);
            updateStatus(server);
        }
    }

    void watch(EditorDocument document) {
        if (closed || documents.containsKey(document)) {
            return;
        }
        var binding = new DocumentBinding(document);
        documents.put(document, binding);
        refresh(binding);
    }

    private void unwatch(EditorDocument document) {
        var binding = documents.remove(document);
        if (binding != null) {
            binding.close();
        }
    }

    void setWorkspace(Path directory) {
        workspace = directory == null ? null : directory.toAbsolutePath().normalize();
        List.copyOf(documents.values()).forEach(this::refresh);
    }

    ReadOnlyStringProperty status(LanguageServerSettings.Server server) {
        return states.get(server).getReadOnlyProperty();
    }

    private void restart(LanguageServerSettings.Server server) {
        // Read the property on every invalidation so applying identical settings also retries startup.
        settings.configuration(server).get();
        for (var binding : List.copyOf(documents.values())) {
            if (binding.key != null && binding.key.server() == server) {
                release(binding);
            }
        }
        List.copyOf(documents.values()).forEach(this::refresh);
        updateStatus(server);
    }

    private void refresh(DocumentBinding binding) {
        if (closed || binding.document.session.editingBlockedProperty().get()) {
            return;
        }
        var session = binding.document.session;
        Path path = session.pathProperty().get();
        var server = LanguageServerSettings.Server.PYTHON;
        var config = settings.configuration(server).get();
        Key next = null;
        if (path != null && session.languageProperty().get() == EditorLanguage.PYTHON && config.enabled()) {
            Path root = workspace != null && path.startsWith(workspace) ? workspace : path.getParent();
            next = new Key(server, root);
        }
        if (java.util.Objects.equals(next, binding.key)) {
            return;
        }
        release(binding);
        if (next == null) {
            return;
        }
        Key key = next;
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(factory.start(config.command(server), key.directory()));
            entries.put(key, entry);
            observe(key, entry);
        }
        binding.key = key;
        binding.entry = entry;
        entry.users++;
        if (entry.failure == null) {
            Entry attached = entry;
            binding.document.attachLanguageServer(entry.connection, error -> failed(key, attached, error));
        }
        updateStatus(server);
    }

    private void observe(Key key, Entry entry) {
        entry.connection.ready().whenComplete((capabilities, error) -> Platform.runLater(() -> {
            if (closed || entries.get(key) != entry) {
                return;
            }
            if (error != null) {
                failed(key, entry, error);
            } else {
                entry.ready = true;
                updateStatus(key.server());
            }
        }));
        entry.connection.stopped().thenRun(() -> Platform.runLater(() -> {
            if (!closed && entries.get(key) == entry && entry.failure == null) {
                failed(key, entry, new IllegalStateException("Language server stopped"));
            }
        }));
    }

    private void failed(Key key, Entry entry, Throwable error) {
        if (closed || entries.get(key) != entry || entry.failure != null) {
            return;
        }
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        entry.failure = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        for (var binding : documents.values()) {
            if (binding.entry == entry) {
                binding.document.detachLanguageServer();
            }
        }
        entry.connection.close();
        updateStatus(key.server());
    }

    private void release(DocumentBinding binding) {
        if (binding.entry == null) {
            return;
        }
        Entry previous = binding.entry;
        Key key = binding.key;
        binding.document.detachLanguageServer();
        binding.entry = null;
        binding.key = null;
        if (--previous.users == 0) {
            entries.remove(key);
            previous.connection.close();
        }
        updateStatus(key.server());
    }

    private void updateStatus(LanguageServerSettings.Server server) {
        String message;
        if (!settings.configuration(server).get().enabled()) {
            message = "Disabled";
        } else {
            var active = entries.entrySet().stream().filter(item -> item.getKey().server() == server)
                    .map(Map.Entry::getValue).toList();
            var failure = active.stream().filter(item -> item.failure != null).findFirst();
            if (failure.isPresent()) {
                message = "Failed: " + failure.orElseThrow().failure + ". Apply settings to retry.";
            } else if (active.isEmpty()) {
                message = "Enabled. Open a Python file to start Pyright.";
            } else if (active.stream().anyMatch(item -> !item.ready)) {
                message = "Starting Pyright…";
            } else {
                message = "Ready — " + active.size() + (active.size() == 1 ? " workspace" : " workspaces");
            }
        }
        states.get(server).set(message);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (var server : LanguageServerSettings.Server.values()) {
            settings.configuration(server).removeListener(listeners.get(server));
        }
        new ArrayList<>(documents.keySet()).forEach(this::unwatch);
    }

    private static final class Client implements LanguageClient {
        @Override
        public void telemetryEvent(Object value) { }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) { }

        @Override
        public void showMessage(MessageParams params) {
            logMessage(params);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            logMessage(params);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
            System.getLogger(LanguageServerManager.class.getName()).log(System.Logger.Level.INFO, params.getMessage());
        }
    }
}
