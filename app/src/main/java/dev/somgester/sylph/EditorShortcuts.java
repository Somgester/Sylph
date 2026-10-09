package dev.somgester.sylph;

import java.util.function.Consumer;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

final class EditorShortcuts {

    enum Command {
        OPEN_FILE("Open File", KeyCode.O),
        OPEN_FOLDER("Open Folder", KeyCode.O, KeyCombination.SHIFT_DOWN),
        SAVE("Save", KeyCode.S),
        SAVE_AS("Save As", KeyCode.S, KeyCombination.SHIFT_DOWN),
        SIDEBAR("Toggle Sidebar", KeyCode.B),
        SETTINGS("Settings", KeyCode.COMMA),
        AUTOSAVE("Toggle Autosave", KeyCode.S, KeyCombination.ALT_DOWN);

        private final String label;

        private final KeyCombination shortcut;

        Command(String label, KeyCode key, KeyCombination.Modifier... modifiers) {
            this.label = label;
            KeyCombination.Modifier[] allModifiers = new KeyCombination.Modifier[modifiers.length + 1];
            allModifiers[0] = KeyCombination.SHORTCUT_DOWN;
            System.arraycopy(modifiers, 0, allModifiers, 1, modifiers.length);
            shortcut = new KeyCodeCombination(key, allModifiers);
        }

        String label() {
            return label;
        }

        KeyCombination shortcut() {
            return shortcut;
        }

        String help() {
            return label + " (" + shortcut.getDisplayText() + ")";
        }
    }

    private EditorShortcuts() { }

    static void install(Scene scene, Consumer<Command> action) {
        KeyCombination chordStart = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
        long[] armedAt = {0L};
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            long now = System.nanoTime();
            if (chordStart.match(event)) {
                armedAt[0] = now;
                event.consume();
                return;
            }
            boolean chord = armedAt[0] != 0L && now - armedAt[0] <= 1_500_000_000L;
            if (!event.getCode().isModifierKey()) {
                armedAt[0] = 0L;
            }
            for (Command command : Command.values()) {
                if (command.shortcut().match(event)) {
                    action.accept(chord && command == Command.OPEN_FILE ? Command.OPEN_FOLDER : command);
                    event.consume();
                    return;
                }
            }
        });
    }
}
