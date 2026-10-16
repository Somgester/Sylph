package dev.somgester.sylph;

import java.util.function.Consumer;
import java.util.function.Predicate;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

final class EditorShortcuts {

    enum Command {
        PALETTE("Command Palette", KeyCode.P, KeyCombination.SHIFT_DOWN),
        QUICK_OPEN("Search Files", KeyCode.P),
        RELOAD("Reload Window", KeyCode.R, KeyCombination.ALT_DOWN),
        OPEN_FILE("Open File", KeyCode.O),
        OPEN_FOLDER("Open Folder", KeyCode.O, KeyCombination.SHIFT_DOWN),
        SAVE("Save", KeyCode.S),
        SAVE_AS("Save As", KeyCode.S, KeyCombination.SHIFT_DOWN),
        SIDEBAR("Toggle Sidebar", KeyCode.B),
        SETTINGS("Settings", KeyCode.COMMA),
        PREVIEW("Preview", KeyCode.V, KeyCombination.SHIFT_DOWN),
        TERMINAL_PANEL("Toggle Terminal Panel", KeyCode.BACK_QUOTE, KeyCombination.SHIFT_DOWN),
        TERMINAL_EDITOR("New Terminal in Editor", KeyCode.BACK_QUOTE),
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
        install(scene, action, command -> true);
    }

    static void install(Scene scene, Consumer<Command> action, Predicate<Command> enabled) {
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
                if (command.shortcut().match(event) && enabled.test(command)) {
                    action.accept(chord && command == Command.OPEN_FILE ? Command.OPEN_FOLDER : command);
                    event.consume();
                    return;
                }
            }
        });
    }
}
