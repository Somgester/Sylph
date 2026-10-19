package dev.somgester.sylph;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.event.MouseOverTextEvent;

/** Editor-side language features. UI changes always validate the document snapshot on the JavaFX thread. */
final class EditorLanguageFeatures implements AutoCloseable {

    private record Snapshot(Path path, String text, int caret, long revision) { }

    private record Problem(int line, DiagnosticSeverity severity, String message) { }

    private final EditorSession session;

    private final CodeArea area;

    private final StackPane host;

    private final LanguageServerConnection connection;

    private final LanguageServerDocument binding;

    private final Consumer<Location> navigate;

    private final Runnable dismissCompletion;

    private final VBox info = new VBox(6);

    private final PauseTransition hoverExitDelay = new PauseTransition(javafx.util.Duration.millis(150));

    private final Label title = new Label();

    private final Label body = new Label();

    private final ScrollPane scrolling = new ScrollPane(body);

    private final ListView<Location> definitions = new ListView<>();

    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();

    private final IntFunction<? extends Node> originalGutter;

    private final Duration originalHoverDelay;

    private final Runnable unsubscribeDiagnostics;

    private final ChangeListener<String> textChanged = (observable, before, after) -> documentChanged();

    private final ChangeListener<Path> pathChanged = (observable, before, after) -> documentChanged();

    private final InvalidationListener moved = observable -> dismiss();

    private final InvalidationListener focusChanged = observable -> {
        if (!eligible()) {
            dismiss();
        }
    };

    private final EventHandler<KeyEvent> keys = this::keyPressed;

    private final EventHandler<MouseOverTextEvent> mouseHover = event -> hover(event.getCharacterIndex(), false);

    private final EventHandler<MouseOverTextEvent> mouseExit = event -> {
        if (this.mouseHoverActive) {
            hoverExitDelay.playFromStart();
        }
    };

    private List<Problem> problems = List.of();

    private volatile long revision;

    private volatile long documentRevision;

    private boolean closed;

    private boolean mouseHoverActive;

