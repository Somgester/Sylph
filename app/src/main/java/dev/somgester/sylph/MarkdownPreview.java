package dev.somgester.sylph;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

final class MarkdownPreview extends BorderPane implements AutoCloseable {

    private final EditorSession session;

    private final AppSettings settings;

    private final WebView web = new WebView();

    private final Label loading = new Label("Rendering Markdown…");

    private final ScheduledThreadPoolExecutor worker;

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    private final ChangeListener<String> textListener;

    private final ChangeListener<Boolean> themeListener;

    private final ChangeListener<Worker.State> loadListener;

    private Future<?> pending;

    private boolean active;

    private boolean closed;

    private long revision;

    private Path previewPath;

    private double scrollX;

    private double scrollY;

    private boolean pageLoaded;

    private boolean loadingPage;

    @SuppressWarnings("this-escape")
    MarkdownPreview(EditorSession session, AppSettings settings, Runnable onClose) {
        this.session = session;
        this.settings = settings;
        setId("markdown-preview");
        setMinWidth(180);
        Label title = new Label("Markdown Preview");
        Button close = new Button("Close");
        close.setId("close-markdown-preview");
        close.setOnAction(event -> onClose.run());
        close.getStyleClass().add("chrome-action");
        BorderPane header = new BorderPane(title, null, close, null, null);
        header.setId("markdown-preview-header");
        setTop(header);
        web.setId("markdown-preview-web");
        web.setContextMenuEnabled(false);
        web.getEngine().setJavaScriptEnabled(false);
        web.getEngine().setCreatePopupHandler(features -> null);
        web.setVisible(false);
        setCenter(new StackPane(web, loading));
        worker = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "sylph-markdown");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        textListener = (observable, previous, text) -> request();
        themeListener = (observable, previous, dark) -> request();
        session.textProperty().addListener(textListener);
        settings.darkThemeProperty().addListener(themeListener);
        loadListener = (observable, previous, state) -> {
            if (active && !closed && loadingPage && state == Worker.State.SUCCEEDED) {
                // Only application-owned code runs here; document JavaScript stays disabled.
                JSObject window = (JSObject) web.getEngine().executeScript("window");
                window.call("scrollTo", scrollX, scrollY);
                pageLoaded = true;
                loadingPage = false;
                web.setVisible(true);
                loading.setVisible(false);
            } else if (active && !closed && loadingPage && state == Worker.State.FAILED) {
                loadingPage = false;
                loading.setText("Unable to render Markdown preview");
            }
        };
        web.getEngine().getLoadWorker().stateProperty().addListener(loadListener);
    }

    void setActive(boolean value) {
        if (closed) {
            return;
        }
        active = value;
        revision++;
        cancelPending();
        if (active) {
            request();
        } else {
            rememberScroll();
            pageLoaded = false;
            loadingPage = false;
            web.setVisible(false);
            loading.setVisible(true);
            web.getEngine().load(null);
        }
    }

    private void rememberScroll() {
        if (pageLoaded) {
            scrollX = ((Number) web.getEngine().executeScript("window.scrollX")).doubleValue();
            scrollY = ((Number) web.getEngine().executeScript("window.scrollY")).doubleValue();
        }
    }

    private void request() {
        if (!active || closed) {
            return;
        }
        long requestedRevision = ++revision;
        cancelPending();
        String text = session.textProperty().get();
        Path requestedPath = session.pathProperty().get();
        boolean dark = settings.darkThemeProperty().get();
        pending = worker.schedule(() -> {
            try {
                String html = renderer.render(text, dark);
                Platform.runLater(() -> {
                    if (active && !closed && revision == requestedRevision) {
                        rememberScroll();
                        if (!Objects.equals(previewPath, requestedPath)) {
                            scrollX = 0;
                            scrollY = 0;
                        }
                        previewPath = requestedPath;
                        pageLoaded = false;
                        loadingPage = true;
                        loading.setText("Rendering Markdown…");
                        loading.setVisible(true);
                        web.setVisible(false);
                        web.getEngine().loadContent(html);
                    }
                });
            } catch (RuntimeException failure) {
                System.getLogger(MarkdownPreview.class.getName()).log(Level.WARNING,
                        "Markdown preview failed", failure);
                Platform.runLater(() -> {
                    if (active && !closed && revision == requestedRevision) {
                        web.setVisible(false);
                        loading.setText("Unable to render Markdown preview");
                        loading.setVisible(true);
                    }
                });
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(true);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            active = false;
            revision++;
            cancelPending();
            worker.shutdownNow();
            session.textProperty().removeListener(textListener);
            settings.darkThemeProperty().removeListener(themeListener);
            web.getEngine().getLoadWorker().stateProperty().removeListener(loadListener);
            web.getEngine().load(null);
        }
    }
}
