package dev.somgester.sylph;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import javafx.beans.binding.Bindings;
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
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

// An in-scene overlay: no extra window, and theme values inherit from the editor.
final class CommandPalette extends StackPane implements AutoCloseable {

    private record Entry(String label, EditorShortcuts.Command command, Path file, String query) { }

    private final BorderPane host;

    private final Supplier<Path> folder;

    private final Supplier<List<Path>> recentFiles;

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

    private long displayedRevision = -1;

    private boolean closed;

    private Node previousFocus;

    @SuppressWarnings("this-escape")
    CommandPalette(BorderPane host, Supplier<Path> folder, Supplier<List<Path>> recentFiles, Consumer<Path> openFile,
            Consumer<EditorShortcuts.Command> execute, Predicate<EditorShortcuts.Command> enabled) {
        this.host = host;
        this.folder = folder;
        this.recentFiles = recentFiles;
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
                setText(null);
                setGraphic(null);
                setAccessibleText(null);
                if (!empty && entry != null) {
                    TextFlow label = highlightedLabel(entry.label(), entry.query());
                    label.prefWidthProperty().bind(Bindings.max(0, widthProperty().subtract(20)));
                    setGraphic(label);
                    setAccessibleText(entry.label());
                }
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
            results.getItems().clear();
        }
        setVisible(true);
        toFront();
        resizeToHost();
        String initialQuery = commands ? ">" : "";
        if (query.getText().equals(initialQuery)) {
            refresh();
        } else {
            query.setText(initialQuery);
        }
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
        String text = query.getText().stripLeading();
        if (text.startsWith(">")) {
            List<Entry> commands = Arrays.stream(EditorShortcuts.Command.values())
                    .filter(command -> command != EditorShortcuts.Command.PALETTE
                            && command != EditorShortcuts.Command.QUICK_OPEN)
                    .filter(enabled)
                    .filter(command -> PaletteFileSearch.matches(command.label(), text.substring(1)))
                    .map(command -> new Entry(command.help(), command, null, text.substring(1))).toList();
            publish(commands);
            status.setText("Commands • Enter to run • Esc to close");
            return;
        }
        Path root = folder.get();
        List<Path> history = List.copyOf(recentFiles.get());
        if (root == null && history.isEmpty()) {
            publish(List.of());
            status.setText("Open a folder to search its files. Type > to search commands.");
            return;
        }
        status.setText("Searching files…");
        long requestedRevision = revision;
        pending = worker.schedule(() -> {
            try {
                List<Path> recent = new ArrayList<>();
                for (Path file : history) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("Recent search superseded");
                    }
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            && PaletteFileSearch.matches(fileLabel(root, file), text)) {
                        recent.add(file);
                    }
                }
                boolean recentOnly = text.isBlank() && !recent.isEmpty();
                var found = root == null || recentOnly ? new PaletteFileSearch.Result(List.of(), false, 0)
                        : PaletteFileSearch.search(root, text);
                var paths = new LinkedHashSet<>(recent);
                paths.addAll(found.files());
                boolean limited = found.limited() || paths.size() > 200;
                List<Entry> entries = paths.stream().limit(200)
                        .map(file -> new Entry(fileLabel(root, file), null, file, text)).toList();
                Platform.runLater(() -> {
                    if (closed || !isVisible() || revision != requestedRevision
                            || !java.util.Objects.equals(root, folder.get())) {
                        return;
                    }
                    publish(entries);
                    String message = recentOnly || root == null ? "Recent files • Enter to open"
                            : limited ? "First 200 matches • Narrow your search" : "Files in " + root;
                    status.setText(message + (found.unreadable() == 0 ? "" : " • Some paths could not be read"));
                });
            } catch (CancellationException cancelled) {
                // Another query or closing the palette superseded this search.
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> {
                    if (!closed && isVisible() && revision == requestedRevision) {
                        publish(List.of());
                        status.setText("Unable to search files: " + failure.getMessage());
                    }
                });
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    private void publish(List<Entry> entries) {
        Entry selected = results.getSelectionModel().getSelectedItem();
        // Replace in one change: keep existing rows until their successor is ready.
        results.getItems().setAll(entries);
        int selectedIndex = -1;
        if (selected != null) {
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                if (java.util.Objects.equals(entry.file(), selected.file()) && entry.command() == selected.command()) {
                    selectedIndex = index;
                    break;
                }
            }
        }
        if (selectedIndex >= 0) {
            results.getSelectionModel().select(selectedIndex);
        } else {
            results.getSelectionModel().selectFirst();
        }
        displayedRevision = revision;
    }

    private static String fileLabel(Path root, Path file) {
        return root != null && file.startsWith(root) ? root.relativize(file).toString() : file.toString();
    }

    private static TextFlow highlightedLabel(String label, String query) {
        TextFlow flow = new TextFlow();
        int cursor = 0;
        for (var range : PaletteMatch.ranges(label, query).orElse(List.of())) {
            if (range.start() > cursor) {
                flow.getChildren().add(labelText(label.substring(cursor, range.start()), false));
            }
            flow.getChildren().add(labelText(label.substring(range.start(), range.end()), true));
            cursor = range.end();
        }
        if (cursor < label.length()) {
            flow.getChildren().add(labelText(label.substring(cursor), false));
        }
        return flow;
    }

    private static Text labelText(String value, boolean matched) {
        Text text = new Text(value);
        text.getStyleClass().add("palette-result-text");
        if (matched) {
            text.getStyleClass().add("palette-match");
        }
        return text;
    }

    private void activate() {
        if (displayedRevision != revision) {
            return;
        }
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
