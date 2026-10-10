package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorLanguageTest {

    @ParameterizedTest
    @CsvSource({
        "Sample.java, JAVA",
        "Sample.JAVA, JAVA",
        "nested/file.JaVa, JAVA",
        "config.json, JSON",
        "config.JSON, JSON",
        ".config.JsOn, JSON",
        "notes.txt, PLAIN_TEXT",
        "README, PLAIN_TEXT",
        "java, PLAIN_TEXT",
        ".java, PLAIN_TEXT",
        ".json, PLAIN_TEXT",
        "file., PLAIN_TEXT",
        "Sample.java.bak, PLAIN_TEXT",
        "config.jsonc, PLAIN_TEXT",
        "folder.java/notes.txt, PLAIN_TEXT"
    })
    void usesOnlyTheFinalFileExtension(String name, EditorLanguage expected) {
        assertEquals(expected, EditorLanguage.forPath(Path.of(name)));
    }

    @Test
    void untitledAndPathsWithoutFileNamesArePlainText() {
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.forPath(null));
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.forPath(Path.of("")));
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.forPath(Path.of("/").toAbsolutePath().getRoot()));
    }
}
