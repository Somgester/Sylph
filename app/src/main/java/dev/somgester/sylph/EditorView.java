package dev.somgester.sylph;

import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import org.eclipse.lsp4j.Location;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.reactfx.Subscription;

final class EditorView extends StackPane implements AutoCloseable {

    private final EditorSession session;

    private final CodeArea area = new EditorCodeArea();

    private final VirtualizedScrollPane<CodeArea> scrolling;

    private final ObservableBooleanValue darkTheme;

    private final ReadOnlyBooleanWrapper canvasVisible = new ReadOnlyBooleanWrapper();

    private EditorCanvasPreview canvasPreview;

    private SplitPane canvasSplit;

    private final Subscription editorTextChanges;

    private final ChangeListener<String> sessionTextListener;

    private final ReadOnlyBooleanWrapper undoAvailable = new ReadOnlyBooleanWrapper();

    private final ReadOnlyBooleanWrapper redoAvailable = new ReadOnlyBooleanWrapper();

    private final ChangeListener<Boolean> historyListener;

    private final SyntaxHighlighter highlighter;

    private final EditorCompletion completion;

    private EditorLanguageFeatures languageFeatures;

    private Consumer<Location> onDefinition = location -> { };

    private final ReadOnlyBooleanWrapper languageFeaturesAvailable = new ReadOnlyBooleanWrapper();

    private boolean synchronizing;

    private boolean closed;

    EditorView(EditorSession session) {
        this(session, new SimpleBooleanProperty(true));
    }

    @SuppressWarnings("this-escape")
    EditorView(EditorSession session, ObservableBooleanValue darkTheme) {
        this.session = session;
        this.darkTheme = darkTheme;
        setId("editor-view");
        area.setId("editor");
        area.setAccessibleText("Document editor");
        area.setWrapText(false);
        var lineNumbers = LineNumberFactory.get(area, digits -> "%1$" + digits + "s", null, null);
        area.setParagraphGraphicFactory(line -> {
            var number = lineNumbers.apply(line);
            // Supply Modena's fallback lookup before the label inherits the editor's theme.
            number.setStyle("-fx-text-background-color: #666666;");
            return number;
        });
        area.replaceText(session.textProperty().get());
        area.getUndoManager().forgetHistory();
        area.editableProperty().bind(session.editingBlockedProperty().not());
        scrolling = new VirtualizedScrollPane<>(area);
        scrolling.setId("editor-scroll");
        getChildren().add(scrolling);
        editorTextChanges = area.multiPlainChanges().subscribe(changes -> {
            if (!synchronizing) {
                synchronizing = true;
                try {
                    session.applyEdits(changes.stream().map(change -> new EditorSession.TextEdit(
                            change.getPosition(), change.getPosition() + change.getRemoved().length(),
                            change.getInserted())).toList());
                } finally {
                    synchronizing = false;
                }
            }
        });
        sessionTextListener = (observable, previous, text) -> {
            if (!synchronizing && !area.getText().equals(text)) {
                synchronizing = true;
                try {
                    area.replaceText(text);
                } finally {
                    synchronizing = false;
                }
            }
        };
        session.textProperty().addListener(sessionTextListener);
        area.setUndoManager(new EditorUndoManager(area,
                () -> !closed && !session.editingBlockedProperty().get()));
        historyListener = (observable, previous, available) -> updateHistoryAvailability();
        observeHistory();
        highlighter = new SyntaxHighlighter(area, session.languageProperty());
        completion = new EditorCompletion(this, area, session.languageProperty());
    }

    CodeArea area() {
        return area;
    }

    private void toggleCanvasPreview() {
        if (closed) {
            return;
        }
        completion.dismiss();
        if (canvasPreview == null) {
            canvasPreview = new EditorCanvasPreview(session, darkTheme,
                    this::toggleCanvasPreview, this::navigateFromCanvas);
            getChildren().remove(scrolling);
            canvasSplit = new SplitPane(scrolling, canvasPreview);
            canvasSplit.setId("canvas-preview-split");
            canvasSplit.setDividerPositions(0.5);
            getChildren().add(0, canvasSplit);
            canvasVisible.set(true);
        } else {
            canvasSplit.getItems().clear();
            getChildren().remove(canvasSplit);
            getChildren().add(0, scrolling);
            canvasPreview.close();
            canvasPreview = null;
            canvasSplit = null;
            canvasVisible.set(false);
        }
        area.requestFocus();
    }

    private void navigateFromCanvas(EditorCanvas.Position position, boolean extend) {
        if (!closed && !session.editingBlockedProperty().get()) {
            int offset = session.offsetAt(position.line(), position.column());
            completion.dismiss();
            area.getUndoManager().preventMerge();
            area.selectRange(extend ? area.getAnchor() : offset, offset);
            area.requestFollowCaret();
            area.requestFocus();
        }
    }

    void setOnDefinition(Consumer<Location> action) {
        onDefinition = action;
    }

