package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

final class TerminalView extends BorderPane implements AutoCloseable {

    private final TerminalSession session;

    private final AppSettings settings;

    private final WebView web = new WebView();

    private final Label status = new Label();

    private final Semaphore drained = new Semaphore(0);

    private final TerminalBridge bridge;

    private final ChangeListener<Boolean> themeListener;

    private final ChangeListener<Worker.State> loadListener;

    private final ContextMenu menu = new ContextMenu();

    private boolean ready;

    private long focusRevision;

    private volatile boolean closed;

    TerminalView(Path directory, AppSettings settings) {
        this(new TerminalSession(directory), settings);
    }

    @SuppressWarnings("this-escape")
    TerminalView(TerminalSession session, AppSettings settings) {
        this.session = session;
        this.settings = settings;
        bridge = new TerminalBridge(session, drained::release);
        getStyleClass().add("terminal-view");
        setMinSize(100, 80);
        setFocusTraversable(true);
        focusedProperty().addListener((observable, previous, current) -> {
            if (current && ready) {
                Platform.runLater(() -> {
                    if (!closed && focused(getScene()) == this) {
                        focusNow();
                    }
                });
            }
        });
        web.setMinSize(0, 0);
        web.setVisible(false);
        web.setContextMenuEnabled(false);
        web.getEngine().setCreatePopupHandler(features -> null);
        web.getEngine().setConfirmHandler(message -> false);
        status.setText("Starting terminal  |  " + session.directory());
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        var statusTooltip = new javafx.scene.control.Tooltip();
        statusTooltip.textProperty().bind(status.textProperty());
        status.setTooltip(statusTooltip);
        status.getStyleClass().add("terminal-status");
        setCenter(web);
        setBottom(status);
        MenuItem copy = new MenuItem("Copy selection (Ctrl+Shift+C)");
        copy.setOnAction(event -> copySelection());
        MenuItem paste = new MenuItem("Paste (Ctrl+Shift+V)");
        paste.setOnAction(event -> pasteClipboard());
        menu.getItems().addAll(copy, paste);
        web.setOnContextMenuRequested(event -> {
            menu.show(web, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        web.addEventFilter(KeyEvent.KEY_PRESSED, this::terminalKey);
        themeListener = (observable, previous, dark) -> {
            if (ready && !closed) {
                call("setTheme", dark);
            }
        };
        settings.darkThemeProperty().addListener(themeListener);
        loadListener = (observable, previous, state) -> {
            if (closed) {
                return;
            }
            if (state == Worker.State.SUCCEEDED) {
                try {
                    call("initialize", bridge, settings.darkThemeProperty().get());
                    ready = true;
                    web.setVisible(true);
                    startSession();
                    if (focused(getScene()) == this) {
                        focusNow();
                    }
                } catch (RuntimeException failure) {
                    failRenderer("Unable to initialize terminal: " + failure.getMessage());
                }
            } else if (state == Worker.State.FAILED) {
                failRenderer("Unable to load terminal renderer");
            }
        };
        web.getEngine().getLoadWorker().stateProperty().addListener(loadListener);
        web.getEngine().loadContent(TerminalPage.html());
    }

    TerminalSession session() {
        return session;
    }

    private Object call(String method, Object... arguments) {
        // Resolve against the current page instead of retaining a native JS peer across events.
        Object api = web.getEngine().executeScript("window.sylphTerminal");
        if (!(api instanceof JSObject object)) {
            throw new IllegalStateException("Terminal page is no longer available");
        }
        return object.call(method, arguments);
    }

    private void failRenderer(String message) {
        status.setText(message);
        drained.release();
        session.close();
    }

    private void startSession() {
        session.start(new TerminalSession.Listener() {
            @Override
            public void started() {
                Platform.runLater(() -> {
                    if (!closed) {
                        status.setText("Shell running  |  " + session.directory());
                    }
                });
            }

            @Override
            public void output(String text) throws InterruptedException {
                if (closed) {
                    throw new InterruptedException("Terminal closed");
                }
                Platform.runLater(() -> {
                    if (closed) {
                        drained.release();
                    } else {
                        try {
                            call("write", text);
                        } catch (RuntimeException failure) {
                            System.getLogger(TerminalView.class.getName()).log(System.Logger.Level.WARNING,
                                    "Terminal renderer failed", failure);
                            failRenderer("Terminal renderer failed: " + failure.getMessage());
                        }
                    }
                });
                drained.acquire();
            }

            @Override
            public void ended(Integer exitCode, Throwable failure) {
                Platform.runLater(() -> {
                    if (!closed) {
                        String message = failure == null ? "Shell exited: " + exitCode
                                : "Terminal failed: " + failure.getMessage();
                        status.setText(message + "  |  " + session.directory());
                        call("ended", message);
                    }
                });
            }
        });
    }

    static boolean ownsFocus(Scene scene) {
        return focused(scene) != null;
    }

    private static TerminalView focused(Scene scene) {
        for (Node node = scene == null ? null : scene.getFocusOwner(); node != null; node = node.getParent()) {
            if (node instanceof TerminalView terminal) {
                return terminal;
            }
        }
        return null;
    }

    private void terminalKey(KeyEvent event) {
        KeyCode key = event.getCode();
        if (event.isAltDown()) {
            return;
        }
        boolean copy = key == KeyCode.C && (event.isMetaDown() || event.isControlDown() && event.isShiftDown());
        boolean paste = key == KeyCode.V && (event.isMetaDown() || event.isControlDown() && event.isShiftDown());
        if (copy || paste) {
            if (copy) {
                copySelection();
            } else {
                pasteClipboard();
            }
            event.consume();
        } else if (event.isMetaDown() && (key == KeyCode.A || key == KeyCode.X || key == KeyCode.Z)) {
            if (ready && key == KeyCode.A) {
                call("selectAll");
            } else if (key == KeyCode.X) {
                copySelection();
            }
            event.consume();
        } else if (event.isControlDown() && !event.isMetaDown()
                && (key == KeyCode.C || key == KeyCode.V || key == KeyCode.X
                || key == KeyCode.A || key == KeyCode.Z)) {
            // Prevent editor menu accelerators from stealing the shell's control characters.
            session.send(String.valueOf((char) (key.getCode() - KeyCode.A.getCode() + 1)));
            event.consume();
        }
    }

    boolean hasSelection() {
        return canSelectAll() && !((String) call("selection")).isEmpty();
    }

    boolean canSelectAll() {
        return ready && !closed && isShowing();
    }

    boolean canPaste() {
        return canSelectAll() && !session.completion().isDone();
    }

    void selectAll() {
        if (canSelectAll()) {
            call("selectAll");
        }
    }

    void copySelection() {
        if (ready && !closed) {
            String text = (String) call("selection");
            if (!text.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
            }
        }
    }

    void pasteClipboard() {
        if (ready && !closed && Clipboard.getSystemClipboard().hasString()) {
            call("paste", Clipboard.getSystemClipboard().getString());
        }
    }

    void focusTerminal(Scene scene) {
        if (scene == null || closed) {
            return;
        }
        long requested = ++focusRevision;
        if (getScene() == scene && isShowing()) {
            focusNow();
            return;
        }
        Node previous = scene.getFocusOwner();
        Platform.runLater(() -> {
            if (!closed && requested == focusRevision && getScene() == scene && isShowing()
                    && scene.getFocusOwner() == previous) {
                focusNow();
            }
        });
    }

    private void focusNow() {
        if (ready) {
            web.requestFocus();
            call("focus");
        } else {
            // Claim focus while loading so completion only continues an existing focus request.
            requestFocus();
        }
    }

    private boolean isShowing() {
        if (getScene() == null) {
            return false;
        }
        for (Node node = this; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            drained.release();
            session.close();
            menu.hide();
            settings.darkThemeProperty().removeListener(themeListener);
            web.getEngine().getLoadWorker().stateProperty().removeListener(loadListener);
            ready = false;
            web.getEngine().load(null);
        }
    }
}
