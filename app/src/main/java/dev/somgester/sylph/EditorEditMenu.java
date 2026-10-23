package dev.somgester.sylph;

import java.util.EnumMap;
import java.util.Map;
import javafx.beans.value.ChangeListener;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

final class EditorEditMenu extends Menu {

    private enum Action {
        UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL
    }

    private EditorView editor;

    private EditorSession session;

    private final Map<Action, MenuItem> items = new EnumMap<>(Action.class);

    private final ChangeListener<Node> focusListener = (observable, previous, current) -> focusChanged(current);

    private final InvalidationListener stateListener = observable -> refresh();

    private Scene scene;

    private Node target;

    @SuppressWarnings("this-escape")
    EditorEditMenu(EditorView editor, EditorSession session) {
        super("Edit");
        getItems().addAll(item(Action.UNDO, "Undo", KeyCode.Z),
                item(Action.REDO, "Redo", KeyCode.Z, KeyCombination.SHIFT_DOWN), new SeparatorMenuItem(),
                item(Action.CUT, "Cut", KeyCode.X), item(Action.COPY, "Copy", KeyCode.C),
                item(Action.PASTE, "Paste", KeyCode.V), item(Action.SELECT_ALL, "Select All", KeyCode.A));
        setOnShowing(event -> refresh());
        setDocument(editor, session);
    }

    void setDocument(EditorView current, EditorSession currentSession) {
        if (editor != null) {
            editor.undoAvailableProperty().removeListener(stateListener);
            editor.redoAvailableProperty().removeListener(stateListener);
            editor.area().selectedTextProperty().removeListener(stateListener);
            session.editingBlockedProperty().removeListener(stateListener);
        }
        editor = current;
        session = currentSession;
        target = editor == null ? null : editor.area();
        if (editor != null) {
            editor.undoAvailableProperty().addListener(stateListener);
            editor.redoAvailableProperty().addListener(stateListener);
            editor.area().selectedTextProperty().addListener(stateListener);
            session.editingBlockedProperty().addListener(stateListener);
        }
        if (editor == null && scene != null) {
            focusChanged(scene.getFocusOwner());
        }
        refresh();
    }

    void setScene(Scene current) {
        if (scene != null) {
            scene.focusOwnerProperty().removeListener(focusListener);
        }
        scene = current;
        if (scene != null) {
            scene.focusOwnerProperty().addListener(focusListener);
            focusChanged(scene.getFocusOwner());
        }
    }

    private void focusChanged(Node owner) {
        if (owner == null) {
            return;
        }
        for (Node node = owner; node != null; node = node.getParent()) {
            // Chrome controls must retain the editing view they operate on.
            if (node instanceof MenuBar || node instanceof EditorChrome) {
                return;
            }
            if (node instanceof TerminalView || node instanceof TextInputControl
                    || editor != null && node == editor.area()) {
                target = node;
                refresh();
                return;
            }
        }
        target = null;
        refresh();
    }

    private boolean enabled(Action action) {
        if (target instanceof TerminalView terminal) {
            return switch (action) {
                case COPY -> terminal.hasSelection();
                case PASTE -> terminal.canPaste();
                case SELECT_ALL -> terminal.canSelectAll();
                default -> false;
            };
        }
        if (editor != null && target == editor.area()) {
            boolean editable = !session.editingBlockedProperty().get();
            boolean selected = !editor.area().getSelectedText().isEmpty();
            return switch (action) {
                case UNDO -> editable && editor.undoAvailableProperty().get();
                case REDO -> editable && editor.redoAvailableProperty().get();
                case CUT -> editable && selected;
                case COPY -> selected;
                case PASTE -> editable;
                case SELECT_ALL -> true;
            };
        }
        if (target instanceof TextInputControl input) {
            boolean editable = input.isEditable() && !input.isDisabled();
            boolean selected = !input.getSelectedText().isEmpty();
            return switch (action) {
                case UNDO -> editable && input.isUndoable();
                case REDO -> editable && input.isRedoable();
                case CUT -> editable && selected;
                case COPY -> selected;
                case PASTE -> editable;
                case SELECT_ALL -> !input.isDisabled();
            };
        }
        return false;
    }

    private void execute(Action action) {
        // Recheck on activation as focus/state can change while a menu is open.
        if (!enabled(action)) {
            return;
        }
        if (target instanceof TerminalView terminal) {
            switch (action) {
                case COPY -> terminal.copySelection();
                case PASTE -> terminal.pasteClipboard();
                case SELECT_ALL -> terminal.selectAll();
                default -> { }
            }
        } else if (editor != null && target == editor.area()) {
            switch (action) {
                case UNDO -> editor.area().undo();
                case REDO -> editor.area().redo();
                case CUT -> editor.area().cut();
                case COPY -> editor.area().copy();
                case PASTE -> editor.area().paste();
                case SELECT_ALL -> editor.area().selectAll();
            }
        } else if (target instanceof TextInputControl input) {
            switch (action) {
                case UNDO -> input.undo();
                case REDO -> input.redo();
                case CUT -> input.cut();
                case COPY -> input.copy();
                case PASTE -> input.paste();
                case SELECT_ALL -> input.selectAll();
            }
        }
        refresh();
    }

    private void refresh() {
        items.forEach((action, item) -> item.setDisable(!enabled(action)));
    }

    private MenuItem item(Action action, String label, KeyCode key, KeyCombination.Modifier... modifiers) {
        MenuItem item = new MenuItem(label);
        KeyCombination.Modifier[] all = new KeyCombination.Modifier[modifiers.length + 1];
        all[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(modifiers, 0, all, 1, modifiers.length);
        item.setAccelerator(new KeyCodeCombination(key, all));
        item.setOnAction(event -> execute(action));
        items.put(action, item);
        return item;
    }
}