    void attachLanguageServer(LanguageServerConnection connection, LanguageServerDocument binding) {
        if (languageFeatures != null) {
            languageFeatures.close();
        }
        languageFeatures = new EditorLanguageFeatures(this, area, session, connection, binding,
                location -> onDefinition.accept(location), completion::dismiss);
        completion.setLanguageFeatures(languageFeatures);
        languageFeaturesAvailable.set(true);
    }

    void detachLanguageServer() {
        completion.setLanguageFeatures(null);
        if (languageFeatures != null) {
            languageFeatures.close();
            languageFeatures = null;
        }
        languageFeaturesAvailable.set(false);
    }

    void installContextMenu(Runnable onPreview) {
        MenuItem undo = contextItem("Undo", area::undo);
        undo.disableProperty().bind(undoAvailable.not().or(session.editingBlockedProperty()));
        MenuItem redo = contextItem("Redo", area::redo);
        redo.disableProperty().bind(redoAvailable.not().or(session.editingBlockedProperty()));
        var noSelection = Bindings.createBooleanBinding(() -> area.getSelectedText().isEmpty(),
                area.selectedTextProperty());
        MenuItem cut = contextItem("Cut", area::cut);
        cut.disableProperty().bind(noSelection.or(session.editingBlockedProperty()));
        MenuItem copy = contextItem("Copy", area::copy);
        copy.disableProperty().bind(noSelection);
        MenuItem paste = contextItem("Paste", area::paste);
        paste.disableProperty().bind(session.editingBlockedProperty());
        MenuItem preview = contextItem("Preview", onPreview);
        preview.setId("preview-markdown");
        preview.setAccelerator(EditorShortcuts.Command.PREVIEW.shortcut());
        preview.disableProperty().bind(session.languageProperty().isNotEqualTo(EditorLanguage.MARKDOWN)
                .or(session.busyProperty()));
        MenuItem canvas = contextItem("Show Canvas Preview", this::toggleCanvasPreview);
        canvas.setId("toggle-canvas-preview");
        canvas.textProperty().bind(Bindings.when(canvasVisible)
                .then("Hide Canvas Preview").otherwise("Show Canvas Preview"));
        MenuItem hover = contextItem("Show Hover", () -> languageAction(
                features -> features.hover(area.getCaretPosition(), true)));
        hover.setAccelerator(new KeyCodeCombination(KeyCode.SPACE,
                KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        MenuItem definition = contextItem("Go to Definition", () -> languageAction(EditorLanguageFeatures::definition));
        definition.setAccelerator(new KeyCodeCombination(KeyCode.F12));
        MenuItem problems = contextItem("Show Problems", () -> languageAction(EditorLanguageFeatures::showProblems));
        problems.setAccelerator(new KeyCodeCombination(KeyCode.M,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        for (MenuItem item : java.util.List.of(hover, definition, problems)) {
            item.disableProperty().bind(languageFeaturesAvailable.not().or(session.editingBlockedProperty()));
        }
        area.setContextMenu(new ContextMenu(undo, redo, new SeparatorMenuItem(), cut, copy, paste,
                contextItem("Select All", area::selectAll), new SeparatorMenuItem(), preview, canvas,
                new SeparatorMenuItem(), hover, definition, problems));
    }

    private void languageAction(Consumer<EditorLanguageFeatures> action) {
        // Let the context menu close before restoring editor focus and starting its request.
        javafx.application.Platform.runLater(() -> {
            if (!closed && languageFeatures != null) {
                area.requestFocus();
                action.accept(languageFeatures);
            }
        });
    }

    private static MenuItem contextItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    ReadOnlyBooleanProperty undoAvailableProperty() {
        return undoAvailable.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty redoAvailableProperty() {
        return redoAvailable.getReadOnlyProperty();
    }

    void documentOpened() {
        // forgetHistory() clears only undo; a new manager also drops the previous document's redo.
        stopObservingHistory();
        area.setUndoManager(new EditorUndoManager(area,
                () -> !closed && !session.editingBlockedProperty().get()));
        observeHistory();
        area.moveTo(0);
        area.requestFollowCaret();
    }

    private void observeHistory() {
        area.undoAvailableProperty().addListener(historyListener);
        area.redoAvailableProperty().addListener(historyListener);
        updateHistoryAvailability();
    }

    private void stopObservingHistory() {
        area.undoAvailableProperty().removeListener(historyListener);
        area.redoAvailableProperty().removeListener(historyListener);
    }

    private void updateHistoryAvailability() {
        undoAvailable.set(area.isUndoAvailable());
        redoAvailable.set(area.isRedoAvailable());
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (canvasPreview != null) {
                canvasPreview.close();
                canvasPreview = null;
            }
            if (languageFeatures != null) {
                languageFeatures.close();
            }
            completion.close();
            highlighter.close();
            if (area.getContextMenu() != null) {
                area.getContextMenu().hide();
            }
            editorTextChanges.unsubscribe();
            session.textProperty().removeListener(sessionTextListener);
            stopObservingHistory();
            area.editableProperty().unbind();
            area.dispose();
        }
    }
}