    @SuppressWarnings("this-escape")
    EditorLanguageFeatures(StackPane host, CodeArea area, EditorSession session, LanguageServerConnection connection,
            LanguageServerDocument binding, Consumer<Location> navigate, Runnable dismissCompletion) {
        this.host = host;
        this.area = area;
        this.session = session;
        this.connection = connection;
        this.binding = binding;
        this.navigate = navigate;
        this.dismissCompletion = dismissCompletion;
        originalGutter = area.getParagraphGraphicFactory();
        originalHoverDelay = area.getMouseOverTextDelay();
        refreshGutter();
        info.setId("language-info");
        info.setMaxWidth(Region.USE_PREF_SIZE);
        info.setMaxHeight(Region.USE_PREF_SIZE);
        info.setMinSize(0, 0);
        hoverExitDelay.setOnFinished(event -> {
            if (!info.isHover()) {
                dismiss();
            }
        });
        info.setOnMouseEntered(event -> hoverExitDelay.stop());
        info.setOnMouseExited(event -> {
            if (mouseHoverActive) {
                hoverExitDelay.playFromStart();
            }
        });
        info.setVisible(false);
        info.managedProperty().bind(info.visibleProperty());
        StackPane.setAlignment(info, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(info, new Insets(12));
        var close = new Button("×");
        close.setId("close-language-info");
        close.setAccessibleText("Close language information");
        close.setFocusTraversable(false);
        close.setOnAction(event -> {
            dismiss();
            area.requestFocus();
        });
        var header = new HBox(8, title, close);
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.setId("language-info-text");
        scrolling.setFitToWidth(true);
        scrolling.setFocusTraversable(false);
        scrolling.setPrefViewportHeight(140);
        definitions.setId("definition-list");
        definitions.setPrefHeight(150);
        definitions.setFocusTraversable(false);
        definitions.setVisible(false);
        definitions.setManaged(false);
        definitions.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Location value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.getUri() + ":"
                        + (value.getRange().getStart().getLine() + 1));
            }
        });
        definitions.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                for (Node node = event.getPickResult().getIntersectedNode(); node != null; node = node.getParent()) {
                    if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                        definitions.getSelectionModel().select(cell.getIndex());
                        acceptDefinition();
                        event.consume();
                        break;
                    }
                }
            }
        });
        info.getChildren().addAll(header, scrolling, definitions);
        host.getChildren().add(info);
        host.addEventFilter(KeyEvent.KEY_PRESSED, keys);
        area.setMouseOverTextDelay(Duration.ofMillis(500));
        area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, mouseHover);
        area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, mouseExit);
        area.caretPositionProperty().addListener(moved);
        area.focusedProperty().addListener(focusChanged);
        area.editableProperty().addListener(focusChanged);
        session.textProperty().addListener(textChanged);
        session.pathProperty().addListener(pathChanged);
        unsubscribeDiagnostics = connection.onDiagnostics(update -> {
            long expected = documentRevision;
            Platform.runLater(() -> diagnostics(update, expected));
        });
        // A shared server can outlive many tabs; its future must not retain closed editors.
        var reference = new WeakReference<>(this);
        connection.stopped().thenRun(() -> Platform.runLater(() -> {
            var features = reference.get();
            if (features != null && !features.closed) {
                features.documentChanged();
            }
        }));
    }

    private boolean eligible() {
        if (closed || !area.isEditable() || !area.isFocused() || session.pathProperty().get() == null) {
            return false;
        }
        for (Node node = area; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private Snapshot snapshot() {
        return new Snapshot(session.pathProperty().get(), session.textProperty().get(),
                area.getCaretPosition(), revision);
    }

    private boolean current(Snapshot snapshot) {
        return eligible() && snapshot.revision() == revision && snapshot.path().equals(session.pathProperty().get())
                && snapshot.text().equals(session.textProperty().get()) && snapshot.caret() == area.getCaretPosition();
    }

    private ServerCapabilities capabilities() {
        return connection.ready().getNow(null);
    }

    private <T> CompletableFuture<T> request(Function<LanguageServer, CompletableFuture<T>> action) {
        binding.flush();
        var result = connection.request(session.pathProperty().get(), action).orTimeout(3, TimeUnit.SECONDS);
        requests.add(result);
        result.whenComplete((value, error) -> requests.remove(result));
        return result;
    }

    private static <T, R> CompletableFuture<R> map(CompletableFuture<T> source, Function<T, R> transform) {
        var result = source.thenApplyAsync(transform);
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                source.cancel(true);
            }
        });
        return result;
    }

    CompletableFuture<List<CompletionEdit>> complete(String text, int caret, boolean manual,
            LanguageCompletions.Result local) {
        if (!eligible() || !text.equals(session.textProperty().get())) {
            return CompletableFuture.completedFuture(List.of());
        }
        var identifier = new TextDocumentIdentifier(session.pathProperty().get().toUri().toString());
        var coordinates = new LspText(text);
        if (!coordinates.boundary(caret)) {
            return CompletableFuture.completedFuture(List.of());
        }
        var position = coordinates.position(caret);
        var response = request(server -> {
            var options = capabilities().getCompletionProvider();
            if (options == null) {
                return CompletableFuture.completedFuture(null);
            }
            String trigger = caret == 0 ? "" : text.substring(caret - 1, caret);
            boolean triggered = options.getTriggerCharacters() != null
                    && options.getTriggerCharacters().contains(trigger);
            int prefix = caret - local.start();
            if (!manual && !triggered && prefix < 2) {
                return CompletableFuture.completedFuture(null);
            }
            var params = new CompletionParams(identifier, position);
            params.setContext(triggered && !manual
                    ? new CompletionContext(CompletionTriggerKind.TriggerCharacter, trigger)
                    : new CompletionContext(CompletionTriggerKind.Invoked));
            return server.getTextDocumentService().completion(params);
        });
        return map(response, value -> value == null ? List.of()
                : ServerCompletions.convert(text, caret, local,
                        value.isLeft() ? value.getLeft() : value.getRight().getItems()));
    }

    void hover(int offset, boolean manual) {
        if (!eligible() || offset < 0 || offset > area.getLength()) {
            return;
        }
        dismiss();
        mouseHoverActive = !manual;
        var snapshot = snapshot();
        var coordinates = new LspText(snapshot.text());
        if (!coordinates.boundary(offset)) {
            return;
        }
        var params = new HoverParams(new TextDocumentIdentifier(snapshot.path().toUri().toString()),
                coordinates.position(offset));
        var response = request(server -> enabled(capabilities().getHoverProvider())
                ? server.getTextDocumentService().hover(params) : CompletableFuture.completedFuture(null));
        response.whenComplete((value, error) -> Platform.runLater(() -> {
            if (current(snapshot)) {
                String message = error == null ? hoverText(value) : manual ? "Unable to get hover information." : "";
                if (!message.isBlank()) {
                    showInfo("Hover", message);
                }
            }
        }));
    }

    void definition() {
        if (!eligible()) {
            return;
        }
        dismiss();
        dismissCompletion.run();
        var snapshot = snapshot();
        var coordinates = new LspText(snapshot.text());
        if (!coordinates.boundary(snapshot.caret())) {
            return;
        }
        var params = new DefinitionParams(new TextDocumentIdentifier(snapshot.path().toUri().toString()),
                coordinates.position(snapshot.caret()));
        var response = request(server -> enabled(capabilities().getDefinitionProvider())
                ? server.getTextDocumentService().definition(params) : CompletableFuture.completedFuture(null));
        response.whenComplete((value, error) -> Platform.runLater(() -> {
            if (!current(snapshot)) {
                return;
            }
            if (error != null || value == null) {
                showInfo("Definition", error == null ? "No definition found." : "Unable to get definition.");
                return;
            }
            List<? extends Location> locations;
            if (value.isLeft()) {
                locations = value.getLeft() == null ? List.of() : value.getLeft();
            } else {
                locations = value.getRight() == null ? List.of() : value.getRight().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(link -> new Location(link.getTargetUri(), link.getTargetSelectionRange())).toList();
            }
            var valid = locations.stream().filter(EditorLanguageFeatures::fileLocation).limit(100).toList();
            if (valid.size() == 1) {
                goTo(valid.getFirst());
            } else if (valid.isEmpty()) {
                showInfo("Definition", "No definition found.");
            } else {
                showInfo("Choose definition", "");
                scrolling.setVisible(false);
                scrolling.setManaged(false);
                definitions.setVisible(true);
                definitions.setManaged(true);
                info.setPrefWidth(460);
                definitions.getItems().setAll(valid);
                definitions.getSelectionModel().selectFirst();
            }
        }));
    }

    private void acceptDefinition() {
        var location = definitions.getSelectionModel().getSelectedItem();
        if (location != null) {
            goTo(location);
        }
    }

    private void goTo(Location location) {
        dismiss();
        if (location.getUri().equals(session.pathProperty().get().toUri().toString())) {
            var coordinates = new LspText(area.getText());
            if (coordinates.valid(location.getRange())) {
                area.selectRange(coordinates.offset(location.getRange().getStart()),
                        coordinates.offset(location.getRange().getEnd()));
                area.requestFollowCaret();
            }
        } else {
            navigate.accept(location);
        }
    }

    private static boolean fileLocation(Location location) {
        try {
            if (location == null || location.getUri() == null || location.getRange() == null
                    || location.getRange().getStart() == null
                    || location.getRange().getEnd() == null) {
                return false;
            }
            var start = location.getRange().getStart();
            var end = location.getRange().getEnd();
            var uri = java.net.URI.create(location.getUri());
            return "file".equalsIgnoreCase(uri.getScheme()) && Path.of(uri).isAbsolute()
                    && start.getLine() >= 0 && start.getCharacter() >= 0 && end.getCharacter() >= 0
                    && (end.getLine() > start.getLine() || end.getLine() == start.getLine()
                    && end.getCharacter() >= start.getCharacter());
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException error) {
            return false;
        }
    }

    private static boolean enabled(Either<Boolean, ?> option) {
        return option != null && (option.isRight() || Boolean.TRUE.equals(option.getLeft()));
    }

    @SuppressWarnings("deprecation") // Hover's legacy MarkedString representation is still valid LSP.
    private static String hoverText(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return "";
        }
        var contents = hover.getContents();
        if (contents.isRight()) {
            return java.util.Objects.toString(contents.getRight().getValue(), "");
        }
        return String.join("\n\n", contents.getLeft().stream()
                .map(value -> value.isLeft() ? value.getLeft() : value.getRight().getValue()).toList());
    }

    private void diagnostics(LanguageServerConnection.Diagnostics update, long expected) {
        Path path = session.pathProperty().get();
        if (closed || documentRevision != expected || path == null || !path.toUri().toString().equals(update.uri())
                || !session.textProperty().get().equals(update.text())) {
            return;
        }
        var coordinates = new LspText(update.text());
        var received = new ArrayList<Problem>();
        for (var item : update.items()) {
            if (item != null && coordinates.valid(item.getRange()) && item.getMessage() != null) {
                received.add(new Problem(item.getRange().getStart().getLine(), item.getSeverity(), item.getMessage()));
            }
            if (received.size() >= 1000) {
                break;
            }
        }
        if (!problems.equals(received)) {
            problems = List.copyOf(received);
            refreshGutter();
        }
    }

    private void refreshGutter() {
        area.setParagraphGraphicFactory(line -> {
            var row = new HBox();
            var marker = new Label(" ");
            marker.getStyleClass().add("diagnostic-marker");
            marker.setMinWidth(14);
            marker.setAlignment(Pos.CENTER);
            var messages = problems.stream().filter(problem -> problem.line() == line).toList();
            if (!messages.isEmpty()) {
                var severity = messages.stream().mapToInt(problem -> problem.severity() == null
                        ? 3 : problem.severity().getValue()).min().orElse(3);
                marker.setText("●");
                marker.getStyleClass().add(severity == 1 ? "diagnostic-error" : "diagnostic-warning");
                marker.setAccessibleText(problemText(messages));
                marker.setOnMouseClicked(event -> showProblems(messages));
            }
            row.getChildren().addAll(marker, originalGutter.apply(line));
            return row;
        });
    }

    void showProblems() {
        showProblems(problems);
    }

    private void showProblems(List<Problem> items) {
        dismiss();
        dismissCompletion.run();
        showInfo("Problems", items.isEmpty() ? "No problems reported." : problemText(items));
    }

    private static String problemText(List<Problem> problems) {
        return String.join("\n\n", problems.stream().map(problem -> "Line " + (problem.line() + 1) + ": "
                + problem.message()).toList());
    }

    private void showInfo(String heading, String message) {
        title.setText(heading);
        body.setText(message);
        body.applyCss();
        info.setPrefWidth(Math.clamp(body.prefWidth(-1) + 32, 240, 460));
        scrolling.setPrefViewportHeight(Math.clamp(body.prefHeight(info.getPrefWidth() - 24), 36, 150));
        definitions.setVisible(false);
        definitions.setManaged(false);
        scrolling.setVisible(true);
        scrolling.setManaged(true);
        info.setVisible(true);
        info.toFront();
    }

    private void keyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE && info.isVisible()) {
            dismiss();
            event.consume();
        } else if (event.getCode() == KeyCode.F12 && !event.isAltDown() && !event.isControlDown()
                && !event.isMetaDown() && !event.isShiftDown()) {
            definition();
            event.consume();
        } else if (event.getCode() == KeyCode.SPACE && event.isControlDown() && event.isShiftDown()
                && !event.isAltDown() && !event.isMetaDown()) {
            dismissCompletion.run();
            hover(area.getCaretPosition(), true);
            event.consume();
        } else if (event.getCode() == KeyCode.M && event.isShortcutDown() && event.isShiftDown()) {
            showProblems();
            event.consume();
        } else if (info.isVisible() && definitions.isVisible() && !event.isControlDown() && !event.isMetaDown()
                && !event.isAltDown() && !event.isShiftDown()) {
            if (event.getCode() == KeyCode.ENTER) {
                acceptDefinition();
            } else if (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN) {
                int delta = event.getCode() == KeyCode.UP ? -1 : 1;
                definitions.getSelectionModel().select(Math.clamp(definitions.getSelectionModel().getSelectedIndex()
                        + delta, 0, definitions.getItems().size() - 1));
            } else {
                return;
            }
            event.consume();
        }
    }

    private void documentChanged() {
        documentRevision++;
        dismiss();
        if (!problems.isEmpty()) {
            problems = List.of();
            refreshGutter();
        }
    }

    private void dismiss() {
        hoverExitDelay.stop();
        revision++;
        info.setVisible(false);
        definitions.setVisible(false);
        definitions.setManaged(false);
        definitions.getItems().clear();
        mouseHoverActive = false;
        requests.forEach(request -> request.cancel(true));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        dismiss();
        unsubscribeDiagnostics.run();
        session.textProperty().removeListener(textChanged);
        session.pathProperty().removeListener(pathChanged);
        area.caretPositionProperty().removeListener(moved);
        area.focusedProperty().removeListener(focusChanged);
        area.editableProperty().removeListener(focusChanged);
        area.removeEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, mouseHover);
        area.removeEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, mouseExit);
        area.setMouseOverTextDelay(originalHoverDelay);
        host.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
        area.setParagraphGraphicFactory(originalGutter);
        info.managedProperty().unbind();
        host.getChildren().remove(info);
    }
}
