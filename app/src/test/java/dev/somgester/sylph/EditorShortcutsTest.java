package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EditorShortcutsTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @ParameterizedTest
    @EnumSource(EditorShortcuts.Command.class)
    void eachShortcutRunsExactlyOneMatchingCommand(EditorShortcuts.Command expected) throws Exception {
        FxTestSupport.onFxThread(() -> {
            List<EditorShortcuts.Command> calls = new ArrayList<>();
            Scene scene = new Scene(new BorderPane());
            EditorShortcuts.install(scene, calls::add);
            KeyCodeCombination shortcut = (KeyCodeCombination) expected.shortcut();
            press(scene, shortcut.getCode(), shortcut.getShift() == KeyCombination.ModifierValue.DOWN,
                    shortcut.getAlt() == KeyCombination.ModifierValue.DOWN, true);
            assertEquals(List.of(expected), calls);
            return null;
        });
    }

    @Test
    void folderChordOpensFolderOnceAndPlainShortcutStillOpensFile() throws Exception {
        FxTestSupport.onFxThread(() -> {
            List<EditorShortcuts.Command> calls = new ArrayList<>();
            Scene scene = new Scene(new BorderPane());
            EditorShortcuts.install(scene, calls::add);
            press(scene, KeyCode.K, false, false, true);
            assertTrue(calls.isEmpty());
            press(scene, KeyCode.O, false, false, true);
            press(scene, KeyCode.O, false, false, true);
            assertEquals(List.of(EditorShortcuts.Command.OPEN_FOLDER, EditorShortcuts.Command.OPEN_FILE), calls);
            return null;
        });
    }

    @Test
    void unrelatedTypingCancelsTheFolderChordAndDoesNotTriggerCommands() throws Exception {
        FxTestSupport.onFxThread(() -> {
            List<EditorShortcuts.Command> calls = new ArrayList<>();
            Scene scene = new Scene(new BorderPane());
            EditorShortcuts.install(scene, calls::add);
            press(scene, KeyCode.K, false, false, true);
            press(scene, KeyCode.X, false, false, false);
            press(scene, KeyCode.O, false, false, true);
            press(scene, KeyCode.S, false, false, false);
            assertEquals(List.of(EditorShortcuts.Command.OPEN_FILE), calls);
            return null;
        });
    }

    static void press(Scene scene, KeyCode key, boolean shift, boolean alt, boolean shortcut) {
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        scene.getRoot().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key,
                shift, shortcut && !mac, alt, shortcut && mac));
    }
}
