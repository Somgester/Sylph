package dev.somgester.sylph;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

/** Routes diagnostics to document listeners while preserving the caller's logging and message handlers. */
final class LanguageServerClient implements LanguageClient {

    private final LanguageClient delegate;

    private final Consumer<PublishDiagnosticsParams> diagnostics;

    LanguageServerClient(LanguageClient delegate, Consumer<PublishDiagnosticsParams> diagnostics) {
        this.delegate = delegate;
        this.diagnostics = diagnostics;
    }

    @Override
    public void telemetryEvent(Object value) {
        delegate.telemetryEvent(value);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        diagnostics.accept(params);
        delegate.publishDiagnostics(params);
    }

    @Override
    public void showMessage(MessageParams params) {
        delegate.showMessage(params);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return delegate.showMessageRequest(params);
    }

    @Override
    public void logMessage(MessageParams params) {
        delegate.logMessage(params);
    }
}
