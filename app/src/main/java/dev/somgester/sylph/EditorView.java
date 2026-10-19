package dev.somgester.sylph;

import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import org.eclipse.lsp4j.Location;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.util.UndoUtils;

final class EditorView extends StackPane implements AutoCloseable {

    private final EditorSession session;

    private final CodeArea area = new CodeArea();

    private final ChangeListener<String> editorTextListener;

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

    @SuppressWarnings("this-escape")
    EditorView(EditorSession session) {
        this.session = session;
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
        VirtualizedScrollPane<CodeArea> scrolling = new VirtualizedScrollPane<>(area);
        scrolling.setId("editor-scroll");
        getChildren().add(scrolling);
        editorTextListener = (observable, previous, text) -> {
            if (!synchronizing) {
                session.textProperty().set(text);
            }
        };
        sessionTextListener = (observable, previous, text) -> {
            if (!area.getText().equals(text)) {
                synchronizing = true;
                try {
                    area.replaceText(text);
                } finally {
                    synchronizing = false;
                }
            }
        };
        area.textProperty().addListener(editorTextListener);
        session.textProperty().addListener(sessionTextListener);
        historyListener = (observable, previous, available) -> updateHistoryAvailability();
        observeHistory();
        highlighter = new SyntaxHighlighter(area, session.languageProperty());
        completion = new EditorCompletion(this, area, session.languageProperty());
    }

    CodeArea area() {
        return area;
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
                contextItem("Select All", area::selectAll), new SeparatorMenuItem(), preview,
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
        area.setUndoManager(UndoUtils.plainTextUndoManager(area));
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
            if (languageFeatures != null) {
                languageFeatures.close();
            }
            completion.close();
            highlighter.close();
            if (area.getContextMenu() != null) {
                area.getContextMenu().hide();
            }
            area.textProperty().removeListener(editorTextListener);
            session.textProperty().removeListener(sessionTextListener);
            stopObservingHistory();
            area.editableProperty().unbind();
            area.dispose();
        }
    }
}
