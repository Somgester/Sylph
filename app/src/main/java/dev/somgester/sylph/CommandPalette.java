package dev.somgester.sylph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

// An in-scene overlay: no extra window, and theme values inherit from the editor.
final class CommandPalette extends StackPane implements AutoCloseable {

    private record Entry(String label, EditorShortcuts.Command command, Path file) { }

    private final BorderPane host;

    private final Supplier<Path> folder;

    private final Consumer<Path> openFile;

    private final Consumer<EditorShortcuts.Command> execute;

    private final Predicate<EditorShortcuts.Command> enabled;

    private final TextField query = new TextField();

    private final ListView<Entry> results = new ListView<>();

    private final Label status = new Label();

    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "sylph-file-search");
        thread.setDaemon(true);
        return thread;
    });

    private final InvalidationListener resizeListener = observable -> resizeToHost();

    private Future<?> pending;

    private long revision;

    private boolean closed;

    private Node previousFocus;

    @SuppressWarnings("this-escape")
    CommandPalette(BorderPane host, Supplier<Path> folder, Consumer<Path> openFile,
            Consumer<EditorShortcuts.Command> execute, Predicate<EditorShortcuts.Command> enabled) {
        this.host = host;
        this.folder = folder;
        this.openFile = openFile;
        this.execute = execute;
        this.enabled = enabled;
        setId("command-palette");
        setManaged(false);
        setVisible(false);
        worker.setRemoveOnCancelPolicy(true);
        query.setId("palette-query");
        query.setAccessibleText("Search files or commands; use greater-than for commands");
        query.setPromptText("Search files, or type > for commands");
        results.setId("palette-results");
        results.setAccessibleText("Search results");
        results.setPlaceholder(new Label("No matching results"));
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : entry.label());
            }
        });
        status.setId("palette-status");
        status.setWrapText(true);
        VBox panel = new VBox(8, query, results, status);
        panel.setId("palette-panel");
        panel.setMaxWidth(560);
        panel.setMaxHeight(VBox.USE_PREF_SIZE);
        StackPane.setAlignment(panel, Pos.TOP_CENTER);
        StackPane.setMargin(panel, new Insets(38, 16, 16, 16));
        getChildren().add(panel);
        setOnMouseClicked(event -> {
            if (event.getTarget() == this) {
                hide();
            }
        });
        results.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                activate();
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hide();
            } else if (event.getCode() == KeyCode.ENTER) {
                activate();
            } else if (event.getCode() == KeyCode.TAB) {
                if (query.isFocused()) {
                    results.requestFocus();
                } else {
                    query.requestFocus();
                }
            } else if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
                int delta = event.getCode() == KeyCode.DOWN ? 1 : -1;
                int index = Math.clamp(results.getSelectionModel().getSelectedIndex() + delta,
                        0, Math.max(0, results.getItems().size() - 1));
                results.getSelectionModel().select(index);
                results.scrollTo(index);
            } else {
                return;
            }
            event.consume();
        });
        query.textProperty().addListener((observable, previous, current) -> refresh());
        host.widthProperty().addListener(resizeListener);
        host.heightProperty().addListener(resizeListener);
        host.getChildren().add(this);
    }

    void show(boolean commands) {
        if (closed) {
            return;
        }
        if (!isVisible()) {
            previousFocus = host.getScene().getFocusOwner();
        }
        setVisible(true);
        toFront();
        resizeToHost();
        query.setText(commands ? ">" : "");
        refresh();
        query.requestFocus();
        query.positionCaret(query.getLength());
    }

    void hide() {
        setVisible(false);
        cancelSearch();
        if (previousFocus != null && previousFocus.getScene() == host.getScene()) {
            previousFocus.requestFocus();
        }
        previousFocus = null;
    }

    private void resizeToHost() {
        resizeRelocate(0, 0, host.getWidth(), host.getHeight());
    }

    private void cancelSearch() {
        revision++;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
    }

    private void refresh() {
        cancelSearch();
        if (!isVisible() || closed) {
            return;
        }
        results.getItems().clear();
        String text = query.getText().stripLeading();
        if (text.startsWith(">")) {
            List<Entry> commands = Arrays.stream(EditorShortcuts.Command.values())
                    .filter(command -> command != EditorShortcuts.Command.PALETTE
                            && command != EditorShortcuts.Command.QUICK_OPEN)
                    .filter(enabled)
                    .filter(command -> PaletteFileSearch.matches(command.label(), text.substring(1)))
                    .map(command -> new Entry(command.help(), command, null)).toList();
            results.getItems().setAll(commands);
            results.getSelectionModel().selectFirst();
            status.setText("Commands • Enter to run • Esc to close");
            return;
        }
        Path root = folder.get();
        if (root == null) {
            status.setText("Open a folder to search its files. Type > to search commands.");
            return;
        }
        status.setText("Searching files…");
        long requestedRevision = revision;
        pending = worker.schedule(() -> {
            try {
                var found = PaletteFileSearch.search(root, text);
                Platform.runLater(() -> {
                    if (closed || !isVisible() || revision != requestedRevision || !root.equals(folder.get())) {
                        return;
                    }
                    results.getItems().setAll(found.files().stream()
                            .map(file -> new Entry(root.relativize(file).toString(), null, file)).toList());
                    results.getSelectionModel().selectFirst();
                    status.setText((found.limited() ? "First 200 matches • Narrow your search" : "Files in " + root)
                            + (found.unreadable() == 0 ? "" : " • Some paths could not be read"));
                });
            } catch (CancellationException cancelled) {
                // Another query or closing the palette superseded this search.
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> {
                    if (!closed && isVisible() && revision == requestedRevision) {
                        status.setText("Unable to search this folder: " + failure.getMessage());
                    }
                });
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    private void activate() {
        Entry entry = results.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        if (entry.file() != null && !enabled.test(EditorShortcuts.Command.OPEN_FILE)) {
            status.setText("Please wait for the current file operation to finish.");
            return;
        }
        if (entry.command() != null && !enabled.test(entry.command())) {
            refresh();
            return;
        }
        hide();
        if (entry.file() != null) {
            openFile.accept(entry.file());
        } else {
            execute.accept(entry.command());
        }
    }

    @Override
    public void close() {
        if (!closed) {
            hide();
            closed = true;
            worker.shutdownNow();
            host.widthProperty().removeListener(resizeListener);
            host.heightProperty().removeListener(resizeListener);
            host.getChildren().remove(this);
        }
    }
}
