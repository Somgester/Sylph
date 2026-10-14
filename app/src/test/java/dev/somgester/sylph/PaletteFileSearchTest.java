package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaletteFileSearchTest {

    @TempDir
    Path directory;

    @Test
    void searchesRelativePathsCaseInsensitivelyAndSkipsGeneratedTreesAndLinks() throws Exception {
        Path source = Files.createDirectories(directory.resolve("app/src"));
        Path editor = Files.writeString(source.resolve("Editor.java"), "class Editor {}");
        Files.writeString(directory.resolve("README.md"), "readme");
        for (String name : List.of(".git", ".gradle", "node_modules")) {
            Files.writeString(Files.createDirectories(directory.resolve(name)).resolve("Editor.java"), "ignored");
        }
        Path outside = Files.createTempDirectory("sylph-search-outside-");
        try {
            Files.writeString(outside.resolve("Editor.java"), "outside");
            Files.createSymbolicLink(directory.resolve("linked"), outside);
            Files.createSymbolicLink(directory.resolve("linked.java"), editor);
            var result = PaletteFileSearch.search(directory, "APP editor");
            assertEquals(List.of(editor), result.files());
            assertFalse(result.limited());
            assertEquals(2, PaletteFileSearch.search(directory, "").files().size());
            assertTrue(PaletteFileSearch.matches("app/src/Editor.java", "APP\\SRC editor"));
        } finally {
            Files.delete(outside.resolve("Editor.java"));
            Files.delete(outside);
        }
    }

    @Test
    void capsMatchesAndReportsTruncationButNarrowQueryStillFindsFiles() throws Exception {
        for (int index = 0; index < 205; index++) {
            Files.writeString(directory.resolve("file-" + index + ".txt"), "");
        }
        var result = PaletteFileSearch.search(directory, "file");
        assertEquals(200, result.files().size());
        assertTrue(result.limited());
        assertEquals(List.of(directory.resolve("file-204.txt")),
                PaletteFileSearch.search(directory, "file-204").files());
    }

    @Test
    void interruptedSearchStopsWithoutClearingTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> PaletteFileSearch.search(directory, ""));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
